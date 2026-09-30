import asyncio
import os
import unittest
from unittest.mock import patch

import discord

from brawl_bot.config import load_settings
from bot import BrawlChallengeBot


class PublicCommandRegistrationTests(unittest.TestCase):
    def test_commands_are_global_and_limited_to_server_installation(self):
        env = {
            "DISCORD_BOT_TOKEN": "test-discord-token",
            "BRAWL_STARS_API_TOKEN": "test-brawl-token",
            "DISCORD_GUILD_ID": "",
            "QUEUE_CHANNEL_ID": "",
            "RESULT_CHANNEL_ID": "",
            "DATABASE_PATH": ":memory:",
        }
        with patch("brawl_bot.config.load_dotenv"), patch.dict(os.environ, env, clear=True):
            settings = load_settings()
        bot = BrawlChallengeBot(settings)
        try:
            commands = bot.tree.get_commands()
            names = {command.name for command in commands}
            self.assertEqual(len(commands), 17)
            self.assertIn("configure_servers", names)
            self.assertIn("set_auto_ban", names)
            self.assertEqual(bot.tree.get_commands(guild=discord.Object(id=123456789)), [])
            self.assertTrue(bot.tree.allowed_installs.guild)
            self.assertFalse(bot.tree.allowed_installs.user)
            self.assertTrue(bot.tree.allowed_contexts.guild)
            self.assertFalse(bot.tree.allowed_contexts.dm_channel)
            self.assertFalse(bot.tree.allowed_contexts.private_channel)
        finally:
            asyncio.run(bot.db.close())


if __name__ == "__main__":
    unittest.main()
