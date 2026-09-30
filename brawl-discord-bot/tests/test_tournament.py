import unittest

from brawl_bot.tournament import pair_round


class PairRoundTests(unittest.TestCase):
    def test_ten_players_are_paired_in_queue_order(self):
        pairs, bye = pair_round(list(range(1, 11)))
        self.assertEqual(pairs, [(1, 2), (3, 4), (5, 6), (7, 8), (9, 10)])
        self.assertIsNone(bye)

    def test_odd_round_gives_last_player_a_bye(self):
        pairs, bye = pair_round([101, 202, 303, 404, 505])
        self.assertEqual(pairs, [(101, 202), (303, 404)])
        self.assertEqual(bye, 505)

    def test_two_players_have_one_final_match(self):
        self.assertEqual(pair_round([7, 8]), ([(7, 8)], None))

    def test_one_player_is_a_bye(self):
        self.assertEqual(pair_round([42]), ([], 42))


if __name__ == "__main__":
    unittest.main()
