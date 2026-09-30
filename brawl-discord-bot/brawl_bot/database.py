from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import aiosqlite

from .brawl_api import normalize_tag


SCHEMA = """
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS guild_settings (
    guild_id INTEGER PRIMARY KEY,
    protected_user_id INTEGER,
    protected_brawl_tag TEXT,
    winner_guild_id INTEGER,
    winner_invite_channel_id INTEGER
);

CREATE TABLE IF NOT EXISTS registrations (
    guild_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    player_tag TEXT NOT NULL,
    player_name TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('pending', 'approved', 'rejected')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    PRIMARY KEY (guild_id, user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS unique_registered_tag
    ON registrations(guild_id, player_tag)
    WHERE status IN ('pending', 'approved');

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    guild_id INTEGER NOT NULL,
    event_key TEXT UNIQUE,
    event_date TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('open', 'active', 'completed', 'cancelled')),
    queue_channel_id INTEGER NOT NULL,
    result_channel_id INTEGER NOT NULL,
    queue_message_id INTEGER,
    current_round INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    finished_at TEXT
);

CREATE TABLE IF NOT EXISTS event_players (
    event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id INTEGER NOT NULL,
    player_tag TEXT NOT NULL,
    player_name TEXT NOT NULL,
    seed INTEGER NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
    PRIMARY KEY (event_id, user_id),
    UNIQUE (event_id, seed)
);

CREATE TABLE IF NOT EXISTS matches (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    round_no INTEGER NOT NULL,
    slot_no INTEGER NOT NULL,
    player1_user_id INTEGER NOT NULL,
    player2_user_id INTEGER NOT NULL,
    started_at TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('monitoring', 'pending_ban', 'completed', 'cancelled')),
    winner_user_id INTEGER,
    loser_user_id INTEGER,
    result_kind TEXT,
    battle_key TEXT,
    ban_status TEXT,
    failure_reason TEXT,
    winner_qualified INTEGER NOT NULL DEFAULT 0,
    UNIQUE(event_id, round_no, slot_no),
    FOREIGN KEY (event_id, player1_user_id) REFERENCES event_players(event_id, user_id),
    FOREIGN KEY (event_id, player2_user_id) REFERENCES event_players(event_id, user_id)
);

CREATE TABLE IF NOT EXISTS ignored_battles (
    match_id INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    battle_key TEXT NOT NULL,
    reason TEXT NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (match_id, battle_key)
);

CREATE TABLE IF NOT EXISTS protected_winners (
    source_guild_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL,
    protected_user_id INTEGER NOT NULL,
    protected_brawl_tag TEXT,
    event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    match_id INTEGER NOT NULL REFERENCES matches(id) ON DELETE CASCADE,
    qualified_at TEXT NOT NULL,
    invite_status TEXT NOT NULL DEFAULT 'pending' CHECK(invite_status IN ('pending', 'sent', 'joined', 'failed')),
    invite_url TEXT,
    failure_reason TEXT,
    PRIMARY KEY (source_guild_id, user_id, protected_user_id)
);

CREATE TABLE IF NOT EXISTS tournament_bans (
    guild_id INTEGER NOT NULL,
    subject_type TEXT NOT NULL CHECK(subject_type IN ('discord_id', 'player_tag')),
    subject_value TEXT NOT NULL,
    discord_user_id INTEGER NOT NULL,
    player_tag TEXT NOT NULL,
    event_id INTEGER NOT NULL,
    match_id INTEGER NOT NULL,
    created_at TEXT NOT NULL,
    PRIMARY KEY (guild_id, subject_type, subject_value)
);
"""

def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


async def _fetchone(conn: aiosqlite.Connection, sql: str, params: tuple[Any, ...] = ()) -> dict[str, Any] | None:
    async with conn.execute(sql, params) as cursor:
        row = await cursor.fetchone()
        return dict(row) if row is not None else None


async def _fetchall(conn: aiosqlite.Connection, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    async with conn.execute(sql, params) as cursor:
        rows = await cursor.fetchall()
        return [dict(row) for row in rows]


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.conn: aiosqlite.Connection | None = None

    async def open(
        self,
        default_protected_user_id: int | None = None,
        default_protected_brawl_tag: str | None = None,
        default_winner_guild_id: int | None = None,
        default_winner_invite_channel_id: int | None = None,
    ) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.conn = await aiosqlite.connect(self.path)
        self.conn.row_factory = aiosqlite.Row
        await self.conn.executescript(SCHEMA)

        # Migrate databases created before the protected Brawl tag was stored.
        settings_columns = await _fetchall(self.conn, "PRAGMA table_info(guild_settings)")
        if not any(row["name"] == "protected_brawl_tag" for row in settings_columns):
            await self.conn.execute("ALTER TABLE guild_settings ADD COLUMN protected_brawl_tag TEXT")
        if not any(row["name"] == "winner_guild_id" for row in settings_columns):
            await self.conn.execute("ALTER TABLE guild_settings ADD COLUMN winner_guild_id INTEGER")
        if not any(row["name"] == "winner_invite_channel_id" for row in settings_columns):
            await self.conn.execute("ALTER TABLE guild_settings ADD COLUMN winner_invite_channel_id INTEGER")
        match_columns = await _fetchall(self.conn, "PRAGMA table_info(matches)")
        if not any(row["name"] == "winner_qualified" for row in match_columns):
            await self.conn.execute("ALTER TABLE matches ADD COLUMN winner_qualified INTEGER NOT NULL DEFAULT 0")
        winner_columns = await _fetchall(self.conn, "PRAGMA table_info(protected_winners)")
        if not any(row["name"] == "protected_brawl_tag" for row in winner_columns):
            await self.conn.execute("ALTER TABLE protected_winners ADD COLUMN protected_brawl_tag TEXT")
            await self.conn.execute(
                "UPDATE protected_winners SET invite_status = 'failed', "
                "failure_reason = 'Legacy qualification needs a new verified win against the fixed protected tag' "
                "WHERE protected_brawl_tag IS NULL"
            )

        settings = await _fetchone(
            self.conn,
            "SELECT protected_user_id, protected_brawl_tag, winner_guild_id, winner_invite_channel_id "
            "FROM guild_settings WHERE guild_id = ?",
            (self._guild_id,),
        )
        if settings is None and any(
            value is not None
            for value in (
                default_protected_user_id,
                default_protected_brawl_tag,
                default_winner_guild_id,
                default_winner_invite_channel_id,
            )
        ):
            await self.conn.execute(
                "INSERT INTO guild_settings "
                "(guild_id, protected_user_id, protected_brawl_tag, winner_guild_id, winner_invite_channel_id) "
                "VALUES (?, ?, ?, ?, ?)",
                (
                    self._guild_id,
                    default_protected_user_id,
                    default_protected_brawl_tag,
                    default_winner_guild_id,
                    default_winner_invite_channel_id,
                ),
            )
        elif settings is not None:
            if default_protected_user_id is not None and settings["protected_user_id"] is None:
                await self.conn.execute(
                    "UPDATE guild_settings SET protected_user_id = ?, protected_brawl_tag = ? WHERE guild_id = ?",
                    (default_protected_user_id, default_protected_brawl_tag, self._guild_id),
                )
            elif (
                default_protected_user_id is not None
                and int(settings["protected_user_id"] or 0) == default_protected_user_id
                and not settings["protected_brawl_tag"]
                and default_protected_brawl_tag
            ):
                await self.conn.execute(
                    "UPDATE guild_settings SET protected_brawl_tag = ? WHERE guild_id = ?",
                    (default_protected_brawl_tag, self._guild_id),
                )
            if default_winner_guild_id is not None and settings["winner_guild_id"] is None:
                await self.conn.execute(
                    "UPDATE guild_settings SET winner_guild_id = ?, winner_invite_channel_id = ? WHERE guild_id = ?",
                    (default_winner_guild_id, default_winner_invite_channel_id, self._guild_id),
                )

        settings = await _fetchone(
            self.conn,
            "SELECT protected_user_id, protected_brawl_tag FROM guild_settings WHERE guild_id = ?",
            (self._guild_id,),
        )
        if settings and settings["protected_user_id"] is not None and settings["protected_brawl_tag"]:
            await self._ensure_protected_registration(
                int(settings["protected_user_id"]),
                str(settings["protected_brawl_tag"]),
            )

        # Do not resume active events created by the old elimination-bracket format.
        # They lack the protected-target event row and must not produce cross-player matches.
        legacy_events = await _fetchall(
            self.conn,
            "SELECT e.id FROM events e WHERE e.guild_id = ? AND e.status IN ('open', 'active') "
            "AND NOT EXISTS (SELECT 1 FROM event_players ep WHERE ep.event_id = e.id AND ep.status = 'protected')",
            (self._guild_id,),
        )
        for legacy_event in legacy_events:
            event_id = int(legacy_event["id"])
            await self.conn.execute(
                "UPDATE matches SET status = 'cancelled' WHERE event_id = ? AND status IN ('monitoring', 'pending_ban')",
                (event_id,),
            )
            await self.conn.execute(
                "UPDATE events SET status = 'cancelled', event_key = NULL, finished_at = ? WHERE id = ?",
                (_utc_now(), event_id),
            )
        await self.conn.commit()

    @property
    def _guild_id(self) -> int:
        # Set by bind_guild; keeping DB helpers single-guild avoids accidentally
        # applying one server's tournament rules to another server.
        if not hasattr(self, "guild_id"):
            raise RuntimeError("Database.bind_guild() must be called before database operations")
        return self.guild_id

    def bind_guild(self, guild_id: int) -> None:
        self.guild_id = guild_id

    def _db(self) -> aiosqlite.Connection:
        if self.conn is None:
            raise RuntimeError("Database is not open")
        return self.conn

    async def close(self) -> None:
        if self.conn is not None:
            await self.conn.close()
            self.conn = None

    async def _ensure_protected_registration(self, user_id: int, player_tag: str) -> None:
        conn = self._db()
        duplicate = await _fetchone(
            conn,
            "SELECT user_id FROM registrations WHERE guild_id = ? AND player_tag = ? "
            "AND status IN ('pending', 'approved')",
            (self._guild_id, player_tag),
        )
        if duplicate and int(duplicate["user_id"]) != user_id:
            raise ValueError("보호 대상으로 설정하려는 브롤 태그가 다른 Discord 계정에 등록되어 있습니다.")
        now = _utc_now()
        await conn.execute(
            "INSERT INTO registrations(guild_id, user_id, player_tag, player_name, status, created_at, updated_at) "
            "VALUES (?, ?, ?, 'Protected player', 'approved', ?, ?) "
            "ON CONFLICT(guild_id, user_id) DO UPDATE SET "
            "player_tag = excluded.player_tag, "
            "player_name = CASE WHEN registrations.player_name = 'Protected player' "
            "THEN excluded.player_name ELSE registrations.player_name END, "
            "status = 'approved', updated_at = excluded.updated_at",
            (self._guild_id, user_id, player_tag, now, now),
        )

    async def get_protected_user_id(self, guild_id: int | None = None) -> int | None:
        row = await _fetchone(
            self._db(),
            "SELECT protected_user_id FROM guild_settings WHERE guild_id = ?",
            (guild_id or self._guild_id,),
        )
        return int(row["protected_user_id"]) if row and row["protected_user_id"] is not None else None

    async def get_protected_brawl_tag(self, guild_id: int | None = None) -> str | None:
        row = await _fetchone(
            self._db(),
            "SELECT protected_brawl_tag FROM guild_settings WHERE guild_id = ?",
            (guild_id or self._guild_id,),
        )
        return str(row["protected_brawl_tag"]) if row and row["protected_brawl_tag"] else None

    async def get_winner_destination(self) -> tuple[int, int] | None:
        row = await _fetchone(
            self._db(),
            "SELECT winner_guild_id, winner_invite_channel_id FROM guild_settings WHERE guild_id = ?",
            (self._guild_id,),
        )
        if not row or row["winner_guild_id"] is None or row["winner_invite_channel_id"] is None:
            return None
        return int(row["winner_guild_id"]), int(row["winner_invite_channel_id"])

    async def set_winner_destination(self, winner_guild_id: int, invite_channel_id: int) -> None:
        if winner_guild_id <= 0 or invite_channel_id <= 0:
            raise ValueError("Discord server/channel IDs must be positive")
        if winner_guild_id == self._guild_id:
            raise ValueError("보조 서버는 메인 대회 서버와 달라야 합니다.")
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            current = await _fetchone(
                conn,
                "SELECT winner_guild_id, winner_invite_channel_id FROM guild_settings WHERE guild_id = ?",
                (self._guild_id,),
            )
            changed = not current or (
                current["winner_guild_id"] != winner_guild_id
                or current["winner_invite_channel_id"] != invite_channel_id
            )
            await conn.execute(
                "INSERT INTO guild_settings(guild_id, winner_guild_id, winner_invite_channel_id) VALUES (?, ?, ?) "
                "ON CONFLICT(guild_id) DO UPDATE SET winner_guild_id = excluded.winner_guild_id, "
                "winner_invite_channel_id = excluded.winner_invite_channel_id",
                (self._guild_id, winner_guild_id, invite_channel_id),
            )
            if changed:
                protected = await _fetchone(
                    conn,
                    "SELECT protected_user_id, protected_brawl_tag FROM guild_settings WHERE guild_id = ?",
                    (self._guild_id,),
                )
                if protected and protected["protected_user_id"] is not None and protected["protected_brawl_tag"]:
                    await conn.execute(
                        "UPDATE protected_winners SET invite_status = 'pending', invite_url = NULL, failure_reason = NULL "
                        "WHERE source_guild_id = ? AND protected_user_id = ? AND protected_brawl_tag = ?",
                        (self._guild_id, protected["protected_user_id"], protected["protected_brawl_tag"]),
                    )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise

    async def get_tournament_ban(self, user_id: int, player_tag: str | None = None) -> dict[str, Any] | None:
        tag = normalize_tag(player_tag) if player_tag else None
        conditions = "subject_type = 'discord_id' AND subject_value = ?"
        params: list[Any] = [self._guild_id, str(user_id)]
        if tag is not None:
            conditions += " OR (subject_type = 'player_tag' AND subject_value = ?)"
            params.append(tag)
        return await _fetchone(
            self._db(),
            "SELECT * FROM tournament_bans WHERE guild_id = ? AND (" + conditions + ") "
            "ORDER BY CASE subject_type WHEN 'discord_id' THEN 0 ELSE 1 END LIMIT 1",
            tuple(params),
        )

    async def add_tournament_ban(
        self,
        user_id: int,
        player_tag: str,
        *,
        event_id: int,
        match_id: int,
    ) -> None:
        tag = normalize_tag(player_tag)
        conn = self._db()
        now = _utc_now()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            for subject_type, subject_value in (("discord_id", str(user_id)), ("player_tag", tag)):
                await conn.execute(
                    "INSERT INTO tournament_bans "
                    "(guild_id, subject_type, subject_value, discord_user_id, player_tag, event_id, match_id, created_at) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                    "ON CONFLICT(guild_id, subject_type, subject_value) DO UPDATE SET "
                    "discord_user_id = excluded.discord_user_id, player_tag = excluded.player_tag, "
                    "event_id = excluded.event_id, match_id = excluded.match_id, created_at = excluded.created_at",
                    (self._guild_id, subject_type, subject_value, user_id, tag, event_id, match_id, now),
                )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise

    async def has_pending_tournament_ban(self, user_id: int) -> bool:
        row = await _fetchone(
            self._db(),
            "SELECT m.id FROM matches m JOIN events e ON e.id = m.event_id "
            "WHERE e.guild_id = ? AND m.loser_user_id = ? AND m.status = 'pending_ban' LIMIT 1",
            (self._guild_id, user_id),
        )
        return row is not None

    async def clear_tournament_bans(self, user_id: int) -> tuple[str, ...]:
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            rows = await _fetchall(
                conn,
                "SELECT subject_value FROM tournament_bans WHERE guild_id = ? AND discord_user_id = ? "
                "AND subject_type = 'player_tag'",
                (self._guild_id, user_id),
            )
            await conn.execute(
                "DELETE FROM tournament_bans WHERE guild_id = ? AND discord_user_id = ?",
                (self._guild_id, user_id),
            )
            await conn.commit()
            return tuple(str(row["subject_value"]) for row in rows)
        except Exception:
            await conn.rollback()
            raise

    async def is_qualified_winner(self, user_id: int, protected_user_id: int) -> bool:
        protected_tag = await self.get_protected_brawl_tag()
        if protected_tag is None:
            return False
        row = await _fetchone(
            self._db(),
            "SELECT user_id FROM protected_winners WHERE source_guild_id = ? AND user_id = ? "
            "AND protected_user_id = ? AND protected_brawl_tag = ?",
            (self._guild_id, user_id, protected_user_id, protected_tag),
        )
        return row is not None

    async def get_qualified_winner(self, user_id: int, protected_user_id: int) -> dict[str, Any] | None:
        protected_tag = await self.get_protected_brawl_tag()
        if protected_tag is None:
            return None
        return await _fetchone(
            self._db(),
            "SELECT * FROM protected_winners WHERE source_guild_id = ? AND user_id = ? "
            "AND protected_user_id = ? AND protected_brawl_tag = ?",
            (self._guild_id, user_id, protected_user_id, protected_tag),
        )

    async def get_pending_winner_invites(self, protected_user_id: int) -> list[dict[str, Any]]:
        protected_tag = await self.get_protected_brawl_tag()
        if protected_tag is None:
            return []
        return await _fetchall(
            self._db(),
            "SELECT * FROM protected_winners WHERE source_guild_id = ? AND protected_user_id = ? "
            "AND protected_brawl_tag = ? AND invite_status = 'pending' ORDER BY qualified_at",
            (self._guild_id, protected_user_id, protected_tag),
        )

    async def update_winner_invite(
        self,
        user_id: int,
        protected_user_id: int,
        *,
        status: str,
        invite_url: str | None = None,
        failure_reason: str | None = None,
    ) -> None:
        if status not in {"pending", "sent", "joined", "failed"}:
            raise ValueError("Invalid winner invite status")
        await self._db().execute(
            "UPDATE protected_winners SET invite_status = ?, invite_url = COALESCE(?, invite_url), failure_reason = ? "
            "WHERE source_guild_id = ? AND user_id = ? AND protected_user_id = ?",
            (status, invite_url, failure_reason, self._guild_id, user_id, protected_user_id),
        )
        await self._db().commit()

    async def mark_winner_joined(self, user_id: int, protected_user_id: int) -> None:
        await self.update_winner_invite(user_id, protected_user_id, status="joined")

    async def set_protected_account(self, user_id: int, player_tag: str, player_name: str) -> None:
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            active = await _fetchone(
                conn,
                "SELECT id FROM events WHERE guild_id = ? AND status IN ('open', 'active') LIMIT 1",
                (self._guild_id,),
            )
            if active:
                raise ValueError("진행 중인 이벤트가 있을 때는 보호 대상을 바꿀 수 없습니다.")
            await conn.execute(
                "INSERT INTO guild_settings(guild_id, protected_user_id, protected_brawl_tag) VALUES (?, ?, ?) "
                "ON CONFLICT(guild_id) DO UPDATE SET "
                "protected_user_id = excluded.protected_user_id, protected_brawl_tag = excluded.protected_brawl_tag",
                (self._guild_id, user_id, player_tag),
            )
            await self._ensure_protected_registration(user_id, player_tag)
            await conn.execute(
                "UPDATE registrations SET player_name = ?, updated_at = ? WHERE guild_id = ? AND user_id = ?",
                (player_name[:100], _utc_now(), self._guild_id, user_id),
            )
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise

    async def register_tag(self, user_id: int, player_tag: str, player_name: str) -> str:
        """Save a valid tag as self-approved; legacy pending tags are approved on resubmission."""
        conn = self._db()
        now = _utc_now()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            protected = await _fetchone(
                conn,
                "SELECT protected_user_id, protected_brawl_tag FROM guild_settings WHERE guild_id = ?",
                (self._guild_id,),
            )
            if protected and protected["protected_brawl_tag"]:
                protected_id = int(protected["protected_user_id"]) if protected["protected_user_id"] is not None else None
                protected_tag = str(protected["protected_brawl_tag"])
                if user_id == protected_id and player_tag != protected_tag:
                    raise ValueError("고정 보호 대상은 관리자가 설정한 브롤 태그만 사용할 수 있습니다.")
                if player_tag == protected_tag and user_id != protected_id:
                    raise ValueError("이 브롤 태그는 고정 보호 대상 계정에 연결되어 있습니다.")

            tournament_ban = await _fetchone(
                conn,
                "SELECT subject_type FROM tournament_bans WHERE guild_id = ? AND ("
                "(subject_type = 'discord_id' AND subject_value = ?) OR "
                "(subject_type = 'player_tag' AND subject_value = ?)) "
                "ORDER BY CASE subject_type WHEN 'discord_id' THEN 0 ELSE 1 END LIMIT 1",
                (self._guild_id, str(user_id), player_tag),
            )
            if tournament_ban:
                if tournament_ban["subject_type"] == "discord_id":
                    raise ValueError("이 Discord 계정은 대회 영구 밴 상태입니다. 관리자에게 문의해 주세요.")
                raise ValueError("이 Brawl 태그는 대회 영구 밴 상태입니다. 다른 Discord 계정으로도 등록할 수 없습니다.")

            duplicate = await _fetchone(
                conn,
                "SELECT user_id FROM registrations "
                "WHERE guild_id = ? AND player_tag = ? AND status IN ('pending', 'approved')",
                (self._guild_id, player_tag),
            )
            if duplicate and int(duplicate["user_id"]) != user_id:
                raise ValueError("이 브롤 태그는 이미 다른 디스코드 계정에 등록되어 있습니다.")

            existing = await _fetchone(
                conn,
                "SELECT player_tag, status FROM registrations WHERE guild_id = ? AND user_id = ?",
                (self._guild_id, user_id),
            )
            if existing and existing["player_tag"] == player_tag and existing["status"] == "approved":
                await conn.commit()
                return "approved"
            if existing and existing["player_tag"] == player_tag and existing["status"] == "pending":
                await conn.execute(
                    "UPDATE registrations SET status = 'approved', updated_at = ? "
                    "WHERE guild_id = ? AND user_id = ?",
                    (now, self._guild_id, user_id),
                )
                await conn.commit()
                return "approved"

            await conn.execute(
                "INSERT INTO registrations(guild_id, user_id, player_tag, player_name, status, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, 'approved', ?, ?) "
                "ON CONFLICT(guild_id, user_id) DO UPDATE SET "
                "player_tag = excluded.player_tag, player_name = excluded.player_name, "
                "status = 'approved', updated_at = excluded.updated_at",
                (self._guild_id, user_id, player_tag, player_name, now, now),
            )
            await conn.commit()
            return "approved"
        except Exception:
            await conn.rollback()
            raise

    async def get_registration(self, user_id: int) -> dict[str, Any] | None:
        return await _fetchone(
            self._db(),
            "SELECT * FROM registrations WHERE guild_id = ? AND user_id = ?",
            (self._guild_id, user_id),
        )

    async def set_registration_status(self, user_id: int, status: str) -> bool:
        if status not in {"approved", "rejected"}:
            raise ValueError("Invalid registration status")
        cursor = await self._db().execute(
            "UPDATE registrations SET status = ?, updated_at = ? "
            "WHERE guild_id = ? AND user_id = ? AND status IN ('pending', 'approved')",
            (status, _utc_now(), self._guild_id, user_id),
        )
        await self._db().commit()
        return cursor.rowcount > 0

    async def create_event(
        self,
        *,
        event_key: str | None,
        event_date: str,
        queue_channel_id: int,
        result_channel_id: int,
    ) -> int:
        """Create a queue with the fixed protected player stored as the opponent, not an entrant."""
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            active = await _fetchone(
                conn,
                "SELECT id FROM events WHERE guild_id = ? AND status IN ('open', 'active') LIMIT 1",
                (self._guild_id,),
            )
            if active:
                raise ValueError("이미 참가 모집 중이거나 진행 중인 이벤트가 있습니다.")
            protected = await _fetchone(
                conn,
                "SELECT protected_user_id, protected_brawl_tag FROM guild_settings WHERE guild_id = ?",
                (self._guild_id,),
            )
            if not protected or protected["protected_user_id"] is None or not protected["protected_brawl_tag"]:
                raise ValueError("보호 Discord 계정과 고정 Brawl 태그를 먼저 설정해 주세요.")
            protected_user_id = int(protected["protected_user_id"])
            protected_tag = str(protected["protected_brawl_tag"])
            protected_registration = await _fetchone(
                conn,
                "SELECT player_name FROM registrations WHERE guild_id = ? AND user_id = ? AND player_tag = ?",
                (self._guild_id, protected_user_id, protected_tag),
            )
            if not protected_registration:
                raise ValueError("고정 보호 계정의 Brawl 태그 등록을 찾지 못했습니다. /set_protected를 다시 실행해 주세요.")
            cursor = await conn.execute(
                "INSERT INTO events(guild_id, event_key, event_date, status, queue_channel_id, result_channel_id, created_at) "
                "VALUES (?, ?, ?, 'open', ?, ?, ?)",
                (self._guild_id, event_key, event_date, queue_channel_id, result_channel_id, _utc_now()),
            )
            event_id = int(cursor.lastrowid)
            await conn.execute(
                "INSERT INTO event_players(event_id, user_id, player_tag, player_name, seed, status) "
                "VALUES (?, ?, ?, ?, 0, 'protected')",
                (event_id, protected_user_id, protected_tag, protected_registration["player_name"]),
            )
            await conn.commit()
            return event_id
        except Exception:
            await conn.rollback()
            raise

    async def get_event_by_key(self, event_key: str) -> dict[str, Any] | None:
        return await _fetchone(self._db(), "SELECT * FROM events WHERE event_key = ?", (event_key,))

    async def get_active_event(self) -> dict[str, Any] | None:
        return await _fetchone(
            self._db(),
            "SELECT * FROM events WHERE guild_id = ? AND status IN ('open', 'active') ORDER BY id DESC LIMIT 1",
            (self._guild_id,),
        )

    async def get_event(self, event_id: int) -> dict[str, Any] | None:
        return await _fetchone(self._db(), "SELECT * FROM events WHERE id = ?", (event_id,))

    async def set_queue_message_id(self, event_id: int, message_id: int) -> None:
        await self._db().execute("UPDATE events SET queue_message_id = ? WHERE id = ?", (message_id, event_id))
        await self._db().commit()

    async def cancel_stale_open_events(self, today: str) -> list[int]:
        """Cancel old, not-yet-started queues when the next local day arrives."""
        rows = await _fetchall(
            self._db(),
            "SELECT id FROM events WHERE guild_id = ? AND status = 'open' AND event_date < ?",
            (self._guild_id, today),
        )
        if rows:
            await self._db().execute(
                "UPDATE events SET status = 'cancelled', finished_at = ? "
                "WHERE guild_id = ? AND status = 'open' AND event_date < ?",
                (_utc_now(), self._guild_id, today),
            )
            await self._db().commit()
        return [int(row["id"]) for row in rows]

    async def join_queue(self, event_id: int, user_id: int, capacity: int) -> dict[str, Any]:
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            event = await _fetchone(conn, "SELECT * FROM events WHERE id = ?", (event_id,))
            if not event or event["status"] != "open":
                raise ValueError("현재 참가 신청을 받는 이벤트가 없습니다.")
            registration = await _fetchone(
                conn,
                "SELECT player_tag, player_name, status FROM registrations WHERE guild_id = ? AND user_id = ?",
                (self._guild_id, user_id),
            )
            tournament_ban = await self.get_tournament_ban(
                user_id,
                str(registration["player_tag"]) if registration else None,
            )
            if tournament_ban:
                raise ValueError("이 Discord 계정 또는 Brawl 태그는 대회 영구 밴 상태입니다. 관리자에게 문의해 주세요.")
            if not registration or registration["status"] != "approved":
                raise ValueError("먼저 /register로 유효한 브롤 태그를 등록해 주세요.")
            existing = await _fetchone(
                conn,
                "SELECT seed, status FROM event_players WHERE event_id = ? AND user_id = ?",
                (event_id, user_id),
            )
            if existing and existing["status"] == "protected":
                raise ValueError("보호 대상은 대기열에 들어가지 않습니다. 등록한 10명과 순서대로 경기합니다.")
            if existing:
                raise ValueError(f"이미 오늘 이벤트에 참가했습니다. 순번: {existing['seed']}번")
            count_row = await _fetchone(
                conn,
                "SELECT COUNT(*) AS count FROM event_players WHERE event_id = ? AND status != 'protected'",
                (event_id,),
            )
            count = int(count_row["count"] if count_row else 0)
            if count >= capacity:
                raise ValueError("오늘 참가 정원 10명이 이미 찼습니다.")
            seed = count + 1
            await conn.execute(
                "INSERT INTO event_players(event_id, user_id, player_tag, player_name, seed, status) "
                "VALUES (?, ?, ?, ?, ?, 'queued')",
                (event_id, user_id, registration["player_tag"], registration["player_name"], seed),
            )
            count += 1
            started = count == capacity
            first_match_id: int | None = None
            if started:
                await conn.execute(
                    "UPDATE event_players SET status = 'active' WHERE event_id = ? AND status = 'queued'",
                    (event_id,),
                )
                await conn.execute(
                    "UPDATE events SET status = 'active', current_round = 1 WHERE id = ?",
                    (event_id,),
                )
                first_match_id = await self._create_challenge_tx(conn, event_id, 1)
                if first_match_id is None:
                    raise RuntimeError("Could not create the first protected-player challenge")
            await conn.commit()
            return {"seed": seed, "count": count, "started": started, "match_id": first_match_id}
        except Exception:
            await conn.rollback()
            raise

    async def leave_queue(self, user_id: int) -> int:
        """Remove the caller from an open queue and resequence the remaining roster."""
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            event = await _fetchone(
                conn,
                "SELECT id FROM events WHERE guild_id = ? AND status = 'open' ORDER BY id DESC LIMIT 1",
                (self._guild_id,),
            )
            if not event:
                raise ValueError("현재 참가 신청을 받는 이벤트가 없습니다.")
            event_id = int(event["id"])
            participant = await _fetchone(
                conn,
                "SELECT seed, status FROM event_players WHERE event_id = ? AND user_id = ?",
                (event_id, user_id),
            )
            if not participant or participant["status"] != "queued":
                raise ValueError("현재 대기열에 참가 신청되어 있지 않습니다.")
            await conn.execute(
                "DELETE FROM event_players WHERE event_id = ? AND user_id = ? AND status = 'queued'",
                (event_id, user_id),
            )
            # Resequence only challengers; the protected target keeps seed 0.
            await conn.execute(
                "UPDATE event_players SET seed = -seed WHERE event_id = ? AND status = 'queued'",
                (event_id,),
            )
            remaining = await _fetchall(
                conn,
                "SELECT user_id FROM event_players WHERE event_id = ? AND status = 'queued' ORDER BY seed DESC",
                (event_id,),
            )
            for seed, row in enumerate(remaining, start=1):
                await conn.execute(
                    "UPDATE event_players SET seed = ? WHERE event_id = ? AND user_id = ?",
                    (seed, event_id, row["user_id"]),
                )
            await conn.commit()
            return len(remaining)
        except Exception:
            await conn.rollback()
            raise

    async def _create_challenge_tx(
        self,
        conn: aiosqlite.Connection,
        event_id: int,
        challenger_seed: int,
    ) -> int | None:
        target = await _fetchone(
            conn,
            "SELECT user_id FROM event_players WHERE event_id = ? AND status = 'protected' LIMIT 1",
            (event_id,),
        )
        challenger = await _fetchone(
            conn,
            "SELECT user_id, seed FROM event_players WHERE event_id = ? AND status = 'active' AND seed = ?",
            (event_id, challenger_seed),
        )
        if not target or not challenger:
            return None
        cursor = await conn.execute(
            "INSERT INTO matches(event_id, round_no, slot_no, player1_user_id, player2_user_id, started_at, status) "
            "VALUES (?, 1, ?, ?, ?, ?, 'monitoring')",
            (
                event_id,
                int(challenger["seed"]),
                int(target["user_id"]),
                int(challenger["user_id"]),
                _utc_now(),
            ),
        )
        await conn.execute("UPDATE events SET current_round = ? WHERE id = ?", (challenger_seed, event_id))
        return int(cursor.lastrowid)

    async def get_event_players(self, event_id: int) -> list[dict[str, Any]]:
        return await _fetchall(
            self._db(),
            "SELECT * FROM event_players WHERE event_id = ? AND status != 'protected' ORDER BY seed",
            (event_id,),
        )

    async def get_round_matches(self, event_id: int, round_no: int = 1) -> list[dict[str, Any]]:
        return await _fetchall(
            self._db(),
            "SELECT m.*, p1.player_tag AS tag1, p1.player_name AS name1, "
            "p2.player_tag AS tag2, p2.player_name AS name2 "
            "FROM matches m "
            "JOIN event_players p1 ON p1.event_id = m.event_id AND p1.user_id = m.player1_user_id "
            "JOIN event_players p2 ON p2.event_id = m.event_id AND p2.user_id = m.player2_user_id "
            "WHERE m.event_id = ? AND m.round_no = ? ORDER BY m.slot_no",
            (event_id, round_no),
        )

    async def get_active_matches(self) -> list[dict[str, Any]]:
        return await _fetchall(
            self._db(),
            "SELECT m.*, e.guild_id, e.result_channel_id, e.current_round, "
            "p1.player_tag AS tag1, p1.player_name AS name1, "
            "p2.player_tag AS tag2, p2.player_name AS name2 "
            "FROM matches m JOIN events e ON e.id = m.event_id "
            "JOIN event_players p1 ON p1.event_id = m.event_id AND p1.user_id = m.player1_user_id "
            "JOIN event_players p2 ON p2.event_id = m.event_id AND p2.user_id = m.player2_user_id "
            "WHERE e.guild_id = ? AND e.status = 'active' AND m.status = 'monitoring' "
            "ORDER BY m.event_id, m.round_no, m.slot_no",
            (self._guild_id,),
        )

    async def get_pending_ban_matches(self) -> list[dict[str, Any]]:
        return await _fetchall(
            self._db(),
            "SELECT m.*, e.guild_id, e.result_channel_id, "
            "p1.player_tag AS tag1, p1.player_name AS name1, "
            "p2.player_tag AS tag2, p2.player_name AS name2 "
            "FROM matches m JOIN events e ON e.id = m.event_id "
            "JOIN event_players p1 ON p1.event_id = m.event_id AND p1.user_id = m.player1_user_id "
            "JOIN event_players p2 ON p2.event_id = m.event_id AND p2.user_id = m.player2_user_id "
            "WHERE e.guild_id = ? AND m.status = 'pending_ban' ORDER BY m.id",
            (self._guild_id,),
        )

    async def get_match(self, match_id: int) -> dict[str, Any] | None:
        return await _fetchone(
            self._db(),
            "SELECT m.*, e.guild_id, e.result_channel_id, "
            "p1.player_tag AS tag1, p1.player_name AS name1, "
            "p2.player_tag AS tag2, p2.player_name AS name2 "
            "FROM matches m JOIN events e ON e.id = m.event_id "
            "JOIN event_players p1 ON p1.event_id = m.event_id AND p1.user_id = m.player1_user_id "
            "JOIN event_players p2 ON p2.event_id = m.event_id AND p2.user_id = m.player2_user_id "
            "WHERE m.id = ?",
            (match_id,),
        )

    async def get_match_by_slot(self, event_id: int, round_no: int, slot_no: int) -> dict[str, Any] | None:
        return await _fetchone(
            self._db(),
            "SELECT m.*, e.guild_id, e.result_channel_id, "
            "p1.player_tag AS tag1, p1.player_name AS name1, "
            "p2.player_tag AS tag2, p2.player_name AS name2 "
            "FROM matches m JOIN events e ON e.id = m.event_id "
            "JOIN event_players p1 ON p1.event_id = m.event_id AND p1.user_id = m.player1_user_id "
            "JOIN event_players p2 ON p2.event_id = m.event_id AND p2.user_id = m.player2_user_id "
            "WHERE m.event_id = ? AND m.round_no = ? AND m.slot_no = ?",
            (event_id, round_no, slot_no),
        )

    async def get_ignored_battle_keys(self, match_id: int) -> set[str]:
        rows = await _fetchall(self._db(), "SELECT battle_key FROM ignored_battles WHERE match_id = ?", (match_id,))
        return {str(row["battle_key"]) for row in rows}

    async def ignore_battle(self, match_id: int, battle_key: str, reason: str) -> None:
        await self._db().execute(
            "INSERT OR IGNORE INTO ignored_battles(match_id, battle_key, reason, created_at) VALUES (?, ?, ?, ?)",
            (match_id, battle_key, reason, _utc_now()),
        )
        await self._db().commit()

    async def mark_pending_ban(
        self,
        match_id: int,
        *,
        winner_user_id: int,
        loser_user_id: int,
        battle_key_value: str,
        result_kind: str,
    ) -> bool:
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            row = await _fetchone(conn, "SELECT * FROM matches WHERE id = ?", (match_id,))
            if not row or row["status"] != "monitoring":
                await conn.rollback()
                return False
            player_ids = {int(row["player1_user_id"]), int(row["player2_user_id"])}
            if winner_user_id not in player_ids or loser_user_id not in player_ids or winner_user_id == loser_user_id:
                await conn.rollback()
                raise ValueError("경기 승자와 패자는 현재 1대1에 등록된 서로 다른 두 플레이어여야 합니다.")
            await conn.execute(
                "UPDATE matches SET status = 'pending_ban', winner_user_id = ?, loser_user_id = ?, "
                "battle_key = ?, result_kind = ?, ban_status = 'pending' WHERE id = ?",
                (winner_user_id, loser_user_id, battle_key_value, result_kind, match_id),
            )
            await conn.commit()
            return True
        except Exception:
            await conn.rollback()
            raise

    async def complete_pending_match(
        self,
        match_id: int,
        *,
        ban_status: str,
        protected_loser: bool,
        protected_user_id: int | None = None,
        protected_brawl_tag: str | None = None,
        protected_win_qualification: bool = False,
        failure_reason: str | None = None,
    ) -> dict[str, Any] | None:
        """Finalize the current fixed-target duel, then queue the next challenger."""
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            match = await _fetchone(conn, "SELECT * FROM matches WHERE id = ?", (match_id,))
            if not match or match["status"] != "pending_ban":
                await conn.rollback()
                return None
            event = await _fetchone(conn, "SELECT * FROM events WHERE id = ?", (match["event_id"],))
            if not event or event["status"] != "active":
                await conn.rollback()
                return None

            target = await _fetchone(
                conn,
                "SELECT user_id FROM event_players WHERE event_id = ? AND status = 'protected' LIMIT 1",
                (match["event_id"],),
            )
            if not target:
                raise RuntimeError("Active sequential event is missing its protected target")
            protected_id = int(target["user_id"])
            player_ids = {int(match["player1_user_id"]), int(match["player2_user_id"])}
            if protected_id not in player_ids:
                raise RuntimeError("Current match does not include the configured protected target")
            challenger_id = next(user_id for user_id in player_ids if user_id != protected_id)
            if int(match["winner_user_id"]) not in player_ids or int(match["loser_user_id"]) not in player_ids:
                raise RuntimeError("Resolved winner/loser do not belong to the current match")

            loser_player = await _fetchone(
                conn,
                "SELECT player_tag FROM event_players WHERE event_id = ? AND user_id = ?",
                (match["event_id"], match["loser_user_id"]),
            )
            winner_qualified = bool(
                protected_win_qualification
                and protected_loser
                and int(match["loser_user_id"]) == protected_id
                and protected_user_id == protected_id
                and protected_brawl_tag is not None
                and loser_player is not None
                and str(loser_player["player_tag"]) == protected_brawl_tag
            )
            await conn.execute(
                "UPDATE matches SET status = 'completed', ban_status = ?, failure_reason = ?, winner_qualified = ? WHERE id = ?",
                (ban_status, failure_reason, int(winner_qualified), match_id),
            )
            challenger_status = "challenger_won" if int(match["winner_user_id"]) == challenger_id else "challenger_lost"
            await conn.execute(
                "UPDATE event_players SET status = ? WHERE event_id = ? AND user_id = ? AND status = 'active'",
                (challenger_status, match["event_id"], challenger_id),
            )
            if winner_qualified:
                await conn.execute(
                    "INSERT OR IGNORE INTO protected_winners "
                    "(source_guild_id, user_id, protected_user_id, protected_brawl_tag, event_id, match_id, qualified_at, invite_status) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'pending') "
                    "ON CONFLICT(source_guild_id, user_id, protected_user_id) DO UPDATE SET "
                    "protected_brawl_tag = excluded.protected_brawl_tag, event_id = excluded.event_id, "
                    "match_id = excluded.match_id, qualified_at = excluded.qualified_at, "
                    "invite_status = 'pending', invite_url = NULL, failure_reason = NULL "
                    "WHERE protected_winners.protected_brawl_tag IS NULL "
                    "OR protected_winners.protected_brawl_tag != excluded.protected_brawl_tag",
                    (
                        int(event["guild_id"]),
                        int(match["winner_user_id"]),
                        protected_user_id,
                        protected_brawl_tag,
                        int(match["event_id"]),
                        match_id,
                        _utc_now(),
                    ),
                )

            progression = await self._advance_event_tx(conn, int(match["event_id"]))
            await conn.commit()
            return progression
        except Exception:
            await conn.rollback()
            raise

    async def _advance_event_tx(self, conn: aiosqlite.Connection, event_id: int) -> dict[str, Any]:
        next_challenger = await _fetchone(
            conn,
            "SELECT seed FROM event_players WHERE event_id = ? AND status = 'active' ORDER BY seed LIMIT 1",
            (event_id,),
        )
        if next_challenger:
            challenge_no = int(next_challenger["seed"])
            match_id = await self._create_challenge_tx(conn, event_id, challenge_no)
            if match_id is None:
                raise RuntimeError(f"Could not create sequential challenge {challenge_no} for event {event_id}")
            return {"next_challenge_no": challenge_no, "next_match_id": match_id}

        completed = await _fetchone(
            conn,
            "SELECT COUNT(*) AS count FROM matches WHERE event_id = ? AND status = 'completed'",
            (event_id,),
        )
        completed_count = int(completed["count"] if completed else 0)
        await conn.execute(
            "UPDATE events SET status = 'completed', finished_at = ? WHERE id = ?",
            (_utc_now(), event_id),
        )
        return {"event_completed": True, "matches_completed": completed_count}

    async def get_active_event_snapshot(self) -> dict[str, Any] | None:
        event = await self.get_active_event()
        if not event:
            return None
        event_id = int(event["id"])
        players = await self.get_event_players(event_id)
        matches = await self.get_round_matches(event_id, 1) if event["status"] == "active" else []
        return {"event": event, "players": players, "matches": matches, "byes": []}

    async def cancel_event(self, event_id: int) -> None:
        conn = self._db()
        await conn.execute("BEGIN IMMEDIATE")
        try:
            event = await _fetchone(conn, "SELECT status FROM events WHERE id = ?", (event_id,))
            if not event or event["status"] not in {"open", "active"}:
                raise ValueError("취소할 수 있는 진행 중 이벤트가 없습니다.")
            pending_ban = await _fetchone(
                conn,
                "SELECT id FROM matches WHERE event_id = ? AND status = 'pending_ban' LIMIT 1",
                (event_id,),
            )
            if pending_ban:
                raise ValueError("승패가 확정되어 추방 처리가 진행 중입니다. 처리가 끝난 뒤 취소해 주세요.")
            await conn.execute(
                "UPDATE events SET status = 'cancelled', finished_at = ? WHERE id = ?",
                (_utc_now(), event_id),
            )
            await conn.execute("UPDATE matches SET status = 'cancelled' WHERE event_id = ? AND status = 'monitoring'", (event_id,))
            await conn.commit()
        except Exception:
            await conn.rollback()
            raise
