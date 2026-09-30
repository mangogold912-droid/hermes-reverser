from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Iterable

from .brawl_api import VALID_TAG_CHARACTERS


@dataclass(frozen=True)
class BattleDecision:
    kind: str  # "decisive" or "draw"
    battle_key: str
    battle_time: str
    mode: str
    map_name: str
    winner_tag: str | None = None
    loser_tag: str | None = None


def _canonical_tag(raw: Any) -> str | None:
    if not isinstance(raw, str):
        return None
    tag = raw.strip().upper()
    if tag.startswith("#"):
        tag = tag[1:]
    if not tag or any(character not in VALID_TAG_CHARACTERS for character in tag):
        return None
    return f"#{tag}"


def parse_battle_time(raw: Any) -> datetime | None:
    if not isinstance(raw, str) or not raw:
        return None
    value = raw.strip()
    for pattern in ("%Y%m%dT%H%M%S.%fZ", "%Y%m%dT%H%M%SZ"):
        try:
            return datetime.strptime(value, pattern).replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def _entry_parts(entry: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    event = entry.get("event")
    battle = entry.get("battle")
    return (
        event if isinstance(event, dict) else {},
        battle if isinstance(battle, dict) else {},
        entry,
    )


def _team_tags(teams: Any) -> tuple[tuple[str, ...], ...]:
    if not isinstance(teams, list):
        return ()
    normalized_teams: list[tuple[str, ...]] = []
    for team in teams:
        if not isinstance(team, list):
            return ()
        tags = tuple(sorted(tag for player in team if isinstance(player, dict) if (tag := _canonical_tag(player.get("tag")))))
        normalized_teams.append(tags)
    # Team order can differ between two players' log records. Canonicalize it.
    return tuple(sorted(normalized_teams))


def battle_key(entry: dict[str, Any]) -> str | None:
    """Stable key shared by both players' copies of one API battle record."""
    event, battle, raw = _entry_parts(entry)
    battle_time = raw.get("battleTime")
    if not isinstance(battle_time, str) or not battle_time:
        return None
    mode = battle.get("mode") or event.get("mode") or ""
    material = {
        "battleTime": battle_time,
        "eventId": event.get("id"),
        "eventMode": event.get("mode"),
        "map": event.get("map"),
        "mode": mode,
        "type": battle.get("type"),
        "teams": _team_tags(battle.get("teams")),
    }
    encoded = json.dumps(material, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _mode_for(entry: dict[str, Any]) -> str:
    event, battle, _ = _entry_parts(entry)
    value = battle.get("mode") or event.get("mode") or ""
    return str(value).strip().lower()


def _are_opponents(entry: dict[str, Any], tag_a: str, tag_b: str) -> bool:
    _, battle, _ = _entry_parts(entry)
    teams = battle.get("teams")
    # The configured contest is specifically a 1v1 played in Bounty. Refuse
    # ordinary 3v3 Bounty records even if the two registered tags are opponents.
    if (
        not isinstance(teams, list)
        or len(teams) != 2
        or any(not isinstance(team, list) or len(team) != 1 for team in teams)
    ):
        return False

    sides_a: list[int] = []
    sides_b: list[int] = []
    for index, team in enumerate(teams):
        if not isinstance(team, list):
            continue
        for player in team:
            if not isinstance(player, dict):
                continue
            player_tag = _canonical_tag(player.get("tag"))
            if player_tag == tag_a:
                sides_a.append(index)
            if player_tag == tag_b:
                sides_b.append(index)
    return len(sides_a) == 1 and len(sides_b) == 1 and sides_a[0] != sides_b[0]


def _result(entry: dict[str, Any]) -> str:
    _, battle, _ = _entry_parts(entry)
    return str(battle.get("result", "")).strip().lower()


def _started_datetime(raw: datetime | str) -> datetime | None:
    if isinstance(raw, datetime):
        if raw.tzinfo is None:
            return raw.replace(tzinfo=timezone.utc)
        return raw.astimezone(timezone.utc)
    if isinstance(raw, str):
        try:
            value = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        except ValueError:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)
    return None


def evaluate_pair_logs(
    tag_a: str,
    tag_b: str,
    logs_a: Iterable[dict[str, Any]],
    logs_b: Iterable[dict[str, Any]],
    *,
    started_at: datetime | str,
    allowed_modes: tuple[str, ...] = ("bounty",),
    ignored_battle_keys: set[str] | None = None,
) -> BattleDecision | None:
    """Return the first verifiable shared opponent match after a pairing began.

    Both battle-log records must agree on the battle identity and have explicit,
    opposite results. Draws are returned as a non-decisive decision. Missing,
    conflicting, same-team, old, or unsupported records are ignored.
    """
    canonical_a = _canonical_tag(tag_a)
    canonical_b = _canonical_tag(tag_b)
    if not canonical_a or not canonical_b or canonical_a == canonical_b:
        return None
    start = _started_datetime(started_at)
    if start is None:
        return None

    ignored = ignored_battle_keys or set()
    modes = {mode.strip().lower() for mode in allowed_modes}
    allow_any_mode = not modes or "*" in modes

    def collect(logs: Iterable[dict[str, Any]], own_tag: str, opponent_tag: str) -> dict[str, dict[str, Any]]:
        collected: dict[str, dict[str, Any]] = {}
        for entry in logs:
            if not isinstance(entry, dict):
                continue
            key = battle_key(entry)
            if not key or key in ignored:
                continue
            event, battle, raw = _entry_parts(entry)
            played_at = parse_battle_time(raw.get("battleTime"))
            if played_at is None or played_at < start:
                continue
            mode = _mode_for(entry)
            if not allow_any_mode and mode not in modes:
                continue
            if not _are_opponents(entry, canonical_a, canonical_b):
                continue
            # The official endpoint is player-specific. Check that its team data
            # includes the queried account, rather than trusting a copied tag.
            if not any(
                isinstance(player, dict) and _canonical_tag(player.get("tag")) == own_tag
                for team in battle.get("teams", []) if isinstance(team, list)
                for player in team
            ):
                continue
            collected[key] = {
                "entry": entry,
                "time": played_at,
                "time_raw": str(raw.get("battleTime", "")),
                "mode": mode,
                "map": str(event.get("map") or ""),
                "own_tag": own_tag,
                "opponent_tag": opponent_tag,
            }
        return collected

    by_a = collect(logs_a, canonical_a, canonical_b)
    by_b = collect(logs_b, canonical_b, canonical_a)
    shared_keys = set(by_a).intersection(by_b)
    ordered_keys = sorted(shared_keys, key=lambda key: (by_a[key]["time"], key))

    for key in ordered_keys:
        result_a = _result(by_a[key]["entry"])
        result_b = _result(by_b[key]["entry"])
        if result_a == "draw" and result_b == "draw":
            return BattleDecision(
                kind="draw",
                battle_key=key,
                battle_time=by_a[key]["time_raw"],
                mode=by_a[key]["mode"],
                map_name=by_a[key]["map"],
            )
        if result_a == "victory" and result_b == "defeat":
            winner_tag, loser_tag = canonical_a, canonical_b
        elif result_a == "defeat" and result_b == "victory":
            winner_tag, loser_tag = canonical_b, canonical_a
        else:
            # In particular: don't guess if the API hasn't delivered both sides
            # or reports inconsistent values.
            continue
        return BattleDecision(
            kind="decisive",
            battle_key=key,
            battle_time=by_a[key]["time_raw"],
            mode=by_a[key]["mode"],
            map_name=by_a[key]["map"],
            winner_tag=winner_tag,
            loser_tag=loser_tag,
        )
    return None
