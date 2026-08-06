"""
RAG inference service - handles retrieval and generation.
"""
import re
from typing import AsyncGenerator, List, Optional

from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from langchain_openai import ChatOpenAI

from app.core.config import settings
from app.core.logging import get_logger
from app.services.vector_store import vector_store

logger = get_logger(__name__)

SYSTEM_PROMPT = """你是企业内部知识库助手，请严格基于参考文档回答用户问题。
回答规则：
1. 优先提取参考文档中最直接、最关键的信息作答，不要泛泛而谈。
2. 如果用户是在问某一篇文档的内容，请优先总结该文档中的核心段落，不要被无关片段干扰。
3. 如果已经召回到相关片段，但信息不完整，可以先给出基于已知内容的结论，再明确说明不确定部分。
4. 只有在参考文档里确实没有可支撑回答的内容时，才回答"未找到相关信息"。
5. 不要回答"请提供更具体的问题"这类空泛兜底话术，除非用户的问题本身完全无法理解。
6. 回答要使用中文，并在合适的位置自然标注来源，例如：【来源：文件名】。
参考文档：
{context}
"""


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
            api_key=settings.DEEPSEEK_API_KEY,
            base_url=settings.DEEPSEEK_BASE_URL,
            model=settings.DEEPSEEK_MODEL,
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

        chunks = vector_store.search(
            knowledge_base_id, query, visible_document_ids, top_k
        )

        results = []
        for chunk in chunks:
            results.append({
                "content": chunk.page_content,
                "metadata": chunk.metadata,
                "file_name": chunk.metadata.get("file_name", "unknown"),
                "document_id": chunk.metadata.get("document_id"),
                "page": chunk.metadata.get("page"),
            })

        return results

    async def chat_stream(
        self,
        query: str,
        knowledge_base_id: int,
        visible_document_ids: List[int],
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

        try:
            # Retrieve relevant documents
            results = self.retrieve(query, knowledge_base_id, visible_document_ids)

            if not results:
                yield json.dumps({"type": "metadata", "sources": []})
                yield json.dumps({"type": "token", "content": "未找到相关信息。"})
                yield json.dumps({"type": "done"})
                return

            # Format context
            context_parts = []
            sources = []
            seen_docs = set()

            for r in results:
                doc_id = r["document_id"]
                source_info = {
                    "document_id": doc_id,
                    "file_name": r["file_name"],
                    "page": r.get("page"),
                }
                if doc_id not in seen_docs:
                    sources.append(source_info)
                    seen_docs.add(doc_id)

                context_parts.append(
                    f"[来源: {r['file_name']}]\n{r['content']}"
                )

            context = "\n\n".join(context_parts)
            if len(context) > settings.MAX_CONTEXT_LENGTH:
                context = context[:settings.MAX_CONTEXT_LENGTH]

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
                    yield json.dumps({"type": "token", "content": chunk})

            # Send final sources
            yield json.dumps({"type": "sources", "data": sources})
            yield json.dumps({"type": "done"})

        except Exception as e:
            logger.error(f"RAG chat error: {e}")
            yield json.dumps({"type": "error", "message": str(e)})


# Singleton instance
rag_service = RAGService()
