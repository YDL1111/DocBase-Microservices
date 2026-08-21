"""
RAG inference service - handles retrieval and generation.
"""
import re
import time
from math import sqrt
from typing import AsyncGenerator, List, Optional, Sequence

from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

from app.core.config import settings
from app.core.logging import get_logger
from app.services.vector_store import vector_store
from app.services.vector_store import ScoredDocument

logger = get_logger(__name__)

SYSTEM_PROMPT = """你是企业内部知识库助手，请严格基于参考文档回答用户问题。
回答规则：
1. 优先提取参考文档中最直接、最关键的信息作答，不要泛泛而谈。
2. 如果用户是在问某一篇文档的内容，请优先总结该文档中的核心段落，不要被无关片段干扰。
3. 如果已经召回到相关片段，但信息不完整，可以先给出基于已知内容的结论，再明确说明不确定部分。
4. 只有在参考文档里确实没有可支撑回答的内容时，才回答"未找到相关信息"。
5. 不要回答"请提供更具体的问题"这类空泛兜底话术，除非用户的问题本身完全无法理解。
6. 使用中文和规范 Markdown 排版回答；不要在回答正文中插入“来源”“引用”或文件名标记，引用由系统在正文后单独展示。
参考文档：
{context}
"""

GENERAL_SYSTEM_PROMPT = """你是 DocBase 智能助手。请直接、准确、简洁地回答用户问题。
当信息不足或无法确定时，明确说明不确定性，不要编造事实或伪造来源。默认使用中文回答。"""


class RAGService:
    """Service for RAG-based question answering."""

    @staticmethod
    def _escape_prompt_text(text: str) -> str:
        if not text:
            return ""
        return text.replace("{", "{{").replace("}", "}}")

    @staticmethod
    def _normalize_text(text: str) -> str:
        if not text:
            return ""
        text = text.lower()
        text = re.sub(r"\.(pdf|doc|docx|txt)$", " ", text)
        text = re.sub(r"[^0-9a-z一-鿿]+", " ", text)
        text = re.sub(r"\s+", " ", text)
        return text.strip()

    def _get_llm(self, streaming: bool = False) -> ChatOpenAI:
        return ChatOpenAI(
            api_key=settings.CHAT_API_KEY,
            base_url=settings.CHAT_BASE_URL,
            model=settings.CHAT_MODEL,
            streaming=streaming,
            temperature=0.2,
            max_tokens=2048,
        )

    def retrieve(
        self,
        query: str,
        knowledge_base_id: int,
        visible_document_ids: List[int],
        top_k: Optional[int] = None,
    ) -> List[dict]:
        """
        Retrieve relevant documents with visibility filtering.

        Args:
            query: The search query
            knowledge_base_id: The knowledge base ID
            visible_document_ids: Document IDs the user can access (required)
            top_k: Number of results to return

        Returns:
            List of document chunks with metadata
        """
        if not visible_document_ids:
            return []

        result_limit = min(max(top_k or settings.RERANK_TOP_K, 1), 50)
        candidates = self._retrieve_candidates(
            query,
            knowledge_base_id,
            visible_document_ids,
            max(settings.RETRIEVAL_CANDIDATE_K, result_limit * 3),
        )
        chunks = self._rank_candidates(candidates, result_limit)

        results = []
        for candidate in chunks:
            results.append(self._candidate_to_result(candidate))

        return results

    @staticmethod
    def _retrieve_candidates(query: str, knowledge_base_id: int,
                             visible_document_ids: List[int],
                             candidate_k: int) -> list[ScoredDocument]:
        return vector_store.search_candidates(
            knowledge_base_id, query, visible_document_ids, candidate_k
        )

    def _rank_candidates(self, candidates: list[ScoredDocument], limit: int) -> list[ScoredDocument]:
        deduplicated = self._deduplicate_candidates(candidates)
        if not deduplicated or limit <= 0:
            return []

        remaining = sorted(
            deduplicated,
            key=lambda item: (-item.relevance_score,
                              str(item.document.metadata.get("chunk_id", ""))),
        )
        selected: list[ScoredDocument] = []
        mmr_lambda = min(max(settings.MMR_LAMBDA, 0.0), 1.0)

        while remaining and len(selected) < limit:
            if not selected:
                selected.append(remaining.pop(0))
                continue

            best_index = 0
            best_value = float("-inf")
            for index, candidate in enumerate(remaining):
                redundancy = max(
                    self._cosine_similarity(candidate.embedding, chosen.embedding)
                    for chosen in selected
                )
                mmr_value = (mmr_lambda * candidate.relevance_score
                             - (1.0 - mmr_lambda) * redundancy)
                if mmr_value > best_value:
                    best_index = index
                    best_value = mmr_value
            selected.append(remaining.pop(best_index))
        return selected

    def _deduplicate_candidates(self, candidates: list[ScoredDocument]) -> list[ScoredDocument]:
        ordered = sorted(candidates, key=lambda item: item.relevance_score, reverse=True)
        kept: list[ScoredDocument] = []
        seen_chunk_ids: set[str] = set()
        seen_texts: set[str] = set()

        for candidate in ordered:
            metadata = candidate.document.metadata
            chunk_id = str(metadata.get("chunk_id") or "")
            normalized_text = re.sub(r"\s+", " ", candidate.document.page_content).strip()
            if (chunk_id and chunk_id in seen_chunk_ids) or normalized_text in seen_texts:
                continue

            document_id = metadata.get("document_id")
            chunk_index = metadata.get("chunk_index")
            is_adjacent_duplicate = False
            for existing in kept:
                existing_metadata = existing.document.metadata
                if document_id != existing_metadata.get("document_id"):
                    continue
                existing_index = existing_metadata.get("chunk_index")
                if not isinstance(chunk_index, int) or not isinstance(existing_index, int):
                    continue
                if abs(chunk_index - existing_index) > 1:
                    continue
                if self._cosine_similarity(candidate.embedding, existing.embedding) >= settings.NEAR_DUPLICATE_THRESHOLD:
                    is_adjacent_duplicate = True
                    break
            if is_adjacent_duplicate:
                continue

            if chunk_id:
                seen_chunk_ids.add(chunk_id)
            seen_texts.add(normalized_text)
            kept.append(candidate)
        return kept

    @staticmethod
    def _cosine_similarity(left: tuple[float, ...], right: tuple[float, ...]) -> float:
        if not left or not right or len(left) != len(right):
            return 0.0
        left_norm = sqrt(sum(value * value for value in left))
        right_norm = sqrt(sum(value * value for value in right))
        if left_norm == 0.0 or right_norm == 0.0:
            return 0.0
        similarity = sum(a * b for a, b in zip(left, right)) / (left_norm * right_norm)
        return min(max(similarity, -1.0), 1.0)

    @staticmethod
    def _candidate_to_result(candidate: ScoredDocument) -> dict:
        chunk = candidate.document
        return {
            "content": chunk.page_content,
            "metadata": chunk.metadata,
            "file_name": chunk.metadata.get("file_name", "unknown"),
            "document_id": chunk.metadata.get("document_id"),
            "page": chunk.metadata.get("page"),
            "score": candidate.relevance_score,
        }

    @staticmethod
    def _pack_context(results: list[dict], max_length: int) -> tuple[str, list[dict]]:
        context_parts: list[str] = []
        included: list[dict] = []
        used_length = 0
        for result in results:
            part = f"[来源: {result['file_name']}]\n{result['content']}"
            separator_length = 2 if context_parts else 0
            if used_length + separator_length + len(part) > max_length:
                continue
            context_parts.append(part)
            included.append(result)
            used_length += separator_length + len(part)
        return "\n\n".join(context_parts), included

    async def chat_stream(
        self,
        query: str,
        knowledge_base_id: Optional[int] = None,
        visible_document_ids: Optional[List[int]] = None,
        knowledge_scopes: Optional[Sequence[object]] = None,
    ) -> AsyncGenerator[str, None]:
        """
        Stream RAG chat response with SSE.

        Yields JSON strings for each event:
        - {"type": "metadata", "sources": [...]}
        - {"type": "token", "content": "..."}
        - {"type": "sources", "data": [...]}
        - {"type": "done"}
        - {"type": "error", "message": "..."}
        """
        import json

        started_at = time.perf_counter()
        retrieval_ms = 0.0
        first_token_logged = False
        try:
            scopes: list[tuple[int, list[int]]] = []
            for scope in knowledge_scopes or []:
                scope_id = getattr(scope, "knowledge_base_id", None)
                scope_docs = getattr(scope, "visible_document_ids", None)
                if scope_id is not None:
                    scopes.append((int(scope_id), list(scope_docs or [])))
            if not scopes and knowledge_base_id is not None:
                scopes.append((knowledge_base_id, list(visible_document_ids or [])))

            if not scopes:
                yield json.dumps({"type": "metadata", "sources": []})
                prompt = ChatPromptTemplate.from_messages([
                    ("system", GENERAL_SYSTEM_PROMPT),
                    ("human", "{query}"),
                ])
                chain = prompt | self._get_llm(streaming=True) | StrOutputParser()
                async for chunk in chain.astream({"query": query}):
                    if chunk:
                        yield json.dumps({"type": "token", "content": chunk})
                yield json.dumps({"type": "sources", "data": []})
                yield json.dumps({"type": "done"})
                return

            # Retrieve relevant documents
            retrieval_started_at = time.perf_counter()
            final_top_k = min(max(settings.RERANK_TOP_K, 1), 50)
            total_candidate_k = max(settings.RETRIEVAL_CANDIDATE_K, final_top_k * 3)
            per_base_candidate_k = max(final_top_k, (total_candidate_k + len(scopes) - 1) // len(scopes))
            candidates: list[ScoredDocument] = []
            for scope_id, scope_document_ids in scopes:
                candidates.extend(self._retrieve_candidates(
                    query, scope_id, scope_document_ids, per_base_candidate_k
                ))
            results = [self._candidate_to_result(candidate) for candidate in self._rank_candidates(
                candidates, final_top_k
            )]
            retrieval_ms = (time.perf_counter() - retrieval_started_at) * 1000
            logger.info(
                "RAG retrieval completed scopes=%d results=%d retrieval_ms=%.1f",
                len(scopes), len(results), retrieval_ms,
            )

            if not results:
                yield json.dumps({"type": "metadata", "sources": []})
                yield json.dumps({"type": "token", "content": "未找到相关信息。"})
                yield json.dumps({"type": "done"})
                return

            context, included_results = self._pack_context(results, settings.MAX_CONTEXT_LENGTH)
            if not included_results:
                yield json.dumps({"type": "metadata", "sources": []})
                yield json.dumps({"type": "token", "content": "未找到相关信息。"})
                yield json.dumps({"type": "done"})
                return

            sources = []
            seen_docs = set()

            for r in included_results:
                doc_id = r["document_id"]
                source_info = {
                    "document_id": doc_id,
                    "file_name": r["file_name"],
                    "page": r.get("page"),
                }
                if doc_id not in seen_docs:
                    sources.append(source_info)
                    seen_docs.add(doc_id)

            # Send metadata
            yield json.dumps({"type": "metadata", "sources": sources})

            # Generate response with streaming
            prompt = ChatPromptTemplate.from_messages([
                ("system", SYSTEM_PROMPT),
                ("human", "用户问题：{query}\n请基于以上参考文档回答。"),
            ])

            llm = self._get_llm(streaming=True)
            chain = prompt | llm | StrOutputParser()

            async for chunk in chain.astream({"context": context, "query": query}):
                if chunk:
                    if not first_token_logged:
                        first_token_logged = True
                        logger.info(
                            "RAG first token scopes=%d retrieval_ms=%.1f first_token_ms=%.1f",
                            len(scopes), retrieval_ms,
                            (time.perf_counter() - started_at) * 1000,
                        )
                    yield json.dumps({"type": "token", "content": chunk})

            # Send final sources
            yield json.dumps({"type": "sources", "data": sources})
            yield json.dumps({"type": "done"})
            logger.info(
                "RAG stream completed scopes=%d total_ms=%.1f",
                len(scopes), (time.perf_counter() - started_at) * 1000,
            )

        except Exception as e:
            logger.error(f"RAG chat error: {e}")
            yield json.dumps({"type": "error", "message": str(e)})


# Singleton instance
rag_service = RAGService()
