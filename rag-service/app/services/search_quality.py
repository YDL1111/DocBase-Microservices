"""Search-only reranking of authorized semantic candidates; no external model calls."""
import re


def original_text(text, metadata):
    title = str(metadata.get("document_title") or "").strip()
    heading = str(metadata.get("heading_path") or "").strip()
    prefix = ([f"文档：{title}"] if title else [])
    if heading and heading != title:
        prefix.append(f"章节：{heading}")
    marker = "\n".join(prefix) + "\n\n"
    return text[len(marker):] if prefix and text.startswith(marker) else text


def terms(text):
    text = text.casefold()
    result = set(re.findall(r"[a-z0-9_]+", text))
    for run in re.findall(r"[\u4e00-\u9fff]+", text):
        result.update(run[i:i+2] for i in range(len(run)-1))
    return result


def rank_search(candidates, query, limit, minimum):
    query_terms = terms(query)
    ranked = []
    seen = set()
    for candidate in candidates:
        if candidate.relevance_score < minimum:
            continue
        metadata = candidate.document.metadata
        text = original_text(candidate.document.page_content, metadata)
        # Title prefixes must not make empty table separators look meaningful.
        if not any(char.isalnum() for char in text):
            continue
        normalized = re.sub(r"\s+", " ", text).strip()
        identity = (metadata.get("document_id"), normalized)
        overlap = len(query_terms & terms(text)) / max(len(query_terms), 1)
        heading_overlap = len(query_terms & terms(str(metadata.get("heading_path") or ""))) / max(len(query_terms), 1)
        score = candidate.relevance_score + 0.15 * overlap + 0.05 * heading_overlap
        ranked.append((score, str(metadata.get("chunk_id", "")), identity, candidate))
    result = []
    for _, _, identity, candidate in sorted(ranked, key=lambda item: (-item[0], item[1])):
        if identity in seen:
            continue
        seen.add(identity)
        result.append(candidate)
        if len(result) >= limit:
            break
    return result if limit > 0 else []
