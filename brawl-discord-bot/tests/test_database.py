import sqlite3
import tempfile
import unittest
from pathlib import Path

from brawl_bot.database import Database


PROTECTED_TAG = "#8L0Q2YJ"

TAGS = [
    "#2YJPJ2Q0",
    "#8QJ0L0YQ",
    "#22PR2V0",
    "#P8YQJ0L",
    "#Q0C8LYP",
    "#R2J08QY",
    "#UQY2P8C",
    "#LQ0YJ2P",
    "#J2V0Q8Y",
    "#YQ8P2J0",
]


class DatabaseSequentialChallengeTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.db = Database(Path(self.temp_dir.name) / "test.sqlite3")
        self.db.bind_guild(123456789)
        await self.db.open(default_protected_user_id=999, default_protected_brawl_tag=PROTECTED_TAG)

    async def asyncTearDown(self):
        await self.db.close()
        self.temp_dir.cleanup()

    async def create_full_event(self):
        event_id = await self.db.create_event(
            event_key="test-event",
            event_date="2026-09-30",
            queue_channel_id=1,
            result_channel_id=1,
        )
        for user_id, tag in enumerate(TAGS, start=1):
            await self.db.register_tag(user_id, tag, f"Player {user_id}")
        for user_id in range(1, 11):
            result = await self.db.join_queue(event_id, user_id, 10)
        self.assertTrue(result["started"])
        return event_id

    async def test_queue_fills_ten_slots_and_starts_only_the_first_challenge(self):
        event_id = await self.create_full_event()
        event = await self.db.get_event(event_id)
        players = await self.db.get_event_players(event_id)
        matches = await self.db.get_round_matches(event_id, 1)
        active_matches = await self.db.get_active_matches()
        self.assertEqual(event["status"], "active")
        self.assertEqual(event["current_round"], 1)
        self.assertEqual(len(players), 10)
        self.assertEqual(len(matches), 1)
        self.assertEqual(len(active_matches), 1)
        self.assertEqual((matches[0]["player1_user_id"], matches[0]["player2_user_id"]), (999, 1))
        self.assertEqual(matches[0]["slot_no"], 1)

    async def test_protected_target_is_not_counted_as_a_queue_entrant(self):
        event_id = await self.db.create_event(
            event_key="target-not-entrant",
            event_date="2026-09-30",
            queue_channel_id=1,
            result_channel_id=1,
        )
        with self.assertRaisesRegex(ValueError, "보호 대상은 대기열"):
            await self.db.join_queue(event_id, 999, 10)
        self.assertEqual(await self.db.get_event_players(event_id), [])

    async def test_all_ten_challengers_face_protected_target_sequentially(self):
        event_id = await self.create_full_event()
        progression = None
        for challenge_no, challenger_id in enumerate(range(1, 11), start=1):
            active_matches = await self.db.get_active_matches()
            self.assertEqual(len(active_matches), 1, "only the current challenger may have an active match")
            match = active_matches[0]
            self.assertEqual(match["slot_no"], challenge_no)
            self.assertEqual(match["player1_user_id"], 999)
            self.assertEqual(match["player2_user_id"], challenger_id)
            self.assertEqual(match["round_no"], 1)
            accepted = await self.db.mark_pending_ban(
                match["id"],
                winner_user_id=999,
                loser_user_id=challenger_id,
                battle_key_value=f"test-{match['id']}",
                result_kind="test",
            )
            self.assertTrue(accepted)
            progression = await self.db.complete_pending_match(
                match["id"],
                ban_status="dry_run",
                protected_loser=False,
                protected_user_id=999,
                protected_brawl_tag=PROTECTED_TAG,
            )
            if challenge_no < 10:
                self.assertEqual(progression["next_challenge_no"], challenge_no + 1)
                event = await self.db.get_event(event_id)
                self.assertEqual(event["current_round"], challenge_no + 1)
            else:
                self.assertTrue(progression["event_completed"])
                self.assertEqual(progression["matches_completed"], 10)

        event = await self.db.get_event(event_id)
        matches = await self.db.get_round_matches(event_id, 1)
        self.assertEqual(event["status"], "completed")
        self.assertEqual(len(matches), 10)
        self.assertEqual([row["slot_no"] for row in matches], list(range(1, 11)))
        self.assertEqual([row["player2_user_id"] for row in matches], list(range(1, 11)))
        self.assertTrue(all(row["player1_user_id"] == 999 for row in matches))

    async def test_protected_loser_qualifies_challenger_for_second_server(self):
        event_id = await self.create_full_event()
        match = (await self.db.get_active_matches())[0]
        self.assertEqual((match["player1_user_id"], match["player2_user_id"]), (999, 1))
        self.assertTrue(
            await self.db.mark_pending_ban(
                match["id"],
                winner_user_id=1,
                loser_user_id=999,
                battle_key_value="test-protected-win",
                result_kind="api",
            )
        )
        progression = await self.db.complete_pending_match(
            match["id"],
            ban_status="protected",
            protected_loser=True,
            protected_user_id=999,
            protected_brawl_tag=PROTECTED_TAG,
            protected_win_qualification=True,
        )
        self.assertEqual(progression["next_challenge_no"], 2)
        self.assertTrue(await self.db.is_qualified_winner(1, 999))
        self.assertFalse(await self.db.is_qualified_winner(999, 999))
        record = await self.db.get_qualified_winner(1, 999)
        self.assertEqual(record["invite_status"], "pending")
        self.assertEqual(record["protected_brawl_tag"], PROTECTED_TAG)


    async def test_fixed_protected_account_cannot_self_register_a_different_tag(self):
        await self.db.set_protected_account(10, TAGS[9], "Player 10")
        with self.assertRaisesRegex(ValueError, "고정 보호 대상"):
            await self.db.register_tag(10, TAGS[8], "Impostor tag")

    async def test_protected_identity_without_fixed_tag_match_does_not_qualify_winner(self):
        event_id = await self.create_full_event()
        match = (await self.db.get_active_matches())[0]
        await self.db.mark_pending_ban(
            match["id"],
            winner_user_id=1,
            loser_user_id=999,
            battle_key_value="test-protected-mismatch",
            result_kind="api",
        )
        await self.db.complete_pending_match(
            match["id"],
            ban_status="protected",
            protected_loser=True,
            protected_user_id=999,
            protected_brawl_tag=TAGS[0],
            protected_win_qualification=True,
        )
        self.assertFalse(await self.db.is_qualified_winner(1, 999))

    async def test_leaving_open_queue_resequences_remaining_players(self):
        event_id = await self.db.create_event(
            event_key="leave-test",
            event_date="2026-09-30",
            queue_channel_id=1,
            result_channel_id=1,
        )
        for user_id, tag in enumerate(TAGS[:3], start=1):
            await self.db.register_tag(user_id, tag, f"Player {user_id}")
            await self.db.join_queue(event_id, user_id, 10)

        remaining = await self.db.leave_queue(2)
        players = await self.db.get_event_players(event_id)
        self.assertEqual(remaining, 2)
        self.assertEqual([(row["user_id"], row["seed"]) for row in players], [(1, 1), (3, 2)])

    async def test_protected_user_id_is_seeded(self):
        self.assertEqual(await self.db.get_protected_user_id(), 999)

    async def test_tournament_ban_blocks_both_discord_id_and_player_tag_until_admin_unban(self):
        await self.db.add_tournament_ban(77, TAGS[0], event_id=900, match_id=901)
        self.assertIsNotNone(await self.db.get_tournament_ban(77))
        self.assertIsNotNone(await self.db.get_tournament_ban(88, TAGS[0]))
        with self.assertRaisesRegex(ValueError, "대회 영구 밴"):
            await self.db.register_tag(77, TAGS[1], "Banned Discord ID")
        with self.assertRaisesRegex(ValueError, "대회 영구 밴"):
            await self.db.register_tag(88, TAGS[0], "Alt account")
        cleared_tags = await self.db.clear_tournament_bans(77)
        self.assertEqual(cleared_tags, (TAGS[0],))
        self.assertIsNone(await self.db.get_tournament_ban(77))
        self.assertIsNone(await self.db.get_tournament_ban(88, TAGS[0]))
        await self.db.register_tag(88, TAGS[0], "Player 1")

    async def test_existing_registration_cannot_rejoin_after_tag_or_id_ban(self):
        event_id = await self.db.create_event(
            event_key="banned-rejoin",
            event_date="2026-09-30",
            queue_channel_id=1,
            result_channel_id=1,
        )
        await self.db.register_tag(77, TAGS[0], "Player 77")
        await self.db.add_tournament_ban(77, TAGS[0], event_id=900, match_id=901)
        with self.assertRaisesRegex(ValueError, "대회 영구 밴"):
            await self.db.join_queue(event_id, 77, 10)

    async def test_winner_destination_can_be_set_and_persisted(self):
        await self.db.set_winner_destination(987654321, 876543210)
        self.assertEqual(await self.db.get_winner_destination(), (987654321, 876543210))
        await self.db.close()
        await self.db.open()
        self.assertEqual(await self.db.get_winner_destination(), (987654321, 876543210))
        with self.assertRaisesRegex(ValueError, "메인 대회 서버"):
            await self.db.set_winner_destination(123456789, 876543210)

    async def test_duplicate_tag_cannot_be_linked_to_two_discord_users(self):
        await self.db.register_tag(1, TAGS[0], "Player 1")
        with self.assertRaises(ValueError):
            await self.db.register_tag(2, TAGS[0], "Player 2")

    async def test_old_active_bracket_event_is_cancelled_on_database_reopen(self):
        now = "2026-09-30T00:00:00+00:00"
        cursor = await self.db.conn.execute(
            "INSERT INTO events(guild_id, event_key, event_date, status, queue_channel_id, result_channel_id, created_at) "
            "VALUES (?, ?, ?, 'active', ?, ?, ?)",
            (123456789, "old-bracket", "2026-09-30", 1, 1, now),
        )
        event_id = int(cursor.lastrowid)
        await self.db.conn.executemany(
            "INSERT INTO event_players(event_id, user_id, player_tag, player_name, seed, status) "
            "VALUES (?, ?, ?, ?, ?, 'active')",
            [
                (event_id, 301, TAGS[0], "Old entrant 1", 1),
                (event_id, 302, TAGS[1], "Old entrant 2", 2),
            ],
        )
        match_cursor = await self.db.conn.execute(
            "INSERT INTO matches(event_id, round_no, slot_no, player1_user_id, player2_user_id, started_at, status) "
            "VALUES (?, 1, 1, 301, 302, ?, 'monitoring')",
            (event_id, now),
        )
        match_id = int(match_cursor.lastrowid)
        await self.db.conn.commit()

        await self.db.close()
        await self.db.open()
        event = await self.db.get_event(event_id)
        match = await self.db.get_match(match_id)
        self.assertEqual(event["status"], "cancelled")
        self.assertIsNone(event["event_key"])
        self.assertIsNone(await self.db.get_active_event())
        self.assertEqual(match["status"], "cancelled")

    async def test_legacy_database_gets_protected_tag_and_qualification_columns(self):
        legacy_path = Path(self.temp_dir.name) / "legacy.sqlite3"
        with sqlite3.connect(legacy_path) as conn:
            conn.execute("CREATE TABLE guild_settings (guild_id INTEGER PRIMARY KEY, protected_user_id INTEGER)")
            conn.execute("CREATE TABLE matches (id INTEGER PRIMARY KEY)")
            conn.execute(
                "CREATE TABLE protected_winners ("
                "source_guild_id INTEGER NOT NULL, user_id INTEGER NOT NULL, protected_user_id INTEGER NOT NULL, "
                "event_id INTEGER NOT NULL, match_id INTEGER NOT NULL, qualified_at TEXT NOT NULL, "
                "invite_status TEXT NOT NULL DEFAULT 'pending', invite_url TEXT, failure_reason TEXT, "
                "PRIMARY KEY (source_guild_id, user_id, protected_user_id))"
            )
            conn.execute(
                "INSERT INTO protected_winners(source_guild_id, user_id, protected_user_id, event_id, match_id, qualified_at) "
                "VALUES (1, 2, 3, 4, 5, '2026-09-30T00:00:00+00:00')"
            )
        legacy_db = Database(legacy_path)
        legacy_db.bind_guild(987654321)
        await legacy_db.open()
        try:
            settings_columns = await legacy_db.conn.execute_fetchall("PRAGMA table_info(guild_settings)")
            match_columns = await legacy_db.conn.execute_fetchall("PRAGMA table_info(matches)")
            self.assertIn("protected_brawl_tag", {row[1] for row in settings_columns})
            self.assertIn("winner_guild_id", {row[1] for row in settings_columns})
            self.assertIn("winner_invite_channel_id", {row[1] for row in settings_columns})
            self.assertIn("winner_qualified", {row[1] for row in match_columns})
            winner_columns = await legacy_db.conn.execute_fetchall("PRAGMA table_info(protected_winners)")
            self.assertIn("protected_brawl_tag", {row[1] for row in winner_columns})
            legacy_winner = await legacy_db.conn.execute_fetchall("SELECT invite_status, protected_brawl_tag FROM protected_winners")
            self.assertEqual(legacy_winner[0][0], "failed")
            self.assertIsNone(legacy_winner[0][1])
        finally:
            await legacy_db.close()


if __name__ == "__main__":
    unittest.main()
