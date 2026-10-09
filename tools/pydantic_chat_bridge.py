"""Offline migration from the chosen Pydantic AI example's SQLite history to Android NDJSON.

Adapted Database.connect / _asyncify / _execute / get_messages and to_chat_message from
pydantic_ai_examples/chat_app.py at 36529f3a8ebc5a5675129fe6262223b9da0815ec.
Copyright (c) Pydantic Services Inc. 2024 to present. MIT; see third_party/pydantic-ai-chat/LICENSE.
ModelPilot changes: read-only input, no Logfire/runtime dependency, strict text-only validation,
rowid ordering for upstream NULL primary keys, size limits, cleanup and explicit output.
No API calls, API keys, provider session cookies or model billing data are required.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import partial
from pathlib import Path
from typing import Any, AsyncGenerator, Callable

MAX_BYTES = 32 * 1024 * 1024


class UnsupportedHistory(ValueError):
    """Safe error messages deliberately omit source content."""


def to_chat_message(message: dict[str, Any]) -> dict[str, str]:
    """Port upstream role/timestamp/content conversion; refuse partial tool/image exports."""
    if not isinstance(message, dict):
        raise UnsupportedHistory("A history message is not an object.")
    parts = message.get("parts")
    if not isinstance(parts, list) or not parts:
        raise UnsupportedHistory("A history message has no parts.")
    if message.get("kind") == "request":
        # Unlike the simple upstream first-part assumption, refuse system/tool/mixed requests.
        if len(parts) != 1 or not isinstance(parts[0], dict) or parts[0].get("part_kind") != "user-prompt":
            raise UnsupportedHistory("Only one text user-prompt part per request is supported.")
        role, content, timestamp = "user", parts[0].get("content"), parts[0].get("timestamp")
    elif message.get("kind") == "response":
        if any(not isinstance(p, dict) or p.get("part_kind") != "text" or not isinstance(p.get("content"), str) for p in parts):
            raise UnsupportedHistory("Tool, reasoning and non-text response parts require a full-history migration.")
        role, content, timestamp = "model", "".join(p["content"] for p in parts), message.get("timestamp")
    else:
        raise UnsupportedHistory("Unknown model-message kind.")
    if not isinstance(content, str) or not isinstance(timestamp, str):
        raise UnsupportedHistory("Text and ISO timestamp are required.")
    try:
        instant = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
        if instant.tzinfo is None or instant.utcoffset() is None or instant.timestamp() <= 0:
            raise ValueError()
        timestamp = instant.astimezone(timezone.utc).isoformat()
    except (ValueError, OverflowError):
        raise UnsupportedHistory("A message timestamp is invalid.") from None
    return {"role": role, "timestamp": timestamp, "content": content}


@dataclass
class Database:
    """Actual adaptation of the example's executor-backed SQLite Database module."""
    con: sqlite3.Connection
    _loop: asyncio.AbstractEventLoop
    _executor: ThreadPoolExecutor

    @classmethod
    @asynccontextmanager
    async def connect(cls, file: Path) -> AsyncGenerator[Database, None]:
        path = file.resolve(strict=True)
        loop = asyncio.get_running_loop()
        executor = ThreadPoolExecutor(max_workers=1)
        con = None
        try:
            # Connection and every query run on the same worker (SQLite thread affinity).
            con = await loop.run_in_executor(executor, cls._connect, path)
            yield cls(con, loop, executor)
        finally:
            if con is not None:
                await loop.run_in_executor(executor, con.close)
            executor.shutdown(wait=True)

    @staticmethod
    def _connect(file: Path) -> sqlite3.Connection:
        return sqlite3.connect(file.as_uri() + "?mode=ro", uri=True)

    async def _asyncify(self, func: Callable, *args: Any, **kwargs: Any) -> Any:
        return await self._loop.run_in_executor(self._executor, partial(func, **kwargs), *args)

    def _execute(self, sql: str) -> sqlite3.Cursor:
        return self.con.execute(sql)

    async def get_messages(self) -> list[dict[str, Any]]:
        # Upstream inserts omit id despite `id INT PRIMARY KEY`: rowid keeps insertion order.
        cursor = await self._asyncify(self._execute, "SELECT message_list FROM messages ORDER BY rowid")
        messages = []
        size = 0
        try:
            while True:
                rows = await self._asyncify(cursor.fetchmany, 64)
                if not rows:
                    break
                for row in rows:
                    payload = row[0]
                    if not isinstance(payload, (str, bytes)):
                        raise UnsupportedHistory("A SQLite message batch is not JSON text.")
                    size += len(payload if isinstance(payload, bytes) else payload.encode("utf-8"))
                    if size > MAX_BYTES:
                        raise UnsupportedHistory("History exceeds the 32 MiB interchange limit.")
                    try:
                        batch = json.loads(payload)
                    except (ValueError, UnicodeError):
                        raise UnsupportedHistory("A SQLite message batch contains invalid JSON.") from None
                    if not isinstance(batch, list):
                        raise UnsupportedHistory("A SQLite message batch must be an array.")
                    messages.extend(to_chat_message(m) for m in batch)
        finally:
            await self._asyncify(cursor.close)
        if not messages:
            raise UnsupportedHistory("The source history is empty.")
        return messages


async def convert(source: Path) -> bytes:
    async with Database.connect(source) as database:
        messages = await database.get_messages()
    result = ("\n".join(json.dumps(m, ensure_ascii=False, separators=(",", ":")) for m in messages) + "\n").encode("utf-8")
    if len(result) > MAX_BYTES:
        raise UnsupportedHistory("NDJSON exceeds the 32 MiB interchange limit.")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="The selected example's .chat_app_messages.sqlite")
    parser.add_argument("--output", type=Path, required=True, help="New .ndjson destination; existing files are never overwritten")
    args = parser.parse_args()
    try:
        if args.source.resolve() == args.output.resolve():
            raise UnsupportedHistory("Source and destination must be different files.")
        data = asyncio.run(convert(args.source))
        # Exclusive create: no overwrite or mutation of the original SQLite database.
        with args.output.open("xb") as output:
            output.write(data)
        print(f"Converted {len(data.splitlines())} text messages. No token/cost records were created.")
        return 0
    except (UnsupportedHistory, sqlite3.Error, OSError) as failure:
        # sqlite/OS exceptions can include paths; never print file contents or raw JSON errors.
        detail = str(failure) if isinstance(failure, UnsupportedHistory) else "Cannot read source or create a new output file."
        parser.exit(1, detail + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
