from app.services import vector_store as vector_store_module
from app.services.vector_store import VectorStoreService


class FakeRawCollection:
    def query(self, **kwargs):
        assert kwargs["n_results"] == 9
        assert kwargs["where"]["$and"][1] == {"document_id": {"$in": [7]}}
        assert "embeddings" in kwargs["include"]
        return {
            "ids": [["old", "new-low", "new-high"]],
            "documents": [["旧版本", "新版本低分", "新版本高分"]],
            "metadatas": [[
                {"document_id": 7, "version_id": 1},
                {"document_id": 7, "version_id": 2},
                {"document_id": 7, "version_id": 2},
            ]],
            "distances": [[0.01, 0.4, 0.1]],
            "embeddings": [[[1.0, 0.0], [0.0, 1.0], [0.8, 0.2]]],
        }


class FakeCollection:
    _collection = FakeRawCollection()

    def get(self, **_kwargs):
        return {"metadatas": [
            {"document_id": 7, "version_id": 1},
            {"document_id": 7, "version_id": 2},
        ]}


def test_search_candidates_preserves_scores_embeddings_and_latest_version(monkeypatch):
    service = VectorStoreService()
    monkeypatch.setattr(service, "_get_collection", lambda _knowledge_base_id: FakeCollection())
    monkeypatch.setattr(vector_store_module.embedding_service, "embed_query", lambda _query: [0.5, 0.5])

    candidates = service.search_candidates(1, "问题", [7], 3)

    assert [item.document.metadata["chunk_id"] for item in candidates] == ["new-high", "new-low"]
    assert candidates[0].relevance_score > candidates[1].relevance_score
    assert candidates[0].embedding == (0.8, 0.2)


def test_reindex_removes_only_stale_chunks_from_the_same_version():
    class Collection:
        deleted = []

        def get(self, **_kwargs):
            return {"ids": ["chunk-0", "chunk-1", "chunk-2"]}

        def delete(self, ids):
            self.deleted.extend(ids)

    collection = Collection()

    deleted = VectorStoreService._delete_stale_chunk_ids(
        collection, document_id=7, version_id=2, current_ids={"chunk-0", "chunk-1"}
    )

    assert deleted == 1
    assert collection.deleted == ["chunk-2"]


def test_search_does_not_return_old_version_when_latest_chunk_missed_candidate_window(monkeypatch):
    class OldOnlyRawCollection(FakeRawCollection):
        def query(self, **_kwargs):
            return {
                "ids": [["old"]],
                "documents": [["旧版本高相似内容"]],
                "metadatas": [[{"document_id": 7, "version_id": 1}]],
                "distances": [[0.01]],
                "embeddings": [[[1.0, 0.0]]],
            }

    class Collection(FakeCollection):
        _collection = OldOnlyRawCollection()

    service = VectorStoreService()
    monkeypatch.setattr(service, "_get_collection", lambda _knowledge_base_id: Collection())
    monkeypatch.setattr(vector_store_module.embedding_service, "embed_query", lambda _query: [1.0, 0.0])

    assert service.search_candidates(1, "问题", [7], 3) == []


def test_new_version_write_failure_keeps_the_previous_version(monkeypatch):
    operations = []

    class Collection:
        def add_documents(self, **_kwargs):
            operations.append("add")
            raise RuntimeError("chroma unavailable")

    service = VectorStoreService()
    monkeypatch.setattr(service, "_get_collection", lambda _knowledge_base_id: Collection())
    monkeypatch.setattr(service, "_get_max_version_id", lambda _collection, _document_id: 1)
    monkeypatch.setattr(service, "delete_versions_older_than",
                        lambda *_args: operations.append("delete-old"))

    from langchain_core.documents import Document
    try:
        service.upsert_chunks(1, 7, 2, [Document(page_content="new", metadata={})])
    except RuntimeError as exception:
        assert str(exception) == "chroma unavailable"
    else:
        raise AssertionError("upsert should have failed")

    assert operations == ["add"]


def test_concurrently_superseded_write_deletes_its_own_version(monkeypatch):
    deleted = []

    class Collection:
        def add_documents(self, **_kwargs):
            pass

    service = VectorStoreService()
    versions = iter([1, 3])
    monkeypatch.setattr(service, "_get_collection", lambda _knowledge_base_id: Collection())
    monkeypatch.setattr(service, "_get_max_version_id", lambda *_args: next(versions))
    monkeypatch.setattr(service, "_delete_version_chunks",
                        lambda _collection, document_id, version_id: deleted.append((document_id, version_id)))

    from langchain_core.documents import Document
    count = service.upsert_chunks(1, 7, 2, [Document(page_content="v2", metadata={})])

    assert count == 0
    assert deleted == [(7, 2)]
