"""Validation shared by object-storage callers."""


def is_valid_object_key(object_key: str) -> bool:
    """Accept MinIO UTF-8 names while rejecting traversal and control input."""
    if not object_key or len(object_key.encode("utf-8")) > 1024:
        return False
    if object_key.startswith("/") or "\\" in object_key:
        return False
    if any(ord(char) < 32 or ord(char) == 127 for char in object_key):
        return False

    segments = object_key.split("/")
    return all(segment not in ("", ".", "..") for segment in segments)
