import unittest

from brawl_bot.brawl_api import normalize_tag


class TagValidationTests(unittest.TestCase):
    def test_tag_is_canonicalized(self):
        self.assertEqual(normalize_tag("#2yjpJ2q0"), "#2YJPJ2Q0")
        self.assertEqual(normalize_tag("8QJ0L0YQ"), "#8QJ0L0YQ")

    def test_invalid_tag_characters_are_rejected(self):
        with self.assertRaises(ValueError):
            normalize_tag("#2YJPJ2Q1")

    def test_empty_or_short_tags_are_rejected(self):
        for value in ("", "#", "#2Y"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                normalize_tag(value)


if __name__ == "__main__":
    unittest.main()
