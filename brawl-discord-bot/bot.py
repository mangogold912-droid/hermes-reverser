from __future__ import annotations

import asyncio
import logging
from dataclasses import replace
from datetime import date, datetime, time, timedelta, timezone
from typing import Any

import discord
from discord import app_commands
from discord.ext import commands

from brawl_bot.battle_logic import BattleDecision, evaluate_pair_logs
from brawl_bot.brawl_api import BrawlAPIError, BrawlStarsAPI, normalize_tag
from brawl_bot.config import Settings, load_settings
from brawl_bot.database import MultiGuildDatabase, current_guild_id


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("brawl-challenge-bot")


async def _send_ephemeral(interaction: discord.Interaction, content: str) -> None:
    if interaction.response.is_done():
        await interaction.followup.send(content, ephemeral=True)
    else:
        await interaction.response.send_message(content, ephemeral=True)


def _is_admin(interaction: discord.Interaction) -> bool:
    if interaction.guild is not None and interaction.guild.owner_id == interaction.user.id:
        return True
    permissions = getattr(interaction.user, "guild_permissions", None)
    return bool(permissions and permissions.administrator)


class BrawlCommandTree(app_commands.CommandTree):
    async def interaction_check(self, interaction: discord.Interaction) -> bool:
        if interaction.guild_id is None:
            await _send_ephemeral(interaction, "이 명령은 봇이 설치된 Discord 서버 안에서만 사용할 수 있습니다.")
            return False
        bot = self.client
        if not isinstance(bot, BrawlChallengeBot):
            return False
        bot.db.activate_guild(interaction.guild_id)
        try:
            settings = await bot.ensure_guild_settings(interaction.guild_id)
        except Exception:
            log.exception("Could not load settings for guild %s", interaction.guild_id)
            await _send_ephemeral(interaction, "이 서버 설정을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.")
            return False
        command_name = str((interaction.data or {}).get("name", ""))
        if command_name != "configure_servers" and (
            settings.queue_channel_id is None or settings.result_channel_id is None
        ):
            await _send_ephemeral(
                interaction,
                "먼저 서버 관리자가 `/configure_servers`를 실행해 대회 채널과 보조 서버를 설정해야 합니다.",
            )
            return False
        return True


class DailyQueueView(discord.ui.View):
    """Persistent join button for the current daily event."""

    def __init__(self, bot: "BrawlChallengeBot") -> None:
        super().__init__(timeout=None)
        self.bot = bot

    @discord.ui.button(
        label="오늘 참가하기",
        style=discord.ButtonStyle.success,
        custom_id="brawl_elimination:daily_join:v1",
    )
    async def join_button(self, interaction: discord.Interaction, button: discord.ui.Button) -> None:
        if interaction.guild_id is None:
            await _send_ephemeral(interaction, "이 버튼은 Discord 서버 안에서만 사용할 수 있습니다.")
            return
        self.bot.db.activate_guild(interaction.guild_id)
        settings = await self.bot.ensure_guild_settings(interaction.guild_id)
        if settings.queue_channel_id is None or settings.result_channel_id is None:
            await _send_ephemeral(interaction, "이 서버는 아직 대회 서버로 설정되지 않았습니다.")
            return
        if interaction.user.bot:
            await _send_ephemeral(interaction, "봇 계정은 참가할 수 없습니다.")
            return
        if interaction.guild is None or interaction.guild.verification_level != discord.VerificationLevel.highest:
            await _send_ephemeral(
                interaction,
                "전화번호 인증을 위해 메인 서버 Verification Level을 Highest로 설정해야 참가할 수 있습니다. 관리자에게 알려 주세요.",
            )
            return

        event = await self.bot.db.get_active_event()
        if not event or event["status"] != "open":
            await _send_ephemeral(interaction, "현재 참가 신청을 받는 이벤트가 없습니다.")
            return
        if event.get("queue_message_id") and int(event["queue_message_id"]) != interaction.message.id:
            await _send_ephemeral(interaction, "지난 이벤트의 버튼입니다. 오늘 올라온 참가 버튼을 사용해 주세요.")
            return

        protected_id = await self.bot.db.get_protected_user_id()
        protected_tag = await self.bot.db.get_protected_brawl_tag()
        if protected_id is None or protected_tag is None:
            await _send_ephemeral(interaction, "관리자가 /set_protected 또는 /set_protected_id로 보호 Discord 계정과 브롤 태그를 설정해야 합니다.")
            return
        if _is_admin(interaction) and interaction.user.id != protected_id:
            await _send_ephemeral(interaction, "안전 설정상 서버 관리자는 대회 참가자로 등록할 수 없습니다.")
            return

        try:
            joined = await self.bot.db.join_queue(
                int(event["id"]),
                interaction.user.id,
                self.bot.settings.event_size,
            )
        except ValueError as exc:
            await _send_ephemeral(interaction, str(exc))
            return

        if joined["started"]:
            await _send_ephemeral(interaction, "10명 모집 완료! 보호 대상과 1번 도전자의 경기를 시작합니다. 이후 도전자도 순번대로 한 명씩 경기합니다.")
            await self.bot.refresh_queue_message(int(event["id"]))
            await self.bot.announce_challenge(int(event["id"]), 1)
        else:
            await _send_ephemeral(
                interaction,
                f"참가 완료: **{joined['seed']}번** (현재 {joined['count']}/10명)",
            )
            await self.bot.refresh_queue_message(int(event["id"]))


class BrawlChallengeBot(commands.Bot):
    def __init__(self, settings: Settings) -> None:
        intents = discord.Intents.default()
        # Required for winners-only destination checks and protected-target membership checks.
        intents.members = True
        super().__init__(
            command_prefix="!",
            intents=intents,
            help_command=None,
            tree_cls=BrawlCommandTree,
            allowed_contexts=app_commands.AppCommandContext(guild=True, dm_channel=False, private_channel=False),
            allowed_installs=app_commands.AppInstallationType(guild=True, user=False),
        )
        self._base_settings = settings
        self._guild_settings: dict[int, Settings] = {}
        self.db = MultiGuildDatabase(settings.database_path, settings.guild_id)
        self.brawl_api = BrawlStarsAPI(settings.brawl_stars_api_token)
        self._daily_attempted: dict[int, date] = {}
        self._daily_task: asyncio.Task[None] | None = None
        self._poll_task: asyncio.Task[None] | None = None
        self._view_registered = False
        self._register_app_commands()

    @property
    def settings(self) -> Settings:
        guild_id = current_guild_id()
        if guild_id is None:
            return self._base_settings
        configured = self._guild_settings.get(guild_id)
        if configured is not None:
            return configured
        return replace(
            self._base_settings,
            guild_id=guild_id,
            queue_channel_id=None,
            result_channel_id=None,
            protected_discord_id=None,
            protected_brawl_tag=None,
            winner_guild_id=None,
            winner_invite_channel_id=None,
            dry_run=True,
        )

    async def ensure_guild_settings(self, guild_id: int) -> Settings:
        guild_id = int(guild_id)
        cached = self._guild_settings.get(guild_id)
        if cached is not None:
            return cached
        with self.db.guild_context(guild_id):
            channels = await self.db.get_server_channels()
            if channels is None and guild_id == self._base_settings.guild_id:
                if self._base_settings.queue_channel_id is not None and self._base_settings.result_channel_id is not None:
                    await self.db.set_server_channels(
                        self._base_settings.queue_channel_id,
                        self._base_settings.result_channel_id,
                    )
                    channels = (self._base_settings.queue_channel_id, self._base_settings.result_channel_id)
            destination = await self.db.get_winner_destination()
            protected_id = await self.db.get_protected_user_id()
            protected_tag = await self.db.get_protected_brawl_tag()
            auto_ban_enabled = await self.db.get_auto_ban_enabled()
        configured = replace(
            self._base_settings,
            guild_id=guild_id,
            queue_channel_id=channels[0] if channels else None,
            result_channel_id=channels[1] if channels else None,
            protected_discord_id=protected_id,
            protected_brawl_tag=protected_tag,
            winner_guild_id=destination[0] if destination else None,
            winner_invite_channel_id=destination[1] if destination else None,
            dry_run=self._base_settings.dry_run or not auto_ban_enabled,
        )
        self._guild_settings[guild_id] = configured
        return configured

    async def setup_hook(self) -> None:
        await self.db.open(
            self._base_settings.protected_discord_id,
            self._base_settings.protected_brawl_tag,
            self._base_settings.winner_guild_id,
            self._base_settings.winner_invite_channel_id,
        )
        configured_guild_ids = await self.db.get_configured_guild_ids()
        if self._base_settings.guild_id is not None and self._base_settings.guild_id not in configured_guild_ids:
            with self.db.guild_context(self._base_settings.guild_id):
                if self._base_settings.queue_channel_id is not None and self._base_settings.result_channel_id is not None:
                    await self.db.set_server_channels(
                        self._base_settings.queue_channel_id,
                        self._base_settings.result_channel_id,
                    )
                    configured_guild_ids.append(self._base_settings.guild_id)
        for guild_id in configured_guild_ids:
            await self.ensure_guild_settings(guild_id)

        await self.brawl_api.start()
        if not self._view_registered:
            self.add_view(DailyQueueView(self))
            self._view_registered = True

        # Global commands make the bot usable in every guild where it is installed.
        await self.tree.sync()
        log.info("Global slash commands synced")
        # Remove old guild-scoped copies from the single-guild version of this bot.
        for guild_id in configured_guild_ids:
            guild = discord.Object(id=guild_id)
            self.tree.clear_commands(guild=guild)
            try:
                await self.tree.sync(guild=guild)
            except discord.HTTPException:
                log.exception("Could not remove legacy guild-scoped commands from guild %s", guild_id)

        self._daily_task = asyncio.create_task(self._daily_scheduler(), name="daily-queue-scheduler")
        self._poll_task = asyncio.create_task(self._match_poller(), name="battle-log-poller")

    async def close(self) -> None:
        for task in (self._daily_task, self._poll_task):
            if task is not None:
                task.cancel()
        tasks = [task for task in (self._daily_task, self._poll_task) if task is not None]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        await self.brawl_api.close()
        await self.db.close()
        await super().close()

    async def on_ready(self) -> None:
        log.info("Connected as %s (%s)", self.user, self.user.id if self.user else "unknown")

    async def qualified_sources_for_destination(self, destination_guild_id: int, user_id: int) -> list[tuple[int, int]]:
        """Return every source guild where this account earned access to this destination."""
        qualified: list[tuple[int, int]] = []
        for source_guild_id in await self.db.get_source_guild_ids_for_destination(destination_guild_id):
            await self.ensure_guild_settings(source_guild_id)
            with self.db.guild_context(source_guild_id):
                protected_id = await self.db.get_protected_user_id()
                if protected_id is not None and await self.db.is_qualified_winner(user_id, protected_id):
                    qualified.append((source_guild_id, protected_id))
                    await self.db.mark_winner_joined(user_id, protected_id)
        return qualified

    async def on_member_join(self, member: discord.Member) -> None:
        if self.user and member.id == self.user.id:
            return
        if member.id == member.guild.owner_id or member.id in self._base_settings.winner_server_staff_ids:
            return
        try:
            source_guild_ids = await self.db.get_source_guild_ids_for_destination(member.guild.id)
            if not source_guild_ids:
                return
            qualified = await self.qualified_sources_for_destination(member.guild.id, member.id)
        except Exception:
            # Fail closed if the destination is actively configured as winners-only.
            log.exception("Could not verify destination-guild joiner %s", member.id)
            qualified = []
        if qualified:
            log.info(
                "Qualified user %s joined winner guild %s via source guild(s) %s",
                member.id,
                member.guild.id,
                [source_id for source_id, _ in qualified],
            )
            return

        try:
            await member.kick(reason="Winners-only server: this Discord account has not defeated a configured protected player.")
            log.info("Removed non-qualified user %s from winner guild %s", member.id, member.guild.id)
        except discord.Forbidden:
            log.error("Could not remove non-qualified user %s from winner guild; check Kick Members and role position", member.id)
        except discord.HTTPException:
            log.exception("Discord API failed while removing non-qualified user %s", member.id)

    async def _save_protected_target(
        self,
        interaction: discord.Interaction,
        *,
        user_id: int,
        mention: str,
        tag: str,
    ) -> None:
        try:
            canonical_tag = normalize_tag(tag)
            profile = await self.brawl_api.get_player(canonical_tag)
            confirmed_tag = normalize_tag(str(profile.get("tag") or canonical_tag))
            player_name = str(profile.get("name") or "Protected player")[:100]
            await self.db.set_protected_account(user_id, confirmed_tag, player_name)
            if interaction.guild_id is not None:
                current = await self.ensure_guild_settings(interaction.guild_id)
                self._guild_settings[interaction.guild_id] = replace(
                    current,
                    protected_discord_id=user_id,
                    protected_brawl_tag=confirmed_tag,
                )
        except (ValueError, BrawlAPIError) as exc:
            await _send_ephemeral(interaction, str(exc))
            return
        except Exception:
            log.exception("Could not set protected Discord/Brawl account pair")
            await _send_ephemeral(interaction, "보호 계정을 저장하지 못했습니다. 태그/데이터베이스를 확인해 주세요.")
            return

        await _send_ephemeral(
            interaction,
            f"{mention} · **{player_name}** ({confirmed_tag})을 보호 대상으로 설정했습니다. "
            "이 Discord 계정은 경기에서 져도 자동 영구밴되지 않습니다.",
        )
        now = datetime.now(self.settings.time_zone)
        scheduled_today = datetime.combine(
            now.date(),
            time(self.settings.daily_open_hour, self.settings.daily_open_minute),
            tzinfo=self.settings.time_zone,
        )
        if now >= scheduled_today and await self.db.get_active_event() is None:
            await self._open_daily_event(now.date())

    def _register_app_commands(self) -> None:
        @app_commands.command(name="register", description="유효한 브롤 태그를 등록하고 바로 대회에 참가할 수 있게 합니다.")
        @app_commands.describe(tag="게임 프로필의 플레이어 태그 (#은 생략해도 됩니다)")
        async def register(interaction: discord.Interaction, tag: str) -> None:
            try:
                canonical_tag = normalize_tag(tag)
                profile = await self.brawl_api.get_player(canonical_tag)
            except ValueError as exc:
                await _send_ephemeral(interaction, str(exc))
                return
            except BrawlAPIError as exc:
                await _send_ephemeral(interaction, str(exc))
                return

            profile_tag = profile.get("tag")
            confirmed_tag = normalize_tag(profile_tag if isinstance(profile_tag, str) else canonical_tag)
            player_name = str(profile.get("name") or "Unknown")[:100]
            try:
                await self.db.register_tag(interaction.user.id, confirmed_tag, player_name)
            except ValueError as exc:
                await _send_ephemeral(interaction, str(exc))
                return
            except Exception:
                log.exception("Could not save tag registration for user %s", interaction.user.id)
                await _send_ephemeral(interaction, "태그 등록을 저장하지 못했습니다. 관리자에게 알려 주세요.")
                return

            await _send_ephemeral(
                interaction,
                f"태그 등록 완료: **{player_name}** ({confirmed_tag}). 관리자 승인 없이 참가할 수 있습니다.\n"
                "주의: 태그가 실제 계정인지 확인했지만 소유자 인증은 되지 않습니다. 본인 태그만 등록해 주세요."
            )

        @app_commands.command(name="my_tag", description="내 브롤 태그 등록 상태를 확인합니다.")
        async def my_tag(interaction: discord.Interaction) -> None:
            registration = await self.db.get_registration(interaction.user.id)
            if not registration:
                await _send_ephemeral(interaction, "등록된 태그가 없습니다. /register로 먼저 등록해 주세요.")
                return
            labels = {"pending": "이전 등록 승인 대기", "approved": "등록됨", "rejected": "등록 거절됨"}
            await _send_ephemeral(
                interaction,
                f"**{registration['player_name']}** ({registration['player_tag']}) · "
                f"상태: {labels.get(registration['status'], registration['status'])}",
            )

        @app_commands.command(name="leave_queue", description="10명 모집이 완료되기 전에 오늘 대기열에서 나갑니다.")
        async def leave_queue(interaction: discord.Interaction) -> None:
            try:
                remaining = await self.db.leave_queue(interaction.user.id)
            except ValueError as exc:
                await _send_ephemeral(interaction, str(exc))
                return
            event = await self.db.get_active_event()
            if event:
                await self.refresh_queue_message(int(event["id"]))
            await _send_ephemeral(interaction, f"대기열에서 나왔습니다. 남은 인원은 {remaining}/10명입니다.")

        @app_commands.command(name="set_protected", description="보호 Discord 계정과 고정 Brawl 태그를 설정합니다.")
        @app_commands.describe(member="보호할 Discord 계정", tag="해당 계정의 고정 브롤 태그")
        @app_commands.default_permissions(administrator=True)
        async def set_protected(interaction: discord.Interaction, member: discord.Member, tag: str) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            if member.bot:
                await _send_ephemeral(interaction, "보호 대상은 봇이 아닌 Discord 사용자여야 합니다.")
                return
            await self._save_protected_target(
                interaction,
                user_id=member.id,
                mention=member.mention,
                tag=tag,
            )

        @app_commands.command(name="set_protected_id", description="Discord 사용자 ID와 고정 Brawl 태그로 보호 대상을 설정합니다.")
        @app_commands.describe(user_id="메인 서버에 있는 보호 대상의 숫자 Discord ID", tag="해당 계정의 고정 브롤 태그")
        @app_commands.default_permissions(administrator=True)
        async def set_protected_id(interaction: discord.Interaction, user_id: str, tag: str) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            if not user_id.strip().isdecimal() or int(user_id.strip()) <= 0:
                await _send_ephemeral(interaction, "Discord ID는 양의 숫자로 입력해 주세요.")
                return
            guild = interaction.guild
            if guild is None:
                await _send_ephemeral(interaction, "메인 서버 안에서만 사용할 수 있습니다.")
                return
            target_id = int(user_id.strip())
            member = guild.get_member(target_id)
            if member is None:
                try:
                    member = await guild.fetch_member(target_id)
                except discord.NotFound:
                    await _send_ephemeral(interaction, "해당 Discord ID의 사용자가 메인 서버에 없습니다.")
                    return
                except discord.Forbidden:
                    await _send_ephemeral(interaction, "대상 멤버를 조회할 권한이 없습니다. Server Members Intent와 권한을 확인해 주세요.")
                    return
                except discord.HTTPException:
                    log.exception("Could not fetch member %s while setting protected target", target_id)
                    await _send_ephemeral(interaction, "Discord에서 대상 멤버를 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.")
                    return
            if member.bot:
                await _send_ephemeral(interaction, "보호 대상은 봇이 아닌 Discord 사용자여야 합니다.")
                return
            await self._save_protected_target(
                interaction,
                user_id=member.id,
                mention=member.mention,
                tag=tag,
            )

        @app_commands.command(name="configure_servers", description="현재 메인 서버의 대회 채널과 승자 보조 서버를 설정합니다.")
        @app_commands.describe(
            main_guild_id="현재 명령을 실행하는 메인 서버 ID",
            queue_channel_id="참가 신청 패널을 올릴 메인 서버 채널 ID",
            result_channel_id="경기 공지/결과 채널 ID (모집 채널과 같아도 됩니다)",
            winner_guild_id="보조 승자 서버 ID",
            invite_channel_id="보조 서버에서 초대장을 만들 채널 ID",
        )
        @app_commands.default_permissions(administrator=True)
        async def configure_servers(
            interaction: discord.Interaction,
            main_guild_id: str,
            queue_channel_id: str,
            result_channel_id: str,
            winner_guild_id: str,
            invite_channel_id: str,
        ) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            if interaction.guild is None:
                await _send_ephemeral(interaction, "메인 서버 안에서만 사용할 수 있습니다.")
                return
            raw_ids = (main_guild_id, queue_channel_id, result_channel_id, winner_guild_id, invite_channel_id)
            if any(not value.strip().isdecimal() or int(value.strip()) <= 0 for value in raw_ids):
                await _send_ephemeral(interaction, "서버 ID와 채널 ID는 양의 숫자로 입력해 주세요.")
                return
            main_id, queue_id, result_id, destination_id, invite_id = (int(value.strip()) for value in raw_ids)
            if main_id != interaction.guild.id:
                await _send_ephemeral(
                    interaction,
                    "메인 서버 ID는 현재 `/configure_servers`를 실행한 서버의 ID와 일치해야 합니다.",
                )
                return
            if destination_id == main_id:
                await _send_ephemeral(interaction, "보조 승자 서버는 메인 서버와 달라야 합니다.")
                return
            await interaction.response.defer(ephemeral=True, thinking=True)
            main_guild = self.get_guild(main_id)
            destination_guild = self.get_guild(destination_id)
            if main_guild is None or destination_guild is None:
                await _send_ephemeral(interaction, "봇이 메인 서버와 보조 서버 양쪽에 초대되어 있어야 합니다.")
                return
            bot_main_member = main_guild.me
            bot_destination_member = destination_guild.me
            if bot_main_member is None or bot_destination_member is None:
                await _send_ephemeral(interaction, "봇 멤버 정보를 확인할 수 없습니다. Server Members Intent를 확인해 주세요.")
                return
            try:
                queue_channel = await self._fetch_channel(queue_id)
                result_channel = await self._fetch_channel(result_id)
                invite_channel = await self._fetch_channel(invite_id)
            except discord.NotFound:
                await _send_ephemeral(interaction, "입력한 채널 중 하나를 찾을 수 없습니다. 채널 ID를 확인해 주세요.")
                return
            except discord.Forbidden:
                await _send_ephemeral(interaction, "봇이 입력한 채널을 조회할 권한이 없습니다.")
                return
            except discord.HTTPException:
                log.exception("Could not fetch channels while configuring servers")
                await _send_ephemeral(interaction, "Discord에서 채널을 조회하지 못했습니다. 잠시 후 다시 시도해 주세요.")
                return
            if getattr(getattr(queue_channel, "guild", None), "id", None) != main_id or getattr(
                getattr(result_channel, "guild", None), "id", None
            ) != main_id:
                await _send_ephemeral(interaction, "모집 채널과 결과 채널은 입력한 메인 서버 안에 있어야 합니다.")
                return
            if getattr(getattr(invite_channel, "guild", None), "id", None) != destination_id:
                await _send_ephemeral(interaction, "초대 채널은 입력한 보조 서버 안에 있어야 합니다.")
                return
            required_channel_permissions = ("view_channel", "send_messages", "embed_links", "read_message_history")
            for label, channel in (("모집", queue_channel), ("결과", result_channel)):
                permissions_for = getattr(channel, "permissions_for", None)
                if not callable(permissions_for):
                    await _send_ephemeral(interaction, f"{label} 채널은 텍스트/스레드 채널이어야 합니다.")
                    return
                permissions = permissions_for(bot_main_member)
                if any(not getattr(permissions, permission, False) for permission in required_channel_permissions):
                    await _send_ephemeral(interaction, f"봇에 {label} 채널의 View, Send, Embed, Read History 권한이 필요합니다.")
                    return
            invite_permissions_for = getattr(invite_channel, "permissions_for", None)
            if not bot_destination_member.guild_permissions.kick_members:
                await _send_ephemeral(interaction, "보조 서버에서 봇에 Kick Members 권한을 부여해 주세요.")
                return
            if not callable(invite_permissions_for) or not invite_permissions_for(bot_destination_member).create_instant_invite:
                await _send_ephemeral(interaction, "초대 채널에서 봇에 Create Instant Invite 권한을 부여해 주세요.")
                return
            try:
                await self.db.configure_servers(
                    queue_channel_id=queue_id,
                    result_channel_id=result_id,
                    winner_guild_id=destination_id,
                    invite_channel_id=invite_id,
                )
            except ValueError as exc:
                await _send_ephemeral(interaction, str(exc))
                return
            except Exception:
                log.exception("Could not save main/secondary server configuration")
                await _send_ephemeral(interaction, "서버 설정을 저장하지 못했습니다. 데이터베이스를 확인해 주세요.")
                return
            self._guild_settings[main_id] = replace(
                self.settings,
                guild_id=main_id,
                queue_channel_id=queue_id,
                result_channel_id=result_id,
                winner_guild_id=destination_id,
                winner_invite_channel_id=invite_id,
            )
            await _send_ephemeral(
                interaction,
                f"서버 설정을 저장했습니다. 메인 서버: **{interaction.guild.name}** (`{main_id}`), 보조 서버 ID: `{destination_id}`. "
                "모집/결과 채널과 초대 채널 설정은 재시작 후에도 유지됩니다.",
            )

        @app_commands.command(name="set_auto_ban", description="이 서버에서 대회 자동 영구 밴을 켜거나 끕니다.")
        @app_commands.describe(
            enabled="True이면 확정 패배자를 영구 밴하고 ID/태그 차단 목록에 추가합니다.",
            confirm="자동 영구 밴을 켤 때만 True로 설정합니다.",
        )
        @app_commands.default_permissions(administrator=True)
        async def set_auto_ban(interaction: discord.Interaction, enabled: bool, confirm: bool = False) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            if interaction.guild is None:
                await _send_ephemeral(interaction, "Discord 서버 안에서만 사용할 수 있습니다.")
                return
            if enabled and not confirm:
                await _send_ephemeral(
                    interaction,
                    "확정된 패배자를 이 서버에서 영구 밴하고 Brawl 태그도 대회 차단 목록에 추가합니다. "
                    "진행 중 이벤트가 없고 보호 대상 설정이 끝났는지 확인한 뒤 `confirm:true`로 실행해 주세요.",
                )
                return
            if enabled and self._base_settings.dry_run:
                await _send_ephemeral(
                    interaction,
                    "봇 운영자의 전역 안전 잠금 `DRY_RUN=true`가 켜져 있어 밴을 활성화할 수 없습니다. "
                    "운영자가 로컬 `.env`에서 `DRY_RUN=false`로 바꾸고 봇을 재시작한 뒤 다시 실행해야 합니다.",
                )
                return
            if enabled:
                if await self.db.get_active_event() is not None:
                    await _send_ephemeral(interaction, "진행 중인 이벤트가 있습니다. 이벤트가 끝나거나 취소된 뒤 자동 밴을 켜 주세요.")
                    return
                bot_member = interaction.guild.me
                if bot_member is None or not bot_member.guild_permissions.ban_members:
                    await _send_ephemeral(interaction, "봇에 이 서버의 Ban Members 권한이 필요합니다.")
                    return
                if await self.db.get_protected_user_id() is None or await self.db.get_protected_brawl_tag() is None:
                    await _send_ephemeral(interaction, "먼저 `/set_protected` 또는 `/set_protected_id`로 보호 대상을 설정해 주세요.")
                    return
            try:
                await self.db.set_auto_ban_enabled(enabled)
            except Exception:
                log.exception("Could not update automatic-ban setting for guild %s", interaction.guild.id)
                await _send_ephemeral(interaction, "자동 밴 설정을 저장하지 못했습니다.")
                return
            current = await self.ensure_guild_settings(interaction.guild.id)
            self._guild_settings[interaction.guild.id] = replace(
                current,
                dry_run=self._base_settings.dry_run or not enabled,
            )
            if enabled:
                await _send_ephemeral(interaction, "이 서버에서 자동 영구 밴을 켰습니다. 명확한 Bounty 1대1 결과에만 적용됩니다.")
            else:
                await _send_ephemeral(interaction, "이 서버는 시험 모드입니다. 결과는 기록하지만 실제 Discord 밴과 태그 차단은 실행하지 않습니다.")

        @app_commands.command(name="set_winner_server", description="보호 대상을 이긴 참가자가 초대를 받을 보조 서버를 설정합니다.")
        @app_commands.describe(
            guild_id="보조 서버 ID (봇이 해당 서버에 먼저 초대되어 있어야 합니다)",
            invite_channel_id="해당 보조 서버의 초대 채널 ID",
        )
        @app_commands.default_permissions(administrator=True)
        async def set_winner_server(interaction: discord.Interaction, guild_id: str, invite_channel_id: str) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            if not guild_id.isdecimal() or int(guild_id) <= 0 or not invite_channel_id.isdecimal() or int(invite_channel_id) <= 0:
                await _send_ephemeral(interaction, "서버 ID와 채널 ID는 양의 숫자로 입력해 주세요.")
                return
            target_guild_id = int(guild_id)
            target_channel_id = int(invite_channel_id)
            if target_guild_id == self.settings.guild_id:
                await _send_ephemeral(interaction, "보조 서버는 메인 대회 서버와 달라야 합니다.")
                return
            if await self.db.get_protected_user_id() is None or await self.db.get_protected_brawl_tag() is None:
                await _send_ephemeral(interaction, "먼저 /set_protected 또는 /set_protected_id로 보호 계정과 Brawl 태그를 설정해 주세요.")
                return
            target_guild = self.get_guild(target_guild_id)
            if target_guild is None:
                await _send_ephemeral(interaction, "봇이 보조 서버에 없습니다. 먼저 봇을 해당 서버에 초대한 뒤 다시 시도해 주세요.")
                return
            bot_member = target_guild.me
            if bot_member is None or not bot_member.guild_permissions.kick_members:
                await _send_ephemeral(interaction, "보조 서버에서 봇에 Kick Members 권한을 부여한 뒤 다시 시도해 주세요.")
                return
            try:
                channel = await self._fetch_channel(target_channel_id)
            except discord.HTTPException:
                await _send_ephemeral(interaction, "초대 채널을 찾을 수 없습니다. 채널 ID와 봇의 조회 권한을 확인해 주세요.")
                return
            channel_guild = getattr(channel, "guild", None)
            if channel_guild is None or channel_guild.id != target_guild_id:
                await _send_ephemeral(interaction, "초대 채널은 입력한 보조 서버 안에 있어야 합니다.")
                return
            create_invite = getattr(channel, "create_invite", None)
            permissions_for = getattr(channel, "permissions_for", None)
            if not callable(create_invite) or not callable(permissions_for):
                await _send_ephemeral(interaction, "초대 채널 ID는 초대를 만들 수 있는 텍스트/음성 채널이어야 합니다.")
                return
            if not permissions_for(bot_member).create_instant_invite:
                await _send_ephemeral(interaction, "해당 채널에서 봇에 Create Instant Invite 권한을 부여해 주세요.")
                return
            try:
                await self.db.set_winner_destination(target_guild_id, target_channel_id)
            except ValueError as exc:
                await _send_ephemeral(interaction, str(exc))
                return
            if self.settings.guild_id is not None:
                self._guild_settings[self.settings.guild_id] = replace(
                    self.settings,
                    winner_guild_id=target_guild_id,
                    winner_invite_channel_id=target_channel_id,
                )
            await _send_ephemeral(
                interaction,
                f"보조 서버를 **{target_guild.name}** (`{target_guild_id}`)로 설정했습니다. "
                "자격자에게 1회용 초대를 DM하며, 자격 없는 신규 입장자는 추방됩니다. "
                "기존 멤버 정리는 필요할 때만 `/audit_winner_server confirm:true`로 실행하세요.",
            )

        @app_commands.command(name="phone_verification_status", description="Discord 서버의 전화번호 인증 요구 설정을 확인합니다.")
        @app_commands.default_permissions(administrator=True)
        async def phone_verification_status(interaction: discord.Interaction) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            guild = interaction.guild
            if guild is None:
                await _send_ephemeral(interaction, "서버 안에서만 사용할 수 있습니다.")
                return
            level = guild.verification_level
            if level == discord.VerificationLevel.highest:
                message = (
                    "전화번호 인증 요구가 켜져 있습니다 (Discord Verification Level: **Highest**). "
                    "Discord가 미인증 계정의 서버 참여를 제한합니다. 봇은 전화번호 자체나 인증 자료를 볼 수 없습니다."
                )
            else:
                message = (
                    f"현재 Verification Level은 **{level.name}**이며 전화번호 인증은 강제되지 않습니다. "
                    "전화 인증 계정만 받으려면 서버 설정 → Safety Setup → Verification Level에서 **Highest**를 선택하세요. "
                    "이 설정은 대회 참가자뿐 아니라 서버 전체 멤버에게 적용됩니다."
                )
            await _send_ephemeral(interaction, message)

        @app_commands.command(name="enable_phone_verification", description="Discord 서버의 Verification Level을 Highest로 설정합니다.")
        @app_commands.describe(confirm="True로 실행하면 서버 전체 멤버에게 전화번호 인증 요구가 적용됩니다.")
        @app_commands.default_permissions(administrator=True)
        async def enable_phone_verification(interaction: discord.Interaction, confirm: bool = False) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            guild = interaction.guild
            if guild is None:
                await _send_ephemeral(interaction, "서버 안에서만 사용할 수 있습니다.")
                return
            if not confirm:
                await _send_ephemeral(
                    interaction,
                    "이 설정은 메인 서버 전체 멤버에게 영향을 줍니다. 적용하려면 `confirm: True`로 실행해 주세요.",
                )
                return
            try:
                updated_guild = await guild.edit(
                    verification_level=discord.VerificationLevel.highest,
                    reason=f"Phone verification enabled by administrator {interaction.user.id}",
                )
                cached_guild = self.get_guild(guild.id)
                if cached_guild is not None:
                    cached_guild.verification_level = updated_guild.verification_level
            except discord.Forbidden:
                await _send_ephemeral(
                    interaction,
                    "봇에 Manage Server 권한이 없습니다. 권한을 추가하거나 서버 설정 → Safety Setup에서 직접 Highest로 바꿔 주세요.",
                )
                return
            except discord.HTTPException:
                log.exception("Could not enable phone verification on guild %s", guild.id)
                await _send_ephemeral(interaction, "Discord 서버 인증 수준을 변경하지 못했습니다. 잠시 후 다시 시도해 주세요.")
                return
            await _send_ephemeral(
                interaction,
                f"{updated_guild.name} 서버의 Verification Level을 **Highest**로 설정했습니다. "
                "전화번호 인증 처리는 Discord가 담당하며, 이 설정은 서버 전체에 적용됩니다.",
            )
            now = datetime.now(self.settings.time_zone)
            scheduled_today = datetime.combine(
                now.date(),
                time(self.settings.daily_open_hour, self.settings.daily_open_minute),
                tzinfo=self.settings.time_zone,
            )
            if now >= scheduled_today and await self.db.get_active_event() is None:
                await self._open_daily_event(now.date())

        @app_commands.command(name="open_event", description="관리자가 오늘의 10인 참가 모집을 즉시 엽니다.")
        @app_commands.default_permissions(administrator=True)
        async def open_event(interaction: discord.Interaction) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            try:
                event_id = await self.open_queue(event_key=None, event_date=datetime.now(self.settings.time_zone).date())
            except ValueError as exc:
                await _send_ephemeral(interaction, str(exc))
                return
            except Exception:
                log.exception("Could not open a manual event")
                await _send_ephemeral(interaction, "참가 모집을 열지 못했습니다. 채널 ID와 봇 권한을 확인해 주세요.")
                return
            await _send_ephemeral(interaction, f"이벤트 #{event_id} 참가 버튼을 설정된 모집 채널에 올렸습니다.")

        @app_commands.command(name="event_status", description="오늘 대기 순번과 보호 대상 1대1 경기 진행 상태를 확인합니다.")
        async def event_status(interaction: discord.Interaction) -> None:
            snapshot = await self.db.get_active_event_snapshot()
            if not snapshot:
                await _send_ephemeral(interaction, "진행 중인 이벤트가 없습니다.")
                return
            event = snapshot["event"]
            players = snapshot["players"]
            lines = [f"상태: **{event['status']}** · 도전자 **{len(players)}/10**"]
            if event["status"] == "open":
                lines.extend(f"{row['seed']}번 · <@{row['user_id']}> · {row['player_name']}" for row in players)
            else:
                lines.append(f"현재 도전자 순번: **{event['current_round']}/10**")
                lines.append("참가 순서: " + " · ".join(f"{row['seed']}. <@{row['user_id']}>" for row in players))
                for match in snapshot["matches"]:
                    state = match["status"]
                    if state == "completed":
                        state = f"완료 ({match['ban_status'] or '결과 저장'})"
                        if match["winner_qualified"]:
                            state += " · 보호 대상 패배, 보조 서버 자격"
                    else:
                        state = "경기 기록 확인 중" if state == "monitoring" else "승패 확인 · 밴 처리 중"
                    lines.append(
                        f"**도전자 {match['slot_no']}번** · <@{match['player1_user_id']}> vs "
                        f"<@{match['player2_user_id']}> · {state}"
                    )
            text = "\n".join(lines)
            await _send_ephemeral(interaction, text[:1900])

        @app_commands.command(name="cancel_event", description="관리자가 참가 모집 또는 진행 중인 이벤트를 취소합니다.")
        @app_commands.default_permissions(administrator=True)
        async def cancel_event(interaction: discord.Interaction) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            event = await self.db.get_active_event()
            if not event:
                await _send_ephemeral(interaction, "취소할 이벤트가 없습니다.")
                return
            try:
                await self.db.cancel_event(int(event["id"]))
            except ValueError as exc:
                await _send_ephemeral(interaction, str(exc))
                return
            await self.refresh_queue_message(int(event["id"]))
            await _send_ephemeral(interaction, f"이벤트 #{event['id']}을 취소했습니다. 자동 밴은 실행되지 않았습니다.")

        @app_commands.command(name="resolve_match", description="API로 판정되지 않은 현재 도전자 경기의 승자를 관리자가 직접 확정합니다.")
        @app_commands.describe(slot="/event_status에 표시된 현재 도전자 순번", winner="승자로 확정할 보호 대상 또는 현재 도전자")
        @app_commands.default_permissions(administrator=True)
        async def resolve_match(interaction: discord.Interaction, slot: app_commands.Range[int, 1, 10], winner: discord.Member) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            event = await self.db.get_active_event()
            if not event or event["status"] != "active":
                await _send_ephemeral(interaction, "진행 중인 대회가 없습니다.")
                return
            if int(slot) != int(event["current_round"]):
                await _send_ephemeral(interaction, f"현재 판정 가능한 도전자는 {event['current_round']}번입니다.")
                return
            match = await self.db.get_match_by_slot(int(event["id"]), 1, int(slot))
            if not match or match["status"] != "monitoring":
                await _send_ephemeral(interaction, "해당 도전자의 판정 대기 경기를 찾지 못했습니다.")
                return
            if winner.id not in {int(match["player1_user_id"]), int(match["player2_user_id"])}:
                await _send_ephemeral(interaction, "승자는 해당 경기의 두 참가자 중 한 명이어야 합니다.")
                return
            loser_id = int(match["player2_user_id"] if winner.id == int(match["player1_user_id"]) else match["player1_user_id"])
            accepted = await self.db.mark_pending_ban(
                int(match["id"]),
                winner_user_id=winner.id,
                loser_user_id=loser_id,
                battle_key_value=f"manual:{match['id']}:{int(datetime.now(timezone.utc).timestamp())}",
                result_kind="admin_manual",
            )
            if not accepted:
                await _send_ephemeral(interaction, "이미 다른 처리에서 결과를 확정했습니다.")
                return
            await self.process_pending_match(int(match["id"]))
            await _send_ephemeral(
                interaction,
                f"관리자 판정으로 {match['slot_no']}번 도전자 경기 승자를 {winner.mention}로 확정했습니다. "
                f"DRY_RUN={str(self.settings.dry_run).lower()} 설정이 밴 실행 여부를 결정합니다.",
            )

        @app_commands.command(name="unban", description="관리자만 영구밴을 해제합니다. 대상의 Discord 사용자 ID를 입력하세요.")
        @app_commands.describe(user_id="밴을 해제할 Discord 사용자 ID", reason="밴 해제 사유(선택)")
        @app_commands.default_permissions(administrator=True)
        async def unban(interaction: discord.Interaction, user_id: str, reason: str = "관리자 요청") -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "영구밴 해제는 서버 관리자만 할 수 있습니다.")
                return
            if not user_id.isdecimal() or int(user_id) <= 0:
                await _send_ephemeral(interaction, "Discord 사용자 ID는 숫자로 입력해 주세요.")
                return
            guild = interaction.guild
            if guild is None:
                await _send_ephemeral(interaction, "서버 안에서만 사용할 수 있습니다.")
                return
            target_user_id = int(user_id)
            if await self.db.has_pending_tournament_ban(target_user_id):
                await _send_ephemeral(interaction, "결과가 확정된 밴 처리가 아직 대기 중입니다. 봇 권한을 확인하면 자동 재시도합니다.")
                return
            discord_unbanned = False
            try:
                await guild.unban(discord.Object(id=target_user_id), reason=f"Admin unban: {reason[:400]}")
                discord_unbanned = True
            except discord.NotFound:
                # A prior run may have removed the Discord ban but failed before clearing the tag denylist.
                pass
            except discord.Forbidden:
                await _send_ephemeral(interaction, "봇에 밴 해제 권한이 없습니다. BAN_MEMBERS 권한을 확인해 주세요.")
                return
            except discord.HTTPException:
                log.exception("Discord API failed while unbanning %s", user_id)
                await _send_ephemeral(interaction, "Discord 밴 해제 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.")
                return
            try:
                cleared_tags = await self.db.clear_tournament_bans(target_user_id)
            except Exception:
                log.exception("Discord unban succeeded but tournament denylist cleanup failed for %s", user_id)
                await _send_ephemeral(
                    interaction,
                    "Discord 밴은 해제했지만 Brawl 태그 밴 목록을 지우지 못했습니다. 같은 `/unban` 명령을 다시 실행해 주세요.",
                )
                return
            if not discord_unbanned and not cleared_tags:
                await _send_ephemeral(interaction, "Discord 밴이나 대회 태그 밴 기록이 없습니다.")
                return
            tag_note = f" 연결된 Brawl 태그 {', '.join(cleared_tags)}도 해제했습니다." if cleared_tags else ""
            await _send_ephemeral(interaction, f"사용자 `{user_id}`의 Discord 밴을 해제했습니다.{tag_note}")

        @app_commands.command(name="resend_winner_invite", description="보호 대상을 이긴 참가자에게 1회용 보조 서버 초대를 다시 보냅니다.")
        @app_commands.describe(user_id="초대를 다시 보낼 참가자의 Discord 사용자 ID")
        @app_commands.default_permissions(administrator=True)
        async def resend_winner_invite(interaction: discord.Interaction, user_id: str) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            if not user_id.isdecimal() or int(user_id) <= 0:
                await _send_ephemeral(interaction, "Discord 사용자 ID는 숫자로 입력해 주세요.")
                return
            winner_id = int(user_id)
            protected_id = await self.db.get_protected_user_id()
            if self.settings.winner_guild_id is None or protected_id is None:
                await _send_ephemeral(interaction, "WINNER_GUILD_ID와 보호 대상을 먼저 설정해야 합니다.")
                return
            if not await self.db.is_qualified_winner(winner_id, protected_id):
                await _send_ephemeral(interaction, "해당 계정은 현재 보호 대상을 이긴 기록이 없습니다.")
                return
            await self.db.update_winner_invite(winner_id, protected_id, status="pending")
            await self.send_winner_invite(winner_id, protected_id)
            record = await self.db.get_qualified_winner(winner_id, protected_id)
            if record and record["invite_status"] in {"sent", "joined"}:
                await _send_ephemeral(interaction, f"<@{winner_id}>에게 보조 서버 초대를 보냈습니다. (상태: {record['invite_status']})")
            else:
                details = record.get("failure_reason") if record else None
                fallback = f"\n초대 URL: {record['invite_url']}" if record and record.get("invite_url") else ""
                await _send_ephemeral(interaction, f"초대 전송에 실패했습니다. {details or ''}{fallback}"[:1900])

        @app_commands.command(name="audit_winner_server", description="관리자가 보조 서버에서 보호 대상을 이기지 않은 계정을 정리합니다.")
        @app_commands.describe(confirm="True로 설정하면 자격이 없는 기존 멤버를 서버에서 추방합니다.")
        @app_commands.default_permissions(administrator=True)
        async def audit_winner_server(interaction: discord.Interaction, confirm: bool = False) -> None:
            if not _is_admin(interaction):
                await _send_ephemeral(interaction, "이 명령은 서버 관리자만 사용할 수 있습니다.")
                return
            if self.settings.winner_guild_id is None:
                await _send_ephemeral(interaction, "WINNER_GUILD_ID가 설정되지 않았습니다.")
                return
            if not confirm:
                await _send_ephemeral(interaction, "실행하려면 `confirm: True`로 다시 호출해 주세요. 미승인 기존 멤버는 추방됩니다.")
                return
            target_guild = self.get_guild(self.settings.winner_guild_id)
            if target_guild is None:
                await _send_ephemeral(interaction, "봇이 보조 서버에 들어가 있지 않거나 서버 ID가 잘못되었습니다.")
                return
            protected_id = await self.db.get_protected_user_id()
            if protected_id is None:
                await _send_ephemeral(interaction, "먼저 메인 서버에서 /set_protected 또는 /set_protected_id를 설정해 주세요.")
                return
            removed = 0
            kept = 0
            failed = 0
            async for member in target_guild.fetch_members(limit=None):
                if self.user and member.id == self.user.id:
                    kept += 1
                    continue
                if member.id == target_guild.owner_id or member.id in self.settings.winner_server_staff_ids:
                    kept += 1
                    continue
                if await self.qualified_sources_for_destination(target_guild.id, member.id):
                    kept += 1
                    continue
                try:
                    await member.kick(reason="Winners-only server audit: not recorded as a winner against the protected player.")
                    removed += 1
                except discord.Forbidden:
                    failed += 1
                except discord.HTTPException:
                    failed += 1
            await _send_ephemeral(interaction, f"보조 서버 점검 완료: 추방 {removed}명, 유지 {kept}명, 실패 {failed}명.")

        commands_to_add = (
            register,
            my_tag,
            leave_queue,
            set_protected,
            set_protected_id,
            configure_servers,
            set_auto_ban,
            set_winner_server,
            phone_verification_status,
            enable_phone_verification,
            open_event,
            event_status,
            cancel_event,
            resolve_match,
            unban,
            resend_winner_invite,
            audit_winner_server,
        )
        for command in commands_to_add:
            self.tree.add_command(command)

    async def _fetch_channel(self, channel_id: int) -> Any:
        channel = self.get_channel(channel_id)
        if channel is not None:
            return channel
        return await self.fetch_channel(channel_id)

    async def open_queue(self, *, event_key: str | None, event_date: date) -> int:
        protected_id = await self.db.get_protected_user_id()
        protected_tag = await self.db.get_protected_brawl_tag()
        if protected_id is None or protected_tag is None:
            raise ValueError("먼저 관리자가 /set_protected 또는 /set_protected_id로 보호 Discord 계정과 브롤 태그를 설정해야 합니다.")
        source_guild = self.get_guild(self.settings.guild_id)
        if source_guild is None or source_guild.verification_level != discord.VerificationLevel.highest:
            raise ValueError("전화번호 인증을 강제하려면 메인 서버 Verification Level을 Highest로 설정해야 합니다.")
        event_id = await self.db.create_event(
            event_key=event_key,
            event_date=event_date.isoformat(),
            queue_channel_id=self.settings.queue_channel_id,
            result_channel_id=self.settings.result_channel_id,
        )
        try:
            channel = await self._fetch_channel(self.settings.queue_channel_id)
            embed = discord.Embed(
                title="보호 대상 1대1 챌린지 — 도전자 모집",
                description=(
                    "브롤 태그를 등록하고 **오늘 참가하기**를 눌러 주세요.\n"
                    "도전자 10명이 모이면 보호 대상이 1번부터 10번까지 순서대로 각각 별도의 바운티 1대1 경기를 합니다.\n"
                    "도전자끼리는 경기하지 않습니다. 무승부·불명확한 기록은 밴하지 않습니다."
                ),
                color=discord.Color.gold(),
                timestamp=datetime.now(timezone.utc),
            )
            embed.add_field(name="참가 인원", value="0 / 10", inline=True)
            embed.add_field(
                name="자동 밴 모드",
                value="시험 모드(DRY_RUN)" if self.settings.dry_run else "영구 밴 활성화",
                inline=True,
            )
            message = await channel.send(embed=embed, view=DailyQueueView(self))
            await self.db.set_queue_message_id(event_id, message.id)
        except Exception:
            log.exception("Failed to post queue panel for event %s", event_id)
            try:
                await self.db.cancel_event(event_id)
            except Exception:
                log.exception("Could not cancel event after posting failure")
            raise
        log.info("Opened event %s for %s", event_id, event_date.isoformat())
        return event_id

    async def refresh_queue_message(self, event_id: int) -> None:
        event = await self.db.get_event(event_id)
        if not event or not event.get("queue_message_id"):
            return
        try:
            channel = await self._fetch_channel(int(event["queue_channel_id"]))
            message = await channel.fetch_message(int(event["queue_message_id"]))
        except (discord.NotFound, discord.Forbidden, discord.HTTPException):
            log.warning("Queue message for event %s could not be fetched", event_id)
            return
        players = await self.db.get_event_players(event_id)
        state = event["status"]
        if state == "open":
            title = "보호 대상 1대1 챌린지 — 도전자 모집"
            description = "브롤 태그를 등록하고 버튼을 눌러 참가하세요. 10명이 모이면 보호 대상과 순번대로 각각 별도의 바운티 1대1 경기를 합니다."
            view = DailyQueueView(self)
            for child in view.children:
                if isinstance(child, discord.ui.Button):
                    child.disabled = False
        elif state == "active":
            title = "참가 마감 — 순차 1대1 진행 중"
            description = f"보호 대상이 **{event['current_round']}번 도전자**와 경기 중입니다. 결과 처리 후 다음 순번으로 진행합니다."
            view = DailyQueueView(self)
            for child in view.children:
                if isinstance(child, discord.ui.Button):
                    child.disabled = True
        else:
            title = "이벤트 종료"
            description = f"이 이벤트는 **{state}** 상태입니다."
            view = DailyQueueView(self)
            for child in view.children:
                if isinstance(child, discord.ui.Button):
                    child.disabled = True
        roster = "\n".join(
            f"**{player['seed']}번** · <@{player['user_id']}> · {player['player_name']} ({player['player_tag']})"
            for player in players
        ) or "아직 참가자가 없습니다."
        embed = discord.Embed(title=title, description=description, color=discord.Color.gold())
        embed.add_field(name="참가 인원", value=f"{len(players)} / 10", inline=True)
        embed.add_field(name="현재 모드", value="시험 모드" if self.settings.dry_run else "영구 밴 활성화", inline=True)
        embed.add_field(name="순번", value=roster[:1024], inline=False)
        await message.edit(embed=embed, view=view)

    async def announce_challenge(self, event_id: int, challenge_no: int) -> None:
        event = await self.db.get_event(event_id)
        if not event:
            return
        match = await self.db.get_match_by_slot(event_id, 1, challenge_no)
        if not match or match["status"] != "monitoring":
            return
        description = (
            f"**도전자 {challenge_no}/10**\n"
            f"보호 대상 <@{match['player1_user_id']}> ({match['name1']} / {match['tag1']}) "
            f"vs 도전자 <@{match['player2_user_id']}> ({match['name2']} / {match['tag2']})\n\n"
            "이번 경기가 확정된 뒤에만 다음 순번의 경기를 시작합니다."
        )
        mode_text = "시험 모드: 밴 없이 결과만 기록" if self.settings.dry_run else "보호 대상에게 진 도전자는 결과 확인 후 영구 밴됩니다."
        embed = discord.Embed(
            title=f"바운티 1대1 — {challenge_no}번 도전자",
            description=description,
            color=discord.Color.blurple(),
        )
        embed.set_footer(text=f"모드: Bounty only · 감지: {', '.join(self.settings.allowed_modes)} · {mode_text}")
        await self._send_to_channel(int(event["result_channel_id"]), embed=embed)

    async def _send_to_channel(self, channel_id: int, *, content: str | None = None, embed: discord.Embed | None = None) -> None:
        try:
            channel = await self._fetch_channel(channel_id)
            await channel.send(
                content=content,
                embed=embed,
                allowed_mentions=discord.AllowedMentions.none(),
            )
        except (discord.NotFound, discord.Forbidden, discord.HTTPException):
            log.exception("Could not send message to channel %s", channel_id)

    async def _daily_scheduler(self) -> None:
        await self.wait_until_ready()
        zone = self._base_settings.time_zone
        while not self.is_closed():
            now = datetime.now(zone)
            today = now.date()
            scheduled_today = datetime.combine(
                today,
                time(self._base_settings.daily_open_hour, self._base_settings.daily_open_minute),
                tzinfo=zone,
            )
            if now >= scheduled_today:
                try:
                    guild_ids = await self.db.get_configured_guild_ids()
                except Exception:
                    log.exception("Could not enumerate configured guilds for the daily scheduler")
                    guild_ids = []
                for guild_id in guild_ids:
                    if self.get_guild(guild_id) is None or self._daily_attempted.get(guild_id) == today:
                        continue
                    settings = await self.ensure_guild_settings(guild_id)
                    if settings.queue_channel_id is None or settings.result_channel_id is None:
                        continue
                    with self.db.guild_context(guild_id):
                        try:
                            await self._open_daily_event(today)
                        except Exception:
                            log.exception("Could not open daily event for guild %s", guild_id)
                    self._daily_attempted[guild_id] = today

            now = datetime.now(zone)
            next_target = datetime.combine(
                now.date(),
                time(self._base_settings.daily_open_hour, self._base_settings.daily_open_minute),
                tzinfo=zone,
            )
            if next_target <= now:
                next_target += timedelta(days=1)
            # Re-check periodically so a newly configured guild is picked up without a restart.
            await asyncio.sleep(max(1.0, min(60.0, (next_target - now).total_seconds())))

    async def _open_daily_event(self, event_date: date) -> None:
        guild_id = self.settings.guild_id
        if guild_id is None or self.settings.result_channel_id is None:
            return
        event_key = f"daily-{guild_id}-{event_date.isoformat()}"
        if await self.db.get_event_by_key(event_key):
            return
        legacy_event = await self.db.get_event_by_key(f"daily-{event_date.isoformat()}")
        if legacy_event and int(legacy_event.get("guild_id", 0)) == guild_id:
            return
        cancelled = await self.db.cancel_stale_open_events(event_date.isoformat())
        for event_id in cancelled:
            log.info("Cancelled stale open queue %s for guild %s", event_id, guild_id)

        active = await self.db.get_active_event()
        if active:
            await self._send_to_channel(
                self.settings.result_channel_id,
                content=(
                    f"오늘 자동 모집을 건너뛰었습니다. 이전 이벤트 #{active['id']}가 아직 **{active['status']}** 상태입니다. "
                    "관리자가 /event_status 또는 /cancel_event로 확인해 주세요."
                ),
            )
            return
        if await self.db.get_protected_user_id() is None or await self.db.get_protected_brawl_tag() is None:
            await self._send_to_channel(
                self.settings.result_channel_id,
                content="오늘 참가 모집을 열지 않았습니다. 관리자가 /set_protected 또는 /set_protected_id로 보호 Discord 계정과 브롤 태그를 먼저 설정해 주세요.",
            )
            return
        try:
            await self.open_queue(event_key=event_key, event_date=event_date)
        except ValueError as exc:
            await self._send_to_channel(
                self.settings.result_channel_id,
                content=f"오늘 모집을 열지 않았습니다: {exc}",
            )
        except Exception:
            log.exception("Could not create daily event for guild %s on %s", guild_id, event_date.isoformat())

    async def _match_poller(self) -> None:
        await self.wait_until_ready()
        while not self.is_closed():
            try:
                guild_ids = await self.db.get_configured_guild_ids()
                active_matches: list[dict[str, Any]] = []
                for guild_id in guild_ids:
                    if self.get_guild(guild_id) is None:
                        continue
                    await self.ensure_guild_settings(guild_id)
                    with self.db.guild_context(guild_id):
                        for pending in await self.db.get_pending_ban_matches():
                            await self.process_pending_match(int(pending["id"]))
                        protected_id = await self.db.get_protected_user_id()
                        if self.settings.winner_guild_id is not None and protected_id is not None:
                            for qualified in await self.db.get_pending_winner_invites(protected_id):
                                await self.send_winner_invite(int(qualified["user_id"]), protected_id)
                        active_matches.extend(await self.db.get_active_matches())
                await self._poll_active_matches(active_matches)
            except asyncio.CancelledError:
                raise
            except Exception:
                log.exception("Unexpected error in battle-log poller")
            await asyncio.sleep(self._base_settings.match_poll_seconds)

    async def _poll_active_matches(self, matches: list[dict[str, Any]] | None = None) -> None:
        if matches is None:
            matches = []
            for guild_id in await self.db.get_configured_guild_ids():
                await self.ensure_guild_settings(guild_id)
                with self.db.guild_context(guild_id):
                    matches.extend(await self.db.get_active_matches())
        if not matches:
            return

        tags = sorted({normalize_tag(str(match[key])) for match in matches for key in ("tag1", "tag2")})
        semaphore = asyncio.Semaphore(3)
        logs_by_tag: dict[str, list[dict[str, Any]]] = {}

        async def fetch_log(tag: str) -> None:
            async with semaphore:
                try:
                    logs_by_tag[tag] = await self.brawl_api.get_battle_log(tag)
                except BrawlAPIError as exc:
                    log.warning("Battle-log lookup failed for %s: %s", tag, exc)
                except Exception:
                    log.exception("Unexpected battle-log lookup failure for %s", tag)

        await asyncio.gather(*(fetch_log(tag) for tag in tags))

        for match in matches:
            guild_id = int(match["guild_id"])
            await self.ensure_guild_settings(guild_id)
            with self.db.guild_context(guild_id):
                tag1 = normalize_tag(str(match["tag1"]))
                tag2 = normalize_tag(str(match["tag2"]))
                if tag1 not in logs_by_tag or tag2 not in logs_by_tag:
                    continue
                ignored = await self.db.get_ignored_battle_keys(int(match["id"]))
                decision = evaluate_pair_logs(
                    tag1,
                    tag2,
                    logs_by_tag[tag1],
                    logs_by_tag[tag2],
                    started_at=str(match["started_at"]),
                    allowed_modes=self.settings.allowed_modes,
                    ignored_battle_keys=ignored,
                )
                if decision is None:
                    continue
                if decision.kind == "draw":
                    await self.db.ignore_battle(int(match["id"]), decision.battle_key, "draw")
                    await self._announce_draw(match, decision)
                    continue
                if decision.kind != "decisive" or not decision.winner_tag or not decision.loser_tag:
                    continue

                tag_to_user = {tag1: int(match["player1_user_id"]), tag2: int(match["player2_user_id"])}
                winner_id = tag_to_user.get(normalize_tag(decision.winner_tag))
                loser_id = tag_to_user.get(normalize_tag(decision.loser_tag))
                if winner_id is None or loser_id is None or winner_id == loser_id:
                    log.error("Could not map API decision to participants for match %s", match["id"])
                    continue
                accepted = await self.db.mark_pending_ban(
                    int(match["id"]),
                    winner_user_id=winner_id,
                    loser_user_id=loser_id,
                    battle_key_value=decision.battle_key,
                    result_kind="api",
                )
                if accepted:
                    await self.process_pending_match(int(match["id"]), decision=decision)

    async def _announce_draw(self, match: dict[str, Any], decision: BattleDecision) -> None:
        await self._send_to_channel(
            int(match["result_channel_id"]),
            content=(
                f"{match['slot_no']}번 도전자 경기에서 무승부가 확인됐습니다. "
                "아무도 밴하지 않았으며, 결과가 확정될 때까지 다음 도전자 경기는 시작하지 않습니다. 재경기해 주세요. "
                f"(모드: {decision.mode}, 맵: {decision.map_name or '정보 없음'})"
            ),
        )

    async def send_winner_invite(self, user_id: int, protected_user_id: int) -> None:
        """DM a one-use invite to a verified winner; Discord requires the user to accept it."""
        if self.settings.winner_guild_id is None or self.settings.winner_invite_channel_id is None:
            return
        record = await self.db.get_qualified_winner(user_id, protected_user_id)
        if not record or record["invite_status"] in {"sent", "joined"}:
            return

        target_guild = self.get_guild(self.settings.winner_guild_id)
        if target_guild is None:
            await self.db.update_winner_invite(
                user_id,
                protected_user_id,
                status="failed",
                failure_reason="봇이 설정된 보조 서버에 없습니다.",
            )
            return

        member = target_guild.get_member(user_id)
        if member is None:
            try:
                member = await target_guild.fetch_member(user_id)
            except discord.NotFound:
                member = None
            except discord.Forbidden:
                await self.db.update_winner_invite(
                    user_id,
                    protected_user_id,
                    status="failed",
                    failure_reason="보조 서버 멤버 조회 권한이 없습니다.",
                )
                return
            except discord.HTTPException:
                log.exception("Could not check whether winner %s already joined the destination guild", user_id)
                return
        if member is not None:
            await self.db.mark_winner_joined(user_id, protected_user_id)
            return

        invite_url: str | None = None
        try:
            channel = await self._fetch_channel(self.settings.winner_invite_channel_id)
            channel_guild = getattr(channel, "guild", None)
            if channel_guild is None or channel_guild.id != self.settings.winner_guild_id:
                raise ValueError("WINNER_INVITE_CHANNEL_ID is not in WINNER_GUILD_ID")
            create_invite = getattr(channel, "create_invite", None)
            if not callable(create_invite):
                raise ValueError("WINNER_INVITE_CHANNEL_ID must point to a channel that supports invites")
            invite = await create_invite(
                max_age=604800,
                max_uses=1,
                unique=True,
                reason=f"Qualified by defeating protected player {protected_user_id}",
            )
            invite_url = invite.url
            user = self.get_user(user_id) or await self.fetch_user(user_id)
            await user.send(
                "보호 대상과의 브롤스타즈 바운티 경기에서 승리해 보조 서버 초대를 받았습니다. "
                "이 링크는 1회용이며 7일 후 만료됩니다. 직접 눌러 참가해야 합니다.\n"
                f"{invite.url}",
                allowed_mentions=discord.AllowedMentions.none(),
            )
            await self.db.update_winner_invite(
                user_id,
                protected_user_id,
                status="sent",
                invite_url=invite.url,
                failure_reason=None,
            )
            log.info("Sent one-use destination invite to qualified player %s", user_id)
        except discord.Forbidden:
            await self.db.update_winner_invite(
                user_id,
                protected_user_id,
                status="failed",
                invite_url=invite_url,
                failure_reason="DM 또는 초대 링크 생성이 차단되었습니다. 관리자에게 DM을 열거나 권한을 확인해 주세요.",
            )
        except ValueError as exc:
            await self.db.update_winner_invite(
                user_id,
                protected_user_id,
                status="failed",
                failure_reason=str(exc),
            )
        except discord.HTTPException:
            log.exception("Could not create or send destination invite for qualified player %s", user_id)
            await self.db.update_winner_invite(
                user_id,
                protected_user_id,
                status="failed",
                invite_url=invite_url,
                failure_reason="Discord API 요청에 실패했습니다. 관리자 명령으로 초대를 다시 보내 주세요.",
            )
        except Exception:
            log.exception("Unexpected error while delivering destination invite to qualified player %s", user_id)
            await self.db.update_winner_invite(
                user_id,
                protected_user_id,
                status="failed",
                invite_url=invite_url,
                failure_reason="초대 처리 중 예기치 않은 오류가 발생했습니다. 관리자 명령으로 다시 시도해 주세요.",
            )

    async def process_pending_match(self, match_id: int, *, decision: BattleDecision | None = None) -> None:
        match = await self.db.get_match(match_id)
        if not match or match["status"] != "pending_ban":
            return
        loser_id = int(match["loser_user_id"])
        winner_id = int(match["winner_user_id"])
        protected_id = await self.db.get_protected_user_id()
        protected_tag = await self.db.get_protected_brawl_tag()
        loser_tag = normalize_tag(str(match["tag1"] if loser_id == int(match["player1_user_id"]) else match["tag2"]))
        protected_tag_loser = protected_tag is not None and loser_tag == protected_tag
        protected_loser = protected_id == loser_id or protected_tag_loser
        protected_win_qualification = protected_id is not None and protected_tag_loser
        ban_status = "pending"
        failure_reason: str | None = None

        if protected_loser:
            ban_status = "protected"
        elif self.settings.dry_run:
            ban_status = "dry_run"
        else:
            await self.db.add_tournament_ban(
                loser_id,
                loser_tag,
                event_id=int(match["event_id"]),
                match_id=match_id,
            )
            guild = self.get_guild(int(match["guild_id"]))
            if guild is None:
                log.error("Guild %s unavailable while processing match %s", match["guild_id"], match_id)
                return
            target = discord.Object(id=loser_id)
            try:
                try:
                    await guild.fetch_ban(target)
                    # A previous request may have succeeded just before a crash.
                    ban_status = "banned"
                except discord.NotFound:
                    reason = (
                        f"Brawl Stars protected-target challenge #{match['slot_no']}; "
                        f"winner Discord ID {winner_id}; result {match['result_kind']}"
                    )
                    await guild.ban(target, reason=reason[:480], delete_message_seconds=0)
                    ban_status = "banned"
            except discord.Forbidden:
                # Keep this challenge pending until the required permanent ban succeeds.
                log.error("Cannot ban loser %s; check BAN_MEMBERS and bot role hierarchy", loser_id)
                return
            except discord.HTTPException as exc:
                # Do not advance while Discord has not confirmed the required permanent ban.
                log.warning("Discord API error while banning %s; will retry: %s", loser_id, exc)
                return
            except Exception:
                log.exception("Unexpected error while banning Discord user %s", loser_id)
                return

        progression = await self.db.complete_pending_match(
            match_id,
            ban_status=ban_status,
            protected_loser=protected_loser,
            protected_user_id=protected_id,
            protected_brawl_tag=protected_tag,
            protected_win_qualification=protected_win_qualification,
            failure_reason=failure_reason,
        )
        if protected_win_qualification and protected_id is not None:
            await self.send_winner_invite(winner_id, protected_id)
        if progression is None:
            completed = await self.db.get_match(match_id)
            if completed and completed["status"] == "completed":
                await self._announce_match_result(completed, decision)
            return

        completed = await self.db.get_match(match_id)
        if completed:
            await self._announce_match_result(completed, decision)
        if "next_challenge_no" in progression:
            await self.announce_challenge(int(match["event_id"]), int(progression["next_challenge_no"]))
        elif progression.get("event_completed"):
            await self._send_to_channel(
                int(match["result_channel_id"]),
                content=(
                    "✅ 보호 대상과 10명의 도전자 간 순차 1대1 경기가 모두 끝났습니다. "
                    "도전자끼리의 경기는 없으며, 보호 대상에게 이긴 도전자만 보조 서버 자격을 받습니다."
                ),
            )
        await self.refresh_queue_message(int(match["event_id"]))

    async def _announce_match_result(self, match: dict[str, Any], decision: BattleDecision | None) -> None:
        winner_id = int(match["winner_user_id"])
        loser_id = int(match["loser_user_id"])
        status = str(match.get("ban_status") or "unknown")
        if status == "banned":
            action = f"<@{loser_id}>을(를) **영구 밴**했습니다."
        elif status == "protected":
            if match.get("winner_qualified"):
                invite_note = "승자에게 보조 서버 1회용 초대를 보냅니다." if self.settings.winner_guild_id else "WINNER_GUILD_ID가 없어 보조 서버 초대는 설정 후 재전송할 수 있습니다."
            else:
                invite_note = "고정 보호 브롤 태그와 경기 기록이 일치하지 않아 승자 자격은 부여하지 않았습니다."
            action = f"<@{loser_id}>은 보호 대상이므로 밴하지 않았습니다. 이 도전자의 결과만 기록하고 다음 순번으로 진행합니다. {invite_note}"
        elif status == "dry_run":
            action = f"시험 모드: <@{loser_id}>을(를) 밴할 상황이지만 실제 밴은 하지 않았습니다."
        elif status == "failed":
            action = f"⚠️ <@{loser_id}> 밴에 실패했습니다. 관리자 확인이 필요합니다. ({match.get('failure_reason') or '권한 오류'})"
        else:
            action = f"<@{loser_id}>의 밴 상태를 확인해 주세요."
        suffix = ""
        if decision is not None:
            suffix = f" · {decision.mode} / {decision.map_name or '맵 정보 없음'}"
        await self._send_to_channel(
            int(match["result_channel_id"]),
            content=(
                f"{match['slot_no']}번 도전자 경기 결과 확정: 승자 <@{winner_id}>. "
                f"{action}{suffix}"
            ),
        )

def main() -> None:
    try:
        settings = load_settings()
    except Exception as exc:
        raise SystemExit(f"Configuration error: {exc}") from exc
    bot = BrawlChallengeBot(settings)
    bot.run(settings.discord_token, log_handler=None)


if __name__ == "__main__":
    main()
