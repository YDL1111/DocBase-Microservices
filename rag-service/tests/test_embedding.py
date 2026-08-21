from app.services import embedding as embedding_module
from app.services.embedding import EmbeddingService


class FakeEmbeddingModel:
    def __init__(self):
        self.queries = []

    def embed_query(self, text):
        self.queries.append(text)
        return [1.0, 2.0, 3.0]


def test_repeated_query_reuses_cached_embedding(monkeypatch):
    model = FakeEmbeddingModel()
    monkeypatch.setattr(embedding_module, "get_embedding_model", lambda: model)
    service = EmbeddingService()

    assert service.embed_query("same question") == [1.0, 2.0, 3.0]
    assert service.embed_query("same question") == [1.0, 2.0, 3.0]
    assert model.queries == ["same question"]


def test_warmup_runs_real_query_and_rejects_no_vector(monkeypatch):
    model = FakeEmbeddingModel()
    monkeypatch.setattr(embedding_module, "get_embedding_model", lambda: model)
    service = EmbeddingService()

    service.warmup()

    assert model.queries == ["DocBase embedding warmup"]
