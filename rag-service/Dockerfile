FROM python:3.11.13-slim-bookworm
RUN useradd --system --create-home --uid 10001 docbase
WORKDIR /app
COPY rag-service/placeholder.py /app/placeholder.py
USER docbase
EXPOSE 8090
CMD ["python", "/app/placeholder.py"]
