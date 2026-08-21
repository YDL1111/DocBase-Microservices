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
        assert values == {"history": "", "query": "你好"}
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
    embedding_calls = []

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
    monkeypatch.setattr(
        rag_module.embedding_service, "embed_query",
        lambda query: embedding_calls.append(query) or [0.1, 0.2],
    )
    monkeypatch.setattr(rag_module.ChatPromptTemplate, "from_messages", lambda _messages: MultiChain())
    monkeypatch.setattr(RAGService, "_get_llm", lambda self, streaming=False: object())

    events = [json.loads(item) async for item in RAGService().chat_stream(
        query=request.query, knowledge_scopes=request.knowledge_scopes)]

    assert sorted(calls) == [(1, [11]), (2, [21])]
    assert embedding_calls == ["跨库问题"]
    assert events[-2]["data"] == [
        {"document_id": 21, "file_name": "kb-2.txt", "page": None, "sheet": None,
         "slide": None, "heading_path": None, "block_type": None, "score": 0.9},
        {"document_id": 11, "file_name": "kb-1.txt", "page": None, "sheet": None,
         "slide": None, "heading_path": None, "block_type": None, "score": 0.7},
    ]


def test_follow_up_query_requires_rewrite_but_standalone_query_does_not():
    service = RAGService()
    history = "用户：介绍八大车间\n助手：八大车间包括……"
    assert service._needs_query_rewrite("第二个有什么作用？", history)
    assert not service._needs_query_rewrite("介绍知识铸造炉的作用", history)


def test_history_window_keeps_the_most_recent_messages_within_budget(monkeypatch):
    newest = "用户：最新问题"
    previous = "助手：最近回答"
    monkeypatch.setattr(rag_module.settings, "HISTORY_MAX_MESSAGES", 3)
    monkeypatch.setattr(rag_module.settings, "HISTORY_MAX_CHARS", len(newest) + len(previous))

    history = RAGService._history_text([
        {"role": "user", "content": "应该被预算淘汰的旧问题"},
        {"role": "assistant", "content": "最近回答"},
        {"role": "user", "content": "最新问题"},
    ])

    assert history == f"{previous}\n{newest}"


@pytest.mark.asyncio
async def test_follow_up_query_is_rewritten_for_retrieval(monkeypatch):
    observed = []

    class RewriteChain:
        def __or__(self, _other):
            return self

        async def ainvoke(self, values):
            observed.append(values)
            return "介绍八大车间中的第二个车间的作用"

    monkeypatch.setattr(
        rag_module.ChatPromptTemplate, "from_template", lambda _template: RewriteChain()
    )
    monkeypatch.setattr(RAGService, "_get_rewrite_llm", lambda self: object())

    rewritten = await RAGService()._rewrite_query(
        "第二个有什么作用？", "用户：介绍八大车间\n助手：八大车间包括……"
    )

    assert rewritten == "介绍八大车间中的第二个车间的作用"
    assert observed == [{
        "history": "用户：介绍八大车间\n助手：八大车间包括……",
        "query": "第二个有什么作用？",
    }]


@pytest.mark.asyncio
async def test_query_rewrite_failure_falls_back_to_original_query(monkeypatch):
    class FailingRewriteChain:
        def __or__(self, _other):
            return self

        async def ainvoke(self, _values):
            raise TimeoutError("rewrite timed out")

    monkeypatch.setattr(
        rag_module.ChatPromptTemplate, "from_template", lambda _template: FailingRewriteChain()
    )
    monkeypatch.setattr(RAGService, "_get_rewrite_llm", lambda self: object())

    original = "第二个有什么作用？"
    assert await RAGService()._rewrite_query(original, "用户：介绍八大车间") == original
