FROM python:3.11.13-slim-bookworm

# Create non-root user
RUN useradd --system --create-home --uid 10001 docbase

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Copy requirements first for better caching
COPY rag-service/requirements.txt /app/requirements.txt

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

# Copy application code and migrations
COPY rag-service/app /app/app
COPY rag-service/alembic.ini /app/alembic.ini
COPY rag-service/alembic /app/alembic

# Create chroma data directory and set permissions
RUN mkdir -p /data/chroma && chown -R docbase:docbase /data/chroma

# Switch to non-root user
USER docbase

EXPOSE 8090

# Healthcheck
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8090/health/live')" || exit 1

# Run migrations then start application
CMD ["sh", "-c", "alembic upgrade head && uvicorn app.main:app --host 0.0.0.0 --port 8090"]
