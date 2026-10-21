"""The comparison bench, proved against itself before it is trusted on anything else.

The bench exists to answer one question during v12 — *does this page still look the same?* — by
reducing two renders to a skeleton, a text list and an attribute-copy list. Nothing in P0 can answer
it yet: the React app has placeholders where the pages will be. What can be shown is that the
machinery is sound: it reads the same page the same way twice, and it notices when the page really
changes.

Cross-implementation comparisons start in P1, when the first real page has a counterpart.
"""

from __future__ import annotations

import pytest

from role_matrix import compare

pytestmark = pytest.mark.ui


def open_page(browser, cfg, path: str):
    page = browser.plain()
    page.page.goto(cfg.server + path, wait_until="networkidle")
    # The app renders 加载中… until the session read comes back, so the first paint is not the page.
    page.page.wait_for_timeout(600)
    return page


def test_the_same_page_reads_the_same_way_twice(browser, cfg):
    page = open_page(browser, cfg, "/login")
    try:
        first = compare.capture(page.page)
        second = compare.capture(page.page)
    finally:
        page.close()

    assert first == second
    assert first.skeleton, "the bench read nothing at all — that would make every diff empty"


def test_a_changed_word_is_noticed(browser, cfg):
    page = open_page(browser, cfg, "/login")
    try:
        before = compare.capture(page.page)
        page.page.evaluate("() => { document.querySelector('p').append(' 改过的字') }")
        after = compare.capture(page.page)
    finally:
        page.close()

    differences = compare.compare(before, after)
    assert "text" in differences, differences
    assert "skeleton" not in differences, "a text change should not move the structure"


def test_a_changed_class_is_noticed(browser, cfg):
    page = open_page(browser, cfg, "/login")
    try:
        before = compare.capture(page.page)
        page.page.evaluate("() => { document.querySelector('p').classList.add('is-extra') }")
        after = compare.capture(page.page)
    finally:
        page.close()

    differences = compare.compare(before, after)
    assert "skeleton" in differences, differences
    assert "text" not in differences, "a class change should not move the words"


def test_a_changed_attribute_is_noticed(browser, cfg):
    page = open_page(browser, cfg, "/login")
    try:
        before = compare.capture(page.page)
        page.page.evaluate("() => { document.querySelector('p').setAttribute('hidden', '') }")
        after = compare.capture(page.page)
    finally:
        page.close()

    # `hidden` is in the skeleton on purpose: the stylesheet switches on it, and jsdom cannot see the
    # cascade that makes it matter — which is exactly why this bench exists.
    assert "skeleton" in compare.compare(before, after)
