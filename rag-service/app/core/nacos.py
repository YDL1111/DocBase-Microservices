"""
Nacos service registration for rag-service.
"""
import asyncio
import socket
import uuid
from typing import Optional

import httpx

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)


class NacosClient:
    """Handles Nacos service registration, heartbeat, and deregistration."""

    def __init__(self):
        self._server = settings.NACOS_SERVER
        self._namespace = settings.NACOS_NAMESPACE
        self._group = settings.NACOS_GROUP
        self._username = settings.NACOS_USERNAME
        self._password = settings.NACOS_PASSWORD
        self._service_name = settings.NACOS_SERVICE_NAME
        self._instance_id = str(uuid.uuid4())
        self._registered = False
        self._heartbeat_task: Optional[asyncio.Task] = None

    @property
    def _instance_ip(self) -> str:
        """Get the IP address to register with Nacos."""
        return socket.gethostbyname(socket.gethostname())

    @property
    def _instance_port(self) -> int:
        """Get the port to register with Nacos."""
        return settings.PORT

    async def start(self):
        """Register service with Nacos and start heartbeat."""
        try:
            await self._register()
            self._registered = True
            self._heartbeat_task = asyncio.create_task(self._heartbeat_loop())
            logger.info(f"Registered with Nacos: {self._service_name}@{self._instance_ip}:{self._instance_port}")
        except Exception as e:
            logger.warning(f"Failed to register with Nacos: {e}. Service will continue without registration.")

    async def stop(self):
        """Deregister service from Nacos."""
        if self._registered:
            try:
                await self._deregister()
                logger.info("Deregistered from Nacos")
            except Exception as e:
                logger.warning(f"Failed to deregister from Nacos: {e}")

        if self._heartbeat_task:
            self._heartbeat_task.cancel()

    async def _register(self):
        """Register service instance with Nacos."""
        url = f"http://{self._server}/nacos/v2/ns/instance"
        params = {
            "namespaceId": self._namespace,
            "groupName": self._group,
            "serviceName": self._service_name,
            "ip": self._instance_ip,
            "port": self._instance_port,
            "ephemeral": "true",
        }
        auth = (self._username, self._password)

        async with httpx.AsyncClient() as client:
            response = await client.post(url, params=params, auth=auth)
            response.raise_for_status()

    async def _deregister(self):
        """Deregister service instance from Nacos."""
        url = f"http://{self._server}/nacos/v2/ns/instance"
        params = {
            "namespaceId": self._namespace,
            "groupName": self._group,
            "serviceName": self._service_name,
            "ip": self._instance_ip,
            "port": self._instance_port,
            "ephemeral": "true",
        }
        auth = (self._username, self._password)

        async with httpx.AsyncClient() as client:
            response = await client.delete(url, params=params, auth=auth)
            response.raise_for_status()

    async def _heartbeat_loop(self):
        """Send periodic heartbeats to Nacos."""
        while True:
            try:
                await asyncio.sleep(settings.NACOS_HEARTBEAT_INTERVAL)
                await self._send_heartbeat()
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.warning(f"Nacos heartbeat failed: {e}")

    async def _send_heartbeat(self):
        """Send a heartbeat to Nacos."""
        url = f"http://{self._server}/nacos/v2/ns/instance/beat"
        params = {
            "namespaceId": self._namespace,
            "groupName": self._group,
            "serviceName": self._service_name,
            "ip": self._instance_ip,
            "port": self._instance_port,
            "ephemeral": "true",
        }
        auth = (self._username, self._password)

        async with httpx.AsyncClient() as client:
            response = await client.put(url, params=params, auth=auth)
            response.raise_for_status()


# Singleton instance
nacos_client = NacosClient()
