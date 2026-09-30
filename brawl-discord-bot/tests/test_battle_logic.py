import unittest
from datetime import datetime, timezone

from brawl_bot.battle_logic import evaluate_pair_logs


TAG_A = "#2YJPJ2Q0"
TAG_B = "#8QJ0L0YQ"
STARTED_AT = datetime(2026, 9, 30, 9, 0, tzinfo=timezone.utc)


def battle(result: str, *, mode: str = "bounty", battle_time: str = "20260930T090100.000Z", same_team: bool = False):
    teams = (
        [[{"tag": TAG_A, "name": "Alpha"}, {"tag": TAG_B, "name": "Bravo"}], [{"tag": "#22", "name": "Other"}]]
        if same_team
        else [[{"tag": TAG_A, "name": "Alpha"}], [{"tag": TAG_B, "name": "Bravo"}]]
    )
    return {
        "battleTime": battle_time,
        "event": {"id": 1001, "mode": mode, "map": "Test Map"},
        "battle": {"mode": mode, "type": "friendly", "result": result, "teams": teams},
    }


class BattleLogicTests(unittest.TestCase):
    def evaluate(self, a, b, **kwargs):
        return evaluate_pair_logs(
            TAG_A,
            TAG_B,
            [a],
            [b],
            started_at=STARTED_AT,
            allowed_modes=("bounty",),
            **kwargs,
        )

    def test_opposite_results_identify_winner(self):
        decision = self.evaluate(battle("victory"), battle("defeat"))
        self.assertIsNotNone(decision)
        self.assertEqual(decision.kind, "decisive")
        self.assertEqual(decision.winner_tag, TAG_A)
        self.assertEqual(decision.loser_tag, TAG_B)

    def test_draw_never_selects_a_loser(self):
        decision = self.evaluate(battle("draw"), battle("draw"))
        self.assertIsNotNone(decision)
        self.assertEqual(decision.kind, "draw")
        self.assertIsNone(decision.loser_tag)

    def test_wrong_mode_is_ignored(self):
        self.assertIsNone(self.evaluate(battle("victory", mode="brawlBall"), battle("defeat", mode="brawlBall")))

    def test_same_team_is_ignored(self):
        self.assertIsNone(self.evaluate(battle("victory", same_team=True), battle("defeat", same_team=True)))

    def test_regular_three_vs_three_bounty_is_not_treated_as_the_1v1(self):
        log_a = battle("victory")
        log_b = battle("defeat")
        log_a["battle"]["teams"][0].extend([{"tag": "#P0Q2Y8J"}, {"tag": "#L2C0J9V"}])
        log_b["battle"]["teams"][0].extend([{"tag": "#P0Q2Y8J"}, {"tag": "#L2C0J9V"}])
        self.assertIsNone(self.evaluate(log_a, log_b))

    def test_battle_before_round_start_is_ignored(self):
        old = "20260930T085959.000Z"
        self.assertIsNone(self.evaluate(battle("victory", battle_time=old), battle("defeat", battle_time=old)))

    def test_inconsistent_results_are_not_guessed(self):
        self.assertIsNone(self.evaluate(battle("victory"), battle("victory")))

    def test_both_player_logs_must_contain_the_same_battle(self):
        self.assertIsNone(
            evaluate_pair_logs(
                TAG_A,
                TAG_B,
                [battle("victory")],
                [],
                started_at=STARTED_AT,
                allowed_modes=("bounty",),
            )
        )

    def test_previously_ignored_draw_is_not_reprocessed(self):
        first = self.evaluate(battle("draw"), battle("draw"))
        self.assertIsNotNone(first)
        decision = evaluate_pair_logs(
            TAG_A,
            TAG_B,
            [battle("draw"), battle("victory", battle_time="20260930T090200.000Z")],
            [battle("draw"), battle("defeat", battle_time="20260930T090200.000Z")],
            started_at=STARTED_AT,
            allowed_modes=("bounty",),
            ignored_battle_keys={first.battle_key},
        )
        self.assertIsNotNone(decision)
        self.assertEqual(decision.kind, "decisive")
        self.assertEqual(decision.winner_tag, TAG_A)


if __name__ == "__main__":
    unittest.main()
