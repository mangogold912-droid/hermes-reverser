import os
import unittest
from unittest.mock import patch

from brawl_bot.config import load_settings


BASE_ENV = {
    "DISCORD_BOT_TOKEN": "test-discord-token",
    "BRAWL_STARS_API_TOKEN": "test-brawl-token",
    "DISCORD_GUILD_ID": "123456789012345678",
    "QUEUE_CHANNEL_ID": "223456789012345678",
}


class ConfigTests(unittest.TestCase):
    def load_from(self, overrides=None):
        env = dict(BASE_ENV)
        env.update(overrides or {})
        with patch("brawl_bot.config.load_dotenv"), patch.dict(os.environ, env, clear=True):
            return load_settings()

    def test_defaults_to_bounty_only_and_ten_second_poll(self):
        settings = self.load_from()
        self.assertEqual(settings.allowed_modes, ("bounty",))
        self.assertEqual(settings.match_poll_seconds, 10)
        self.assertEqual(settings.event_size, 10)
        self.assertIsNone(settings.protected_discord_id)
        self.assertIsNone(settings.protected_brawl_tag)

    def test_rejects_non_bounty_mode(self):
        with self.assertRaisesRegex(ValueError, "locked to Bounty"):
            self.load_from({"ALLOWED_MODES": "duels"})

    def test_protected_discord_user_and_brawl_tag_must_be_configured_together(self):
        with self.assertRaisesRegex(ValueError, "Set both PROTECTED_DISCORD_ID"):
            self.load_from({"PROTECTED_DISCORD_ID": "323456789012345678"})
        settings = self.load_from(
            {
                "PROTECTED_DISCORD_ID": "323456789012345678",
                "PROTECTED_BRAWL_TAG": "2yjpJ2q0",
            }
        )
        self.assertEqual(settings.protected_brawl_tag, "#2YJPJ2Q0")

    def test_destination_server_and_invite_channel_must_be_configured_together(self):
        with self.assertRaisesRegex(ValueError, "WINNER_INVITE_CHANNEL_ID is required"):
            self.load_from({"WINNER_GUILD_ID": "423456789012345678"})
        with self.assertRaisesRegex(ValueError, "WINNER_GUILD_ID is required"):
            self.load_from({"WINNER_INVITE_CHANNEL_ID": "523456789012345678"})
        with self.assertRaisesRegex(ValueError, "different server"):
            self.load_from(
                {
                    "WINNER_GUILD_ID": BASE_ENV["DISCORD_GUILD_ID"],
                    "WINNER_INVITE_CHANNEL_ID": "523456789012345678",
                }
            )


if __name__ == "__main__":
    unittest.main()
