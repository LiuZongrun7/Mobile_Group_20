import asyncio
from contextlib import closing
import hashlib
import importlib.util
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PATH = Path(__file__).resolve().parents[1] / "pydantic_chat_bridge.py"
spec = importlib.util.spec_from_file_location("pydantic_chat_bridge", PATH)
bridge = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = bridge
spec.loader.exec_module(bridge)


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / "history.sqlite"

    def request(self, text="你好\n保留 42"):
        return {"kind": "request", "parts": [{"part_kind": "user-prompt", "content": text, "timestamp": "2026-10-09T16:00:00+08:00"}]}

    def response(self):
        return {"kind": "response", "timestamp": "2026-10-09T08:00:01Z", "parts": [{"part_kind": "text", "content": "42"}]}

    def database(self, batches):
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("CREATE TABLE messages (id INT PRIMARY KEY, message_list TEXT)")
            for batch in batches:
                db.execute("INSERT INTO messages(message_list) VALUES (?)", (json.dumps(batch),))

    def test_conversion_reads_real_sqlite_and_preserves_text_order(self):
        self.database([[self.request(), self.response()], [self.request("Next")]])
        rows = [json.loads(row) for row in asyncio.run(bridge.convert(self.path)).splitlines()]
        self.assertEqual([r["role"] for r in rows], ["user", "model", "user"])
        self.assertEqual(rows[0]["content"], "你好\n保留 42")
        self.assertEqual(rows[2]["content"], "Next")
        self.assertNotIn("usage", rows[1])

    def test_input_bytes_and_schema_are_never_changed(self):
        self.database([[self.request(), self.response()]])
        before = hashlib.sha256(self.path.read_bytes()).digest()
        asyncio.run(bridge.convert(self.path))
        self.assertEqual(before, hashlib.sha256(self.path.read_bytes()).digest())

    def test_connection_and_queries_keep_sqlite_thread_affinity(self):
        self.database([[self.request()]])
        async def read():
            async with bridge.Database.connect(self.path) as db:
                for _ in range(3):
                    self.assertEqual(len(await db.get_messages()), 1)
        asyncio.run(read())

    def test_read_only_connection_cannot_delete_history(self):
        self.database([[self.request()]])
        async def read():
            async with bridge.Database.connect(self.path) as db:
                with self.assertRaises(sqlite3.OperationalError):
                    await db._asyncify(db._execute, "DELETE FROM messages")
        asyncio.run(read())

    def test_all_response_text_parts_are_kept(self):
        message = self.response()
        message["parts"].append({"part_kind": "text", "content": " units"})
        self.assertEqual(bridge.to_chat_message(message)["content"], "42 units")

    def test_tools_system_and_images_are_not_silently_omitted(self):
        for part in [{"part_kind": "tool-call", "content": "private"}, {"part_kind": "system-prompt", "content": "private"}]:
            with self.assertRaises(bridge.UnsupportedHistory):
                bridge.to_chat_message({"kind": "request", "parts": [part]})
        message = self.request()
        message["parts"][0]["content"] = [{"image": "private"}]
        with self.assertRaises(bridge.UnsupportedHistory):
            bridge.to_chat_message(message)

    def test_invalid_time_or_json_fails_without_source_leak(self):
        message = self.request(); message["parts"][0]["timestamp"] = "SECRET_TIME"
        with self.assertRaises(bridge.UnsupportedHistory) as error:
            bridge.to_chat_message(message)
        self.assertNotIn("SECRET", str(error.exception))
        self.database([[self.request()]])
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("INSERT INTO messages(message_list) VALUES ('SECRET_BAD_JSON')")
        with self.assertRaises(bridge.UnsupportedHistory) as error:
            asyncio.run(bridge.convert(self.path))
        self.assertNotIn("SECRET", str(error.exception))

    def test_unknown_schema_empty_source_and_naive_time_are_rejected(self):
        self.database([])
        with self.assertRaises(bridge.UnsupportedHistory):
            asyncio.run(bridge.convert(self.path))
        message = self.request(); message["parts"][0]["timestamp"] = "2026-10-09T08:00:00"
        with self.assertRaises(bridge.UnsupportedHistory):
            bridge.to_chat_message(message)

    def test_cli_creates_new_file_and_refuses_overwrite(self):
        self.database([[self.request(), self.response()]])
        out = Path(self.directory.name) / "chat.ndjson"
        cmd = [sys.executable, str(PATH), str(self.path), "--output", str(out)]
        first = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(first.returncode, 0, first.stderr)
        self.assertIn("2 text messages", first.stdout)
        original = out.read_bytes()
        self.assertNotEqual(subprocess.run(cmd, capture_output=True).returncode, 0)
        self.assertEqual(original, out.read_bytes())

    def test_bad_source_creates_no_partial_output(self):
        self.database([{"wrong": "shape"}])
        out = Path(self.directory.name) / "chat.ndjson"
        result = subprocess.run([sys.executable, str(PATH), str(self.path), "--output", str(out)], capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(out.exists())


if __name__ == "__main__":
    unittest.main()
