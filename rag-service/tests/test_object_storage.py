"""Object-key contract tests shared with knowledge-service uploads."""

from app.services.object_key import is_valid_object_key


def test_accepts_unicode_and_spaces_in_generated_knowledge_object_key():
    key = "knowledge/1/2026/08/c5a5c342-bff9-4a81-aa26-00f4bad6741a/八大车间 解说V1.0.docx"
    assert is_valid_object_key(key)


def test_rejects_path_traversal_and_unsafe_characters():
    invalid_keys = [
        "../secret.docx",
        "knowledge/1/../secret.docx",
        "/knowledge/1/file.docx",
        "knowledge\\1\\file.docx",
        "knowledge/1//file.docx",
        "knowledge/1/file\x00.docx",
    ]
    assert all(not is_valid_object_key(key) for key in invalid_keys)
