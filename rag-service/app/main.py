"""
RAG Service - FastAPI application entry point.
"""
import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.config import settings
from app.core.logging import setup_logging, get_logger
from app.core.nacos import nacos_client
from app.api.health import router as health_router
from app.api.internal_ingest import router as internal_router
from app.messaging.consumer import RagConsumer
from app.messaging.publisher import OutboxPublisher
from app.services.event_handler import EventHandler

setup_logging()
logger = get_logger(__name__)


# Global references for cleanup
consumer: RagConsumer = None
publisher: OutboxPublisher = None
background_tasks = []


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan manager - handles startup and shutdown."""
    global consumer, publisher

    # Startup
    logger.info(f"Starting {settings.APP_NAME} v{settings.APP_VERSION}")

    # Register with Nacos (non-blocking - failure doesn't prevent startup)
    await nacos_client.start()

    # Start Outbox Publisher
    publisher = OutboxPublisher()
    await publisher.start()
    logger.info("Outbox publisher started")

    # Start RabbitMQ Consumer
    event_handler = EventHandler()
    consumer = RagConsumer(message_handler=event_handler.handle_event)
    await consumer.start()
    logger.info("RAG consumer started")

    # Start background outbox polling task
    async def poll_outbox():
        from app.db.session import SessionLocal
        while True:
            try:
                db = SessionLocal()
                try:
                    await publisher.publish_pending_events(db)
                finally:
                    db.close()
            except Exception as e:
                logger.error(f"Outbox polling error: {e}")
            await asyncio.sleep(5)  # Poll every 5 seconds

    poll_task = asyncio.create_task(poll_outbox())
    background_tasks.append(poll_task)

    logger.info("RAG service started successfully")

    yield

    # Shutdown
    logger.info("Shutting down RAG service")

    # Cancel background tasks
    for task in background_tasks:
        task.cancel()

    # Stop consumer
    if consumer:
        await consumer.stop()

    # Stop publisher
    if publisher:
        await publisher.stop()

    # Deregister from Nacos
    await nacos_client.stop()


app = FastAPI(
    title=settings.APP_NAME,
    version=settings.APP_VERSION,
    lifespan=lifespan,
)

# Include routers
app.include_router(health_router)
app.include_router(internal_router)


@app.get("/")
async def root():
    return {"service": settings.APP_NAME, "version": settings.APP_VERSION}
