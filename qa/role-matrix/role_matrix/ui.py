"""Drive the real console once per rung in the Chrome already on this machine.

`channel="chrome"` reuses the installed browser: nothing is downloaded, and the rendering is the
one a person sees. The design harness (`design/scripts/`) drives the same binary the same way, so
the two agree about what "the page" is.

What the checks read: the version list's rows and filters, the "new version" affordance, and the
publish handoff — which pane it lands on, which bump is preselected, what the release field holds
and what the button says. They deliberately do not click publish; that is the human's action, and
doing it here would change what every later rung is looking at.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from .config import FOLDER, Account

# `output/` rather than the cache: a screenshot exists to be looked at by a person, and the cache
# is this project's private bookkeeping. Both are untracked.
SHOTS = FOLDER / "output"
VIEWPORT = {"width": 1440, "height": 1000}

LIST_SCRIPT = """
() => ({
  rows: document.querySelectorAll('ul.versions > li').length,
  filters: [...document.querySelectorAll('.version-filter')]
    .map((element) => element.textContent.replace(/\\s+/g, ' ').trim()),
  create: document.querySelector('.version-create') !== null,
  empty: document.querySelector('.version-empty')
    ? document.querySelector('.version-empty').textContent.trim()
    : null,
})
"""

HANDOFF_SCRIPT = """
() => {
  const missing = document.querySelector('#versionMissing');
  if (missing) {
    return {missing: true, text: missing.innerText.replace(/\\s+/g, ' ').trim()};
  }
  return {
    missing: false,
    pane: (document.querySelector('.vp-tab.is-current') || {}).textContent?.trim() || null,
    radios: [...document.querySelectorAll('input[name="vp-bump"]')].map((r) => [r.value, r.checked]),
    field: (document.querySelector('.confirm-input') || {}).value ?? null,
    publishButton:
      [...document.querySelectorAll('.button')]
        .map((b) => b.textContent.trim())
        .find((t) => t.startsWith('上线 @')) || null,
  };
}
"""


@dataclass
class Page:
    """One signed-in tab. Everything it reads is the rendered page, not the API behind it."""

    context: object
    page: object
    base: str

    def version_list(self, skill: str) -> dict:
        self.page.goto(f"{self.base}/skills/{skill}?tab=versions", wait_until="networkidle")
        self.page.wait_for_selector("#versionListWrap, ul.versions", timeout=20000)
        self.page.wait_for_timeout(600)
        return self.page.evaluate(LIST_SCRIPT)

    def handoff(self, skill: str, version: str, bump: str = "minor") -> dict:
        """The address `version open --sub actions --bump <bump>` produces (ADR 0074)."""
        address = (
            f"{self.base}/skills/{skill}?tab=versions&version={version}&sub=actions&bump={bump}"
        )
        self.page.goto(address, wait_until="networkidle")
        self.page.wait_for_selector("#versionDetail, #versionMissing", timeout=20000)
        self.page.wait_for_timeout(500)
        return self.page.evaluate(HANDOFF_SCRIPT)

    def screenshot(self, name: str) -> Path:
        SHOTS.mkdir(parents=True, exist_ok=True)
        path = SHOTS / f"{name}.png"
        self.page.screenshot(path=str(path), full_page=True)
        return path

    def close(self) -> None:
        self.context.close()


class Browser:
    """The process-wide Chrome. One browser, one fresh context per rung: no cookie carries over."""

    def __init__(self, base: str):
        from playwright.sync_api import sync_playwright

        self.base = base.rstrip("/")
        self._playwright = sync_playwright().start()
        self._browser = self._playwright.chromium.launch(channel="chrome", headless=True)

    def close(self) -> None:
        self._browser.close()
        self._playwright.stop()

    def __enter__(self) -> "Browser":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def plain(self) -> Page:
        """A fresh tab with no sign-in. For pages that answer a visitor, and for the comparison bench."""
        context = self._browser.new_context(viewport=VIEWPORT)
        return Page(context=context, page=context.new_page(), base=self.base)

    def open(self, account: Account) -> Page:
        context = self._browser.new_context(viewport=VIEWPORT)
        page = context.new_page()
        page.goto(self.base + "/login", wait_until="networkidle")
        page.fill("#phone", account.phone)
        page.fill('input[type="password"]', account.password)
        page.click('button[type="submit"]')
        page.wait_for_url(lambda url: "/login" not in url, timeout=20000)
        return Page(context=context, page=page, base=self.base)
