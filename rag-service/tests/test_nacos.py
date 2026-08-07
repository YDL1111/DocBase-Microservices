"""
Tests for Nacos 3.1.1 registration, heartbeat, and deregistration.
"""
import pytest
from unittest.mock import AsyncMock, MagicMock, patch

from app.core.nacos import NacosClient


@pytest.fixture
def nacos_client():
    """Create a NacosClient with mocked network."""
    client = NacosClient()
    # Mock the _instance_ip property to return a fixed value
    with patch.object(type(client), "_instance_ip", new_callable=lambda: property(lambda self: "127.0.0.1")):
        yield client


class TestNacosRegistration:
    """Test Nacos 3.x registration flow."""

    @pytest.mark.asyncio
    async def test_register_uses_v3_api_with_token(self, nacos_client):
        """Registration should use /nacos/v3/client/ns/instance with Bearer token."""
        import asyncio

        # response.json() is synchronous in httpx - use MagicMock
        mock_token_response = MagicMock()
        mock_token_response.json.return_value = {"accessToken": "test-token", "ttl": 3600}
        mock_token_response.raise_for_status = MagicMock()

        mock_instance_response = MagicMock()
        mock_instance_response.raise_for_status = MagicMock()

        with patch("httpx.AsyncClient") as mock_client_class:
            mock_client = AsyncMock()
            mock_client_class.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client_class.return_value.__aexit__ = AsyncMock(return_value=False)

            # First call returns token, second returns instance registration
            mock_client.post.side_effect = [mock_token_response, mock_instance_response]
            mock_client.delete.return_value = mock_instance_response

            await nacos_client._register()

            # Verify token was obtained
            assert mock_client.post.call_count >= 1
            first_call = mock_client.post.call_args_list[0]
            assert "/nacos/v1/auth/login" in first_call[0][0]

            # Verify instance registration used v3 API with Bearer token
            instance_call = mock_client.post.call_args_list[1]
            assert "/nacos/v3/client/ns/instance" in instance_call[0][0]
            assert "Authorization" in instance_call[1]["headers"]
            assert instance_call[1]["headers"]["Authorization"] == "Bearer test-token"

    @pytest.mark.asyncio
    async def test_heartbeat_uses_same_post_with_heartbeat_flag(self, nacos_client):
        """Heartbeat should use same POST endpoint with heartBeat=true."""
        nacos_client._access_token = "cached-token"
        nacos_client._token_expiry = float("inf")  # Not expired

        mock_response = AsyncMock()
        mock_response.raise_for_status = AsyncMock()

        with patch("httpx.AsyncClient") as mock_client_class:
            mock_client = AsyncMock()
            mock_client_class.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client_class.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.post.return_value = mock_response

            await nacos_client._send_heartbeat()

            # Verify heartbeat uses POST with heartBeat=true
            call_args = mock_client.post.call_args
            assert "/nacos/v3/client/ns/instance" in call_args[0][0]
            assert call_args[1]["params"]["heartBeat"] == "true"

    @pytest.mark.asyncio
    async def test_deregister_uses_delete_with_token(self, nacos_client):
        """Deregistration should use DELETE with Bearer token."""
        nacos_client._access_token = "cached-token"
        nacos_client._token_expiry = float("inf")

        mock_response = AsyncMock()
        mock_response.raise_for_status = AsyncMock()

        with patch("httpx.AsyncClient") as mock_client_class:
            mock_client = AsyncMock()
            mock_client_class.return_value.__aenter__ = AsyncMock(return_value=mock_client)
            mock_client_class.return_value.__aexit__ = AsyncMock(return_value=False)
            mock_client.delete.return_value = mock_response

            await nacos_client._deregister()

            # Verify DELETE used v3 API
            call_args = mock_client.delete.call_args
            assert "/nacos/v3/client/ns/instance" in call_args[0][0]
            assert "Authorization" in call_args[1]["headers"]
