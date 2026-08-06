"""
Internal API key validation.
"""
import hmac
import logging

from fastapi import Header, HTTPException, status

from app.core.config import settings

logger = logging.getLogger(__name__)


async def validate_internal_api_key(x_internal_api_key: str = Header(None)):
    """
    Validate the internal API key for service-to-service communication.

    Health endpoints should not require this - they should be handled separately.
    """
    if not x_internal_api_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing X-Internal-Api-Key header"
        )

    # Constant-time comparison to prevent timing attacks
    if not hmac.compare_digest(x_internal_api_key, settings.INTERNAL_API_KEY):
        logger.warning("Invalid internal API key attempt")
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Invalid API key"
        )

    return True
