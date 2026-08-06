"""
Document parsing service - migrated from old project.
"""
import os
from pathlib import Path
from typing import List, Tuple

from langchain_core.documents import Document

from app.core.config import settings
from app.core.logging import get_logger

logger = get_logger(__name__)


class DocumentParser:
    """Parse various document formats into LangChain Documents."""

    SUPPORTED_TYPES = {
        "pdf": ["pdf"],
        "word": ["docx"],
        "excel": ["xlsx"],
        "pptx": ["pptx"],
        "text": ["txt", "md", "csv", "json", "xml", "html", "htm"],
    }

    def get_file_type(self, filename: str) -> str:
        ext = Path(filename).suffix.lower().lstrip(".")
        for file_type, exts in self.SUPPORTED_TYPES.items():
            if ext in exts:
                return file_type
        return "unknown"

    def parse(self, file_path: str, filename: str = None) -> Tuple[List[Document], dict]:
        """Parse a file and return documents with metadata."""
        filename = filename or os.path.basename(file_path)
        ext = Path(filename).suffix.lower().lstrip(".")
        file_type = self.get_file_type(filename)

        logger.info(f"Parsing file: {filename} (type={file_type})")

        if file_type == "pdf":
            docs = self._parse_pdf(file_path)
        elif file_type == "word":
            docs = self._parse_word(file_path)
        elif file_type == "excel":
            docs = self._parse_excel(file_path)
        elif file_type == "pptx":
            docs = self._parse_pptx(file_path)
        elif file_type == "text":
            docs = self._parse_text(file_path)
        else:
            raise ValueError(f"Unsupported file type: {ext}")

        total_chars = sum(len(doc.page_content) for doc in docs)
        meta = {
            "filename": filename,
            "file_type": file_type,
            "ext": ext,
            "page_count": len(docs),
            "total_chars": total_chars,
        }
        logger.info(f"Parsed {len(docs)} pages, {total_chars} chars from {filename}")
        return docs, meta

    def _parse_pdf(self, file_path: str) -> List[Document]:
        import fitz  # PyMuPDF

        docs = []
        pdf_doc = fitz.open(file_path)
        try:
            for index, page in enumerate(pdf_doc):
                text = (page.get_text() or "").strip()
                if text:
                    docs.append(
                        Document(
                            page_content=text,
                            metadata={"source": file_path, "page": index + 1},
                        )
                    )
        finally:
            pdf_doc.close()

        return docs if docs else [Document(page_content="", metadata={"source": file_path})]

    def _parse_word(self, file_path: str) -> List[Document]:
        from docx import Document as DocxDocument

        doc = DocxDocument(file_path)
        text = "\n".join([p.text for p in doc.paragraphs if p.text.strip()])
        return [Document(page_content=text, metadata={"source": file_path})] if text else []

    def _parse_excel(self, file_path: str) -> List[Document]:
        import openpyxl

        wb = openpyxl.load_workbook(file_path, read_only=True, data_only=True)
        docs = []
        try:
            for sheet_name in wb.sheetnames:
                sheet = wb[sheet_name]
                rows = []
                for row in sheet.iter_rows(values_only=True):
                    row_text = " | ".join(str(cell) for cell in row if cell is not None)
                    if row_text.strip():
                        rows.append(row_text)
                if rows:
                    text = "\n".join(rows)
                    docs.append(Document(
                        page_content=text,
                        metadata={"source": file_path, "sheet": sheet_name}
                    ))
        finally:
            wb.close()
        return docs

    def _parse_pptx(self, file_path: str) -> List[Document]:
        from pptx import Presentation

        prs = Presentation(file_path)
        docs = []
        for slide_num, slide in enumerate(prs.slides, 1):
            texts = []
            for shape in slide.shapes:
                if hasattr(shape, "text") and shape.text.strip():
                    texts.append(shape.text)
            if texts:
                docs.append(Document(
                    page_content="\n".join(texts),
                    metadata={"source": file_path, "slide": slide_num}
                ))
        return docs

    def _parse_text(self, file_path: str) -> List[Document]:
        import chardet

        with open(file_path, "rb") as f:
            raw = f.read()

        encoding = chardet.detect(raw).get("encoding", "utf-8") or "utf-8"
        text = raw.decode(encoding, errors="replace").strip()

        return [Document(page_content=text, metadata={"source": file_path})] if text else []
