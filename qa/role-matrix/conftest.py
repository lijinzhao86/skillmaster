"""Shared fixtures: settings, the seeded fixture, one credential per rung, the built CLI.

`--ui` decides whether the console suites run. They drive a real Chrome, so they are off by
default — the same cadence the design harness uses: the default run is the cheap one.
"""

from __future__ import annotations

import pytest

from role_matrix import cli, config, roles, state


def pytest_addoption(parser):
    parser.addoption(
        "--ui",
        action="store_true",
        default=False,
        help="also drive the console in the system Chrome",
    )


def pytest_collection_modifyitems(config, items):
    if config.getoption("--ui"):
        return
    skip = pytest.mark.skip(reason="console checks drive Chrome; run with --ui")
    for item in items:
        if "ui" in item.keywords:
            item.add_marker(skip)


@pytest.fixture(scope="session")
def cfg():
    return config.load()


@pytest.fixture(scope="session")
def fixture_state(cfg):
    """The six states `seed` built, with the names from `.env`."""
    return state.load()


@pytest.fixture(scope="session")
def tokens(cfg):
    return roles.ensure(cfg)


@pytest.fixture(scope="session")
def runner(cfg, tokens):
    return cli.Runner(cfg, cli.build(cfg), tokens)


@pytest.fixture(scope="session")
def browser(cfg):
    pytest.importorskip("playwright.sync_api", reason="playwright is not installed")
    from role_matrix.ui import Browser

    try:
        instance = Browser(cfg.server)
    except Exception as failure:  # a missing Chrome is an environment fact, not a failure
        pytest.skip(f"Playwright could not launch system Chrome: {failure}")
    yield instance
    instance.close()
