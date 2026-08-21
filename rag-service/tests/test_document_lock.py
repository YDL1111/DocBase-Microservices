from contextlib import contextmanager

import pytest

from app.services import document_lock as document_lock_module
from app.services import ingestion as ingestion_module
from app.services.document_block import ParsedBlock
from app.services.document_lock import DocumentLifecycleLock, DocumentLockTimeoutError


class ScalarResult:
    def __init__(self, value):
        self.value = value

    def scalar(self):
        return self.value


class FakeSession:
    def __init__(self, acquire_result=1, fail_release=False):
        self.acquire_result = acquire_result
        self.fail_release = fail_release
        self.calls = []
        self.closed = False

    def execute(self, statement, params):
        sql = str(statement)
        self.calls.append((sql, params))
        if self.fail_release and "RELEASE_LOCK" in sql:
            raise RuntimeError("connection interrupted")
        return ScalarResult(self.acquire_result if "GET_LOCK" in sql else 1)

    def close(self):
        self.closed = True


def test_document_lock_acquires_and_releases_on_success(monkeypatch):
    session = FakeSession()
    monkeypatch.setattr(document_lock_module, "SessionLocal", lambda: session)

    with DocumentLifecycleLock(timeout_seconds=7).acquire(12, 34):
        assert len(session.calls) == 1

    assert "GET_LOCK" in session.calls[0][0]
    assert session.calls[0][1] == {
        "lock_name": "docbase-rag:12:34", "timeout_seconds": 7
    }
    assert "RELEASE_LOCK" in session.calls[1][0]
    assert session.closed


def test_document_lock_timeout_does_not_release_unowned_lock(monkeypatch):
    session = FakeSession(acquire_result=0)
    monkeypatch.setattr(document_lock_module, "SessionLocal", lambda: session)

    with pytest.raises(DocumentLockTimeoutError, match="DOCUMENT_LOCK_TIMEOUT"):
        with DocumentLifecycleLock().acquire(1, 2):
            raise AssertionError("critical section must not run")

    assert len(session.calls) == 1
    assert session.closed


def test_document_lock_releases_when_critical_section_fails(monkeypatch):
    session = FakeSession()
    monkeypatch.setattr(document_lock_module, "SessionLocal", lambda: session)

    with pytest.raises(RuntimeError, match="chroma failed"):
        with DocumentLifecycleLock().acquire(1, 2):
            raise RuntimeError("chroma failed")

    assert "RELEASE_LOCK" in session.calls[-1][0]
    assert session.closed


def test_document_lock_closes_connection_when_explicit_release_fails(monkeypatch):
    session = FakeSession(fail_release=True)
    monkeypatch.setattr(document_lock_module, "SessionLocal", lambda: session)

    with pytest.raises(RuntimeError, match="connection interrupted"):
        with DocumentLifecycleLock().acquire(1, 2):
            pass

    assert session.closed


@pytest.mark.asyncio
async def test_ingestion_deletes_use_the_same_document_lock(monkeypatch):
    acquired = []

    @contextmanager
    def acquire(knowledge_base_id, document_id):
        acquired.append((knowledge_base_id, document_id))
        yield

    monkeypatch.setattr(ingestion_module.document_lifecycle_lock, "acquire", acquire)
    monkeypatch.setattr(ingestion_module.vector_store, "delete_document", lambda *_args: 3)
    monkeypatch.setattr(
        ingestion_module.vector_store, "delete_document_version", lambda *_args: 2
    )

    service = ingestion_module.IngestionService()
    assert await service.delete_document(5, 8) == 3
    assert await service.delete_document_version(5, 8, 13) == 2
    assert acquired == [(5, 8), (5, 8)]


@pytest.mark.asyncio
async def test_ingestion_upsert_runs_inside_document_lock(monkeypatch):
    lock_active = False
    observed = []

    @contextmanager
    def acquire(knowledge_base_id, document_id):
        nonlocal lock_active
        observed.append(("acquire", knowledge_base_id, document_id))
        lock_active = True
        try:
            yield
        finally:
            lock_active = False

    def upsert(*_args, **_kwargs):
        observed.append(("upsert", lock_active))
        return 1

    monkeypatch.setattr(ingestion_module.document_lifecycle_lock, "acquire", acquire)
    monkeypatch.setattr(ingestion_module.object_storage, "download_file", lambda _key: b"text")
    monkeypatch.setattr(
        ingestion_module.DocumentParser,
        "parse",
        lambda *_args: ([ParsedBlock("searchable text", "prose")], {"file_type": "txt"}),
    )
    monkeypatch.setattr(ingestion_module.vector_store, "upsert_chunks", upsert)

    result = await ingestion_module.IngestionService().ingest_document(
        5, 8, 13, "objects/doc.txt", "doc.txt", "text/plain"
    )

    assert result["chunk_count"] == 1
    assert observed == [("acquire", 5, 8), ("upsert", True)]


@pytest.mark.asyncio
async def test_ingestion_persists_business_metadata_on_chunks(monkeypatch):
    captured_chunks = []

    @contextmanager
    def acquire(_knowledge_base_id, _document_id):
        yield

    def upsert(_knowledge_base_id, _document_id, _version_id, chunks, **_kwargs):
        captured_chunks.extend(chunks)
        return len(chunks)

    monkeypatch.setattr(ingestion_module.document_lifecycle_lock, "acquire", acquire)
    monkeypatch.setattr(ingestion_module.object_storage, "download_file", lambda _key: b"text")
    monkeypatch.setattr(
        ingestion_module.DocumentParser,
        "parse",
        lambda *_args: ([ParsedBlock(
            "searchable text", "prose", metadata={"heading_path": "第一章 / 概述"}
        )], {"file_type": "txt"}),
    )
    monkeypatch.setattr(ingestion_module.vector_store, "upsert_chunks", upsert)

    await ingestion_module.IngestionService().ingest_document(
        5, 8, 13, "objects/doc.txt", "doc.txt", "text/plain",
        document_title="安全生产手册", folder_id=12, visibility=1,
        document_created_at="2026-08-20T01:02:03Z",
        document_updated_at="2026-08-21T04:05:06Z",
    )

    assert len(captured_chunks) == 1
    metadata = captured_chunks[0].metadata
    assert metadata["document_title"] == "安全生产手册"
    assert metadata["folder_id"] == 12
    assert metadata["visibility"] == 1
    assert metadata["document_created_at"] == "2026-08-20T01:02:03Z"
    assert metadata["document_updated_at"] == "2026-08-21T04:05:06Z"
    assert metadata["heading_path"] == "第一章 / 概述"
    assert metadata["ingested_at"]
