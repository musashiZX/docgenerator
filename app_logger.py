"""Shared file logger for the doc-generator app.

Writes to `logs/app.log` (rotating, 1 MB × 5 files) so you can trace every
turn the user takes: model selected, user prompt, tool calls + truncated
results, saves, checklist runs, and errors.
"""

from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOG_DIR = Path(__file__).parent / "logs"
LOG_FILE = LOG_DIR / "app.log"

_LOGGER_NAME = "docgen"
_initialized = False


def get_logger() -> logging.Logger:
    """Return the project logger. Idempotent — safe to call repeatedly."""
    global _initialized
    logger = logging.getLogger(_LOGGER_NAME)
    if _initialized:
        return logger

    LOG_DIR.mkdir(exist_ok=True)
    logger.setLevel(logging.INFO)

    handler = RotatingFileHandler(
        LOG_FILE, maxBytes=1_000_000, backupCount=5, encoding="utf-8"
    )
    handler.setFormatter(
        logging.Formatter(
            "%(asctime)s | %(levelname)-7s | %(message)s",
            datefmt="%Y-%m-%d %H:%M:%S",
        )
    )
    logger.addHandler(handler)
    logger.propagate = False
    _initialized = True
    logger.info("=== Logger initialized — log file: %s ===", LOG_FILE)
    return logger


def truncate(s: str, n: int = 200) -> str:
    s = str(s)
    return s if len(s) <= n else s[: n - 3] + "..."
