from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from dotenv import load_dotenv

from .brawl_api import normalize_tag


@dataclass(frozen=True)
class Settings:
    discord_token: str
    brawl_stars_api_token: str
    guild_id: int
    queue_channel_id: int
    result_channel_id: int
    protected_discord_id: int | None
    protected_brawl_tag: str | None
    winner_guild_id: int | None
    winner_invite_channel_id: int | None
    winner_server_staff_ids: tuple[int, ...]
    time_zone: ZoneInfo
    daily_open_hour: int
    daily_open_minute: int
    allowed_modes: tuple[str, ...]
    match_poll_seconds: int
    event_size: int
    dry_run: bool
    database_path: Path


def _required(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value or value == "replace_me":
        raise ValueError(f"{name} is required; set it in your local .env file")
    return value


def _snowflake(name: str, *, required: bool = True) -> int | None:
    raw = os.getenv(name, "").strip()
    if not raw:
        if required:
            raise ValueError(f"{name} is required; set it in your local .env file")
        return None
    if not raw.isdecimal() or int(raw) <= 0:
        raise ValueError(f"{name} must be a positive Discord ID")
    return int(raw)


def _integer(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.getenv(name, str(default)).strip()
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if not minimum <= value <= maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _snowflake_list(name: str) -> tuple[int, ...]:
    raw = os.getenv(name, "").strip()
    if not raw:
        return ()
    result: list[int] = []
    for value in raw.split(","):
        value = value.strip()
        if not value:
            continue
        if not value.isdecimal() or int(value) <= 0:
            raise ValueError(f"{name} must be a comma-separated list of positive Discord IDs")
        result.append(int(value))
    return tuple(dict.fromkeys(result))


def load_settings() -> Settings:
    """Load settings from environment and an optional local .env file."""
    load_dotenv()

    guild_id = _snowflake("DISCORD_GUILD_ID")
    queue_channel_id = _snowflake("QUEUE_CHANNEL_ID")
    result_channel_id = _snowflake("RESULT_CHANNEL_ID", required=False) or queue_channel_id
    protected_id = _snowflake("PROTECTED_DISCORD_ID", required=False)
    protected_tag_raw = os.getenv("PROTECTED_BRAWL_TAG", "").strip()
    if bool(protected_id) != bool(protected_tag_raw):
        raise ValueError("Set both PROTECTED_DISCORD_ID and PROTECTED_BRAWL_TAG, or leave both empty")
    try:
        protected_tag = normalize_tag(protected_tag_raw) if protected_tag_raw else None
    except ValueError as exc:
        raise ValueError("PROTECTED_BRAWL_TAG must be a valid Brawl Stars tag") from exc
    winner_guild_id = _snowflake("WINNER_GUILD_ID", required=False)
    winner_invite_channel_id = _snowflake("WINNER_INVITE_CHANNEL_ID", required=False)
    winner_staff_ids = _snowflake_list("WINNER_SERVER_STAFF_IDS")
    if winner_guild_id and not winner_invite_channel_id:
        raise ValueError("WINNER_INVITE_CHANNEL_ID is required when WINNER_GUILD_ID is set")
    if winner_invite_channel_id and not winner_guild_id:
        raise ValueError("WINNER_GUILD_ID is required when WINNER_INVITE_CHANNEL_ID is set")
    if winner_guild_id == guild_id:
        raise ValueError("WINNER_GUILD_ID must be a different server from DISCORD_GUILD_ID")

    time_zone_name = os.getenv("TIME_ZONE", "Asia/Seoul").strip() or "Asia/Seoul"
    try:
        time_zone = ZoneInfo(time_zone_name)
    except ZoneInfoNotFoundError as exc:
        raise ValueError(f"Unknown TIME_ZONE: {time_zone_name}") from exc

    requested_modes = tuple(
        item.strip().lower()
        for item in os.getenv("ALLOWED_MODES", "bounty").split(",")
        if item.strip()
    )
    if set(requested_modes) != {"bounty"}:
        raise ValueError("This bot is locked to Bounty mode only; set ALLOWED_MODES=bounty")
    allowed_modes = ("bounty",)

    event_size = _integer("EVENT_SIZE", 10, 2, 64)
    if event_size != 10:
        raise ValueError("This bot's daily tournament is designed for exactly 10 entrants; keep EVENT_SIZE=10")

    return Settings(
        discord_token=_required("DISCORD_BOT_TOKEN"),
        brawl_stars_api_token=_required("BRAWL_STARS_API_TOKEN"),
        guild_id=guild_id,
        queue_channel_id=queue_channel_id,
        result_channel_id=result_channel_id,
        protected_discord_id=protected_id,
        protected_brawl_tag=protected_tag,
        winner_guild_id=winner_guild_id,
        winner_invite_channel_id=winner_invite_channel_id,
        winner_server_staff_ids=winner_staff_ids,
        time_zone=time_zone,
        daily_open_hour=_integer("DAILY_OPEN_HOUR", 18, 0, 23),
        daily_open_minute=_integer("DAILY_OPEN_MINUTE", 0, 0, 59),
        allowed_modes=allowed_modes,
        match_poll_seconds=_integer("MATCH_POLL_SECONDS", 10, 5, 600),
        event_size=event_size,
        dry_run=_bool("DRY_RUN", True),
        database_path=Path(os.getenv("DATABASE_PATH", "./data/brawl-bot.sqlite3")).expanduser(),
    )
