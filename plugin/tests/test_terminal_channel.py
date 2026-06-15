import asyncio
import unittest
from unittest.mock import AsyncMock

from plugin.relay.channels.terminal import (
    TerminalHandler,
    _Session,
    _client_session_name,
    _parse_tmux_session_line,
    _tmux_session_name,
)


class TerminalChannelTests(unittest.TestCase):
    def test_tmux_session_name_is_prefixed_and_safe(self):
        self.assertEqual(_tmux_session_name("default"), "hermes-default")
        self.assertEqual(_tmux_session_name("my session:1.2"), "hermes-my_session_1_2")
        self.assertEqual(_tmux_session_name(" .: "), "hermes-default")

    def test_client_session_name_only_accepts_hermes_prefix(self):
        self.assertEqual(_client_session_name("hermes-default"), "default")
        self.assertEqual(_client_session_name("hermes-"), "default")
        self.assertIsNone(_client_session_name("work"))

    def test_parse_tmux_session_line(self):
        parsed = _parse_tmux_session_line("hermes-default\t1\t2\t1779110000")
        self.assertEqual(
            parsed,
            {
                "name": "default",
                "tmux_name": "hermes-default",
                "attached": 1,
                "windows": 2,
                "created_at": 1779110000,
            },
        )
        self.assertIsNone(_parse_tmux_session_line("personal\t0\t1\t1779110000"))
        self.assertIsNone(_parse_tmux_session_line("hermes-default\tbad"))

    def test_terminal_summaries_merge_global_tmux_and_live_client_sessions(self):
        async def run():
            handler = TerminalHandler(default_shell="/bin/bash", force_tmux=True)
            ws = object()
            live = _Session(ws, master_fd=1, pid=1234, name="default", shell="/bin/bash")
            handler._sessions = {ws: {"default": live}}
            handler._list_tmux_sessions = AsyncMock(
                return_value=[
                    {
                        "name": "default",
                        "tmux_name": "hermes-default",
                        "attached": 1,
                        "windows": 1,
                        "created_at": 1779110000,
                    },
                    {
                        "name": "build",
                        "tmux_name": "hermes-build",
                        "attached": 0,
                        "windows": 1,
                        "created_at": 1779110100,
                    },
                ]
            )

            return await handler._terminal_session_summaries(ws)

        summaries = asyncio.run(run())
        by_name = {item["name"]: item for item in summaries}
        self.assertTrue(by_name["default"]["live"])
        self.assertTrue(by_name["default"]["owned_by_client"])
        self.assertEqual(by_name["default"]["pid"], 1234)
        self.assertFalse(by_name["build"]["live"])
        self.assertFalse(by_name["build"]["owned_by_client"])
        self.assertEqual(by_name["build"]["shell"], "/bin/bash")


if __name__ == "__main__":
    unittest.main()
