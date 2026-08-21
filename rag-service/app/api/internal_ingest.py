"""
Internal API for ingest-service to call RAG service.
"""
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.core.security import validate_internal_api_key
from app.db.session import get_db
from app.schemas.events import ChatRequest, RetrieveRequest
from app.services.rag import rag_service

router = APIRouter(
    prefix="/internal/v1/rag",
    dependencies=[Depends(validate_internal_api_key)],
    tags=["internal"],
)


@router.post("/retrieve")
async def retrieve(
    request: RetrieveRequest,
    db: Session = Depends(get_db),
):
    """
    Retrieve relevant documents with visibility filtering.

    Only returns documents in the visible_document_ids list.
    Empty visible_document_ids returns empty results (no documents visible).
    """
    results = rag_service.retrieve(
        query=request.query,
        knowledge_base_id=request.knowledge_base_id,
        visible_document_ids=request.visible_document_ids,
        top_k=request.top_k,
    )

    return {"results": results, "count": len(results)}


@router.post("/chat/stream")
async def chat_stream(
    request: ChatRequest,
    db: Session = Depends(get_db),
):
    """
    Stream RAG chat response with SSE.

    Only retrieves from documents in visible_document_ids.
    """
    from fastapi.responses import StreamingResponse

    async def event_generator():
        async for chunk in rag_service.chat_stream(
            query=request.query,
            knowledge_scopes=request.knowledge_scopes,
            knowledge_base_id=request.knowledge_base_id,
            visible_document_ids=request.visible_document_ids,
        ):
            yield f"data: {chunk}\n\n"

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
        },
    )
