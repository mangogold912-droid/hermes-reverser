import asyncio
import tempfile
import unittest
from pathlib import Path

from brawl_bot.database import MultiGuildDatabase


class MultiGuildDatabaseTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db = MultiGuildDatabase(Path(self.temp_dir.name) / "multi-guild.sqlite3")
        await self.db.open()

    async def asyncTearDown(self):
        await self.db.close()
        self.temp_dir.cleanup()

    async def test_guild_data_and_auto_ban_flags_are_isolated(self):
        with self.db.guild_context(101):
            await self.db.set_server_channels(1001, 1002)
            await self.db.set_protected_account(9001, "#2YJPJ2Q0", "Target A")
            await self.db.register_tag(2001, "#8QJ0L0YQ", "Player A")
            await self.db.set_auto_ban_enabled(True)

        with self.db.guild_context(202):
            await self.db.set_server_channels(2001, 2002)
            await self.db.set_protected_account(9002, "#22PR2V0", "Target B")
            await self.db.register_tag(2001, "#P8YQJ0L", "Player B")
            await self.db.set_auto_ban_enabled(False)

        with self.db.guild_context(101):
            self.assertEqual(await self.db.get_server_channels(), (1001, 1002))
            self.assertTrue(await self.db.get_auto_ban_enabled())
            self.assertEqual((await self.db.get_registration(2001))["player_tag"], "#8QJ0L0YQ")
        with self.db.guild_context(202):
            self.assertEqual(await self.db.get_server_channels(), (2001, 2002))
            self.assertFalse(await self.db.get_auto_ban_enabled())
            self.assertEqual((await self.db.get_registration(2001))["player_tag"], "#P8YQJ0L")

        self.assertEqual(await self.db.get_configured_guild_ids(), [101, 202])

    async def test_concurrent_transactions_are_serialized_per_guild(self):
        tags = ("#2YJPJ2Q0", "#8QJ0L0YQ", "#22PR2V0", "#P8YQJ0L")
        with self.db.guild_context(505):
            results = await asyncio.gather(
                *(
                    self.db.register_tag(user_id, tag, f"Player {user_id}")
                    for user_id, tag in enumerate(tags, start=1)
                )
            )
            self.assertEqual(results, ["approved"] * len(tags))
            registrations = [await self.db.get_registration(user_id) for user_id in range(1, 5)]
            self.assertTrue(all(registrations))

    async def test_destination_join_can_be_qualified_by_any_configured_source_guild(self):
        for guild_id, target_id, destination_channel in ((101, 9001, 3001), (202, 9002, 3002)):
            with self.db.guild_context(guild_id):
                await self.db.set_server_channels(guild_id * 10, guild_id * 10 + 1)
                await self.db.set_protected_account(target_id, "#2YJPJ2Q0" if guild_id == 101 else "#22PR2V0", "Target")
                await self.db.set_winner_destination(303, destination_channel)
        self.assertEqual(await self.db.get_source_guild_ids_for_destination(303), [101, 202])
        self.assertEqual(await self.db.get_source_guild_ids_for_destination(404), [])


if __name__ == "__main__":
    unittest.main()
