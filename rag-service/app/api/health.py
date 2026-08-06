"""
Health check endpoints.
"""
from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.db.session import get_db

router = APIRouter(tags=["health"])


@router.get("/health/live")
async def liveness():
    """
    Liveness probe - indicates the service is running.
    Does NOT check dependencies or load models.
    """
    return {"status": "UP", "service": "rag-service"}


@router.get("/health/ready")
async def readiness(db: Session = Depends(get_db)):
    """
    Readiness probe - indicates the service is ready to accept traffic.
    Checks database connectivity.
    """
    try:
        # Check database
        db.execute("SELECT 1")
        db_ok = True
    except Exception:
        db_ok = False

    return {
        "status": "UP" if db_ok else "DOWN",
        "checks": {
            "database": "UP" if db_ok else "DOWN",
        },
    }


@router.get("/metrics")
async def metrics():
    """Prometheus metrics endpoint."""
    from prometheus_client import generate_latest, CONTENT_TYPE_LATEST, REGISTRY

    return Response(
        content=generate_latest(REGISTRY),
        media_type=CONTENT_TYPE_LATEST,
    )


from fastapi import Response
from prometheus_client import REGISTRY, CONTENT_TYPE_LATEST, generate_latest
