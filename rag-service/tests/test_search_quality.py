from types import SimpleNamespace as NS
from app.services.search_quality import rank_search


def candidate(text, score=0.7, **metadata):
    return NS(relevance_score=score, document=NS(page_content=text, metadata=metadata))


def test_filters_separator_after_embedding_prefix():
    noise = candidate("文档：指南\n章节：安装\n\n| --- |", document_title="指南", heading_path="安装")
    useful = candidate("安装步骤：运行服务")
    assert rank_search([noise, useful], "安装", 10, .35) == [useful]


def test_direct_match_beats_nearby_generic_passage():
    generic = candidate("本章概述项目背景", .75)
    direct = candidate("MLFQ 时间片耗尽时降低优先级", .72)
    assert rank_search([generic, direct], "MLFQ 时间片", 2, .35)[0] is direct


def test_threshold_dedup_and_limit():
    first = candidate("实际内容", .8, document_id=1)
    duplicate = candidate("实际内容", .7, document_id=1)
    low = candidate("实际内容", .1, document_id=2)
    assert rank_search([first, duplicate, low], "内容", 10, .35) == [first]
    assert rank_search([first], "内容", 0, .35) == []
