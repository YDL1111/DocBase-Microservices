from app.services.document_block import ParsedBlock
from app.services.document_cleaner import DocumentCleaner


def test_html_cleanup_removes_executable_navigation_and_preserves_structure():
    source = """
    <nav>首页 产品中心</nav><script>alert('x')</script><style>.x{}</style>
    <h1>员工手册</h1><p>设备型号 A-100，效率 98%。</p><ul><li>安全</li><li>质量</li></ul>
    """

    cleaned = DocumentCleaner().clean_source(source, "html")

    assert "首页" not in cleaned and "alert" not in cleaned and ".x" not in cleaned
    assert "# 员工手册" in cleaned
    assert "- 安全" in cleaned and "- 质量" in cleaned
    assert "A-100" in cleaned and "98%" in cleaned


def test_unicode_and_control_cleanup_keeps_retrieval_significant_symbols():
    cleaned = DocumentCleaner.clean_text("ＡＢＣ\u200b\x00 | v1.2: 50%\r\n正文")
    assert cleaned == "ABC | v1.2: 50%\n正文"


def test_repeated_pdf_header_and_footer_are_removed():
    blocks = [
        ParsedBlock(f"公司内部资料\n第 {index} 页正文\n第 1 章", "page", metadata={"page": index})
        for index in range(1, 4)
    ]

    cleaned = DocumentCleaner().clean_blocks(blocks, "pdf")

    assert all("公司内部资料" not in block.page_content for block in cleaned)
    assert all("第 1 章" not in block.page_content for block in cleaned)
    assert all("正文" in block.page_content for block in cleaned)
