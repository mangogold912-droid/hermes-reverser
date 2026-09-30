from __future__ import annotations

from collections.abc import Sequence


def pair_round(entrant_ids: Sequence[int]) -> tuple[list[tuple[int, int]], int | None]:
    """Pair entrants in their displayed order; an odd final entrant receives a bye.

    The original queue order is preserved across rounds. For ten entrants this
    creates five opening matches, then a 5-player round with one bye, followed
    by a 3-player round with one bye, and a final: nine matches in total.
    """
    entrants = list(entrant_ids)
    pairs = [(entrants[index], entrants[index + 1]) for index in range(0, len(entrants) - 1, 2)]
    bye = entrants[-1] if len(entrants) % 2 else None
    return pairs, bye
