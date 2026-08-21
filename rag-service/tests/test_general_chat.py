import json

import pytest
from langchain_core.documents import Document

from app.schemas.events import ChatRequest, KnowledgeScope
from app.services import rag as rag_module
from app.services.rag import RAGService, SYSTEM_PROMPT
from app.services.vector_store import ScoredDocument


class FakeChain:
    def __or__(self, _other):
        return self

    async def astream(self, values):
        assert values == {"query": "你好"}
        yield "通用回答"


def test_knowledge_prompt_keeps_citations_outside_the_answer_body():
    assert "不要在回答正文中插入" in SYSTEM_PROMPT
    assert "引用由系统在正文后单独展示" in SYSTEM_PROMPT
    assert "【来源：文件名】" not in SYSTEM_PROMPT


@pytest.mark.asyncio
async def test_general_chat_does_not_require_or_query_a_knowledge_base(monkeypatch):
    request = ChatRequest(query="你好", knowledge_base_id=None, visible_document_ids=[])
    assert request.knowledge_base_id is None

    monkeypatch.setattr(rag_module.ChatPromptTemplate, "from_messages", lambda _messages: FakeChain())
    monkeypatch.setattr(RAGService, "_get_llm", lambda self, streaming=False: object())
    monkeypatch.setattr(rag_module.vector_store, "search",
                        lambda *_args, **_kwargs: pytest.fail("general chat must not query the vector store"))

    events = [json.loads(item) async for item in RAGService().chat_stream(
        query=request.query,
        knowledge_base_id=request.knowledge_base_id,
        visible_document_ids=request.visible_document_ids,
    )]

    assert [event["type"] for event in events] == ["metadata", "token", "sources", "done"]
    assert events[1]["content"] == "通用回答"
    assert events[2]["data"] == []


@pytest.mark.asyncio
async def test_multi_knowledge_chat_retrieves_each_scope(monkeypatch):
    request = ChatRequest(query="跨库问题", knowledge_scopes=[
        KnowledgeScope(knowledge_base_id=1, visible_document_ids=[11]),
        KnowledgeScope(knowledge_base_id=2, visible_document_ids=[21]),
    ])
    calls = []

    def fake_retrieve(query, knowledge_base_id, visible_document_ids, candidate_k):
        calls.append((knowledge_base_id, visible_document_ids))
        score = 0.7 if knowledge_base_id == 1 else 0.9
        embedding = (1.0, 0.0) if knowledge_base_id == 1 else (0.0, 1.0)
        return [ScoredDocument(
            document=Document(
                page_content=f"知识库 {knowledge_base_id}",
                metadata={
                    "chunk_id": f"kb-{knowledge_base_id}",
                    "chunk_index": 0,
                    "file_name": f"kb-{knowledge_base_id}.txt",
                    "document_id": visible_document_ids[0],
                },
            ),
            relevance_score=score,
            distance=(1.0 / score) - 1.0,
            embedding=embedding,
        )]

    class MultiChain:
        def __or__(self, _other): return self
        async def astream(self, values):
            assert "知识库 1" in values["context"] and "知识库 2" in values["context"]
            yield "跨库回答"

    monkeypatch.setattr(RAGService, "_retrieve_candidates", staticmethod(fake_retrieve))
    monkeypatch.setattr(rag_module.ChatPromptTemplate, "from_messages", lambda _messages: MultiChain())
    monkeypatch.setattr(RAGService, "_get_llm", lambda self, streaming=False: object())

    events = [json.loads(item) async for item in RAGService().chat_stream(
        query=request.query, knowledge_scopes=request.knowledge_scopes)]

    assert calls == [(1, [11]), (2, [21])]
    assert events[-2]["data"] == [
        {"document_id": 21, "file_name": "kb-2.txt", "page": None},
        {"document_id": 11, "file_name": "kb-1.txt", "page": None},
    ]
