"""
MinIO object storage service for document download.
"""
import io
import re
from urllib.parse import urlparse

import httpx
from minio import Minio

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)


class ObjectStorageService:
    """Service for downloading documents from MinIO."""

    def __init__(self):
        self._client = None

    @property
    def client(self) -> Minio:
        """Lazy-initialize MinIO client."""
        if self._client is None:
            self._client = Minio(
                endpoint=settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", ""),
                access_key=settings.MINIO_ACCESS_KEY,
                secret_key=settings.MINIO_SECRET_KEY,
                secure=settings.MINIO_SECURE,
            )
        return self._client

    def download_file(self, object_key: str) -> bytes:
        """
        Download a file from MinIO by object key.

        Args:
            object_key: The MinIO object key (e.g., documents/123/456/file.pdf)

        Returns:
            File content as bytes

        Raises:
            ValueError: If the object key is invalid
            Exception: If download fails
        """
        # Validate object key to prevent path traversal
        if not self._is_valid_object_key(object_key):
            raise ValueError(f"Invalid object key: {object_key}")

        try:
            response = self.client.get_object(
                bucket_name=settings.MINIO_BUCKET,
                object_name=object_key,
            )
            data = response.read()
            response.close()
            response.release_conn()

            if len(data) > settings.MINIO_MAX_FILE_SIZE:
                raise ValueError(f"File exceeds maximum size: {len(data)} bytes")

            logger.info(f"Downloaded file: {object_key} ({len(data)} bytes)")
            return data

        except Exception as e:
            logger.error(f"Failed to download file {object_key}: {e}")
            raise

    def download_from_presigned_url(self, url: str) -> bytes:
        """
        Download a file from a presigned URL.

        Only allows URLs from the configured MinIO endpoint to prevent SSRF.
        """
        parsed = urlparse(url)

        # Validate URL to prevent SSRF
        allowed_host = settings.MINIO_ENDPOINT.replace("http://", "").replace("https://", "").split(":")[0]
        if parsed.hostname != allowed_host:
            raise ValueError(f"URL host not allowed: {parsed.hostname}")

        if parsed.scheme not in ("http", "https"):
            raise ValueError(f"Invalid URL scheme: {parsed.scheme}")

        try:
            with httpx.Client(timeout=30.0) as client:
                response = client.get(url)
                response.raise_for_status()

                data = response.content
                if len(data) > settings.MINIO_MAX_FILE_SIZE:
                    raise ValueError(f"File exceeds maximum size: {len(data)} bytes")

                return data

        except httpx.HTTPError as e:
            logger.error(f"Failed to download from URL: {e}")
            raise

    @staticmethod
    def _is_valid_object_key(object_key: str) -> bool:
        """Validate object key to prevent path traversal."""
        if not object_key:
            return False
        # Must not contain .. or start with /
        if ".." in object_key or object_key.startswith("/"):
            return False
        # Must match expected pattern
        return bool(re.match(r"^[a-zA-Z0-9/_.-]+$", object_key))


# Singleton instance
object_storage = ObjectStorageService()
