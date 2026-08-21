"""Format-aware cleanup before structural chunking."""
import html
import re
import unicodedata
from collections import Counter
from html.parser import HTMLParser

from app.services.document_block import ParsedBlock


class _MarkdownHTMLParser(HTMLParser):
    SKIPPED_TAGS = {"script", "style", "noscript", "nav", "svg", "canvas"}
    BLOCK_TAGS = {"p", "div", "section", "article", "main", "aside", "blockquote", "tr"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []
        self.skip_depth = 0

    def handle_starttag(self, tag: str, attrs) -> None:
        tag = tag.lower()
        if tag in self.SKIPPED_TAGS:
            self.skip_depth += 1
            return
        if self.skip_depth:
            return
        if re.fullmatch(r"h[1-6]", tag):
            self.parts.append("\n\n" + "#" * int(tag[1]) + " ")
        elif tag == "li":
            self.parts.append("\n- ")
        elif tag in self.BLOCK_TAGS or tag == "br":
            self.parts.append("\n")
        elif tag in {"td", "th"}:
            self.parts.append(" | ")

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in self.SKIPPED_TAGS:
            self.skip_depth = max(0, self.skip_depth - 1)
            return
        if not self.skip_depth and (tag in self.BLOCK_TAGS or re.fullmatch(r"h[1-6]", tag)):
            self.parts.append("\n")

    def handle_data(self, data: str) -> None:
        if not self.skip_depth and data:
            self.parts.append(data)

    def markdown(self) -> str:
        return "".join(self.parts)


class DocumentCleaner:
    """Remove format-specific noise without destroying retrieval-significant syntax."""

    _ZERO_WIDTH = re.compile("[\u200b-\u200f\u2060\ufeff]")
    _CONTROL = re.compile("[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")

    def clean_source(self, text: str, extension: str) -> str:
        extension = extension.lower()
        if extension in {"html", "htm"}:
            parser = _MarkdownHTMLParser()
            parser.feed(text)
            text = parser.markdown()
        elif extension == "xml":
            # Strip markup only after removing executable/template sections. XML field
            # names remain represented by whitespace boundaries rather than concatenating values.
            text = re.sub(r"<!--.*?-->", " ", text, flags=re.DOTALL)
            text = re.sub(r"<\?.*?\?>|<!\[CDATA\[(.*?)\]\]>", r" \1 ", text, flags=re.DOTALL)
            text = re.sub(r"<[^>]+>", "\n", text)
            text = html.unescape(text)
        return self.clean_text(text)

    def clean_blocks(self, blocks: list[ParsedBlock], file_type: str) -> list[ParsedBlock]:
        repeated_edge_lines = self._repeated_pdf_edge_lines(blocks) if file_type == "pdf" else set()
        cleaned: list[ParsedBlock] = []
        for block in blocks:
            text = block.page_content
            if repeated_edge_lines and block.block_type == "page":
                lines = text.splitlines()
                while lines and self.clean_text(lines[0]) in repeated_edge_lines:
                    lines.pop(0)
                while lines and self.clean_text(lines[-1]) in repeated_edge_lines:
                    lines.pop()
                text = "\n".join(lines)
            text = self.clean_text(text)
            if text:
                block.page_content = text
                cleaned.append(block)
        return cleaned

    @classmethod
    def clean_text(cls, text: str) -> str:
        text = unicodedata.normalize("NFKC", text or "")
        text = cls._ZERO_WIDTH.sub("", text)
        text = cls._CONTROL.sub(" ", text)
        text = text.replace("\r\n", "\n").replace("\r", "\n")
        text = "\n".join(re.sub(r"[ \t]+", " ", line).strip() for line in text.split("\n"))
        text = re.sub(r"\n{3,}", "\n\n", text)
        return text.strip()

    @classmethod
    def _repeated_pdf_edge_lines(cls, blocks: list[ParsedBlock]) -> set[str]:
        pages = [block for block in blocks if block.block_type == "page"]
        if len(pages) < 3:
            return set()
        edges: Counter[str] = Counter()
        for page in pages:
            lines = [cls.clean_text(line) for line in page.page_content.splitlines() if cls.clean_text(line)]
            if lines:
                edges.update(set(lines[:1] + lines[-1:]))
        threshold = max(3, (len(pages) * 2 + 2) // 3)
        return {line for line, count in edges.items() if count >= threshold and len(line) <= 120}


document_cleaner = DocumentCleaner()
