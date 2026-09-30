from __future__ import annotations

from typing import Any
from urllib.parse import quote

import aiohttp


BASE_URL = "https://api.brawlstars.com/v1"
VALID_TAG_CHARACTERS = frozenset("0289PYLQGRJCUV")


class BrawlAPIError(RuntimeError):
    def __init__(self, message: str, *, status: int | None = None, retry_after: float | None = None):
        super().__init__(message)
        self.status = status
        self.retry_after = retry_after


def normalize_tag(raw_tag: str) -> str:
    """Return a canonical Brawl Stars tag (with #) or raise ValueError."""
    tag = raw_tag.strip().upper()
    if tag.startswith("#"):
        tag = tag[1:]
    if not 3 <= len(tag) <= 15 or any(character not in VALID_TAG_CHARACTERS for character in tag):
        raise ValueError("유효하지 않은 브롤 태그입니다. #을 제외한 태그를 확인해 주세요.")
    return f"#{tag}"


class BrawlStarsAPI:
    """Small async client for Supercell's official Brawl Stars API."""

    def __init__(self, api_token: str, *, timeout_seconds: float = 15.0) -> None:
        self.api_token = api_token
        self.timeout = aiohttp.ClientTimeout(total=timeout_seconds)
        self.session: aiohttp.ClientSession | None = None

    async def start(self) -> None:
        if self.session is None or self.session.closed:
            self.session = aiohttp.ClientSession(timeout=self.timeout)

    async def close(self) -> None:
        if self.session is not None and not self.session.closed:
            await self.session.close()

    async def _get(self, path: str) -> dict[str, Any]:
        if self.session is None or self.session.closed:
            await self.start()
        assert self.session is not None

        url = f"{BASE_URL}{path}"
        headers = {
            "Accept": "application/json",
            "Authorization": f"Bearer {self.api_token}",
        }
        try:
            async with self.session.get(url, headers=headers) as response:
                if response.status == 429:
                    retry_after_raw = response.headers.get("Retry-After", "")
                    try:
                        retry_after = float(retry_after_raw)
                    except ValueError:
                        retry_after = None
                    raise BrawlAPIError(
                        "브롤 API 요청 제한(429)에 걸렸습니다.",
                        status=429,
                        retry_after=retry_after,
                    )
                if response.status == 401 or response.status == 403:
                    raise BrawlAPIError(
                        "브롤 API 인증에 실패했습니다. API 키와 허용 IP 설정을 확인해 주세요.",
                        status=response.status,
                    )
                if response.status == 404:
                    raise BrawlAPIError("해당 브롤 태그를 찾을 수 없습니다.", status=404)
                if response.status >= 500:
                    raise BrawlAPIError(
                        f"브롤 API 서버 오류({response.status})입니다.",
                        status=response.status,
                    )
                if response.status != 200:
                    # Don't include request headers or tokens in logs/messages.
                    raise BrawlAPIError(
                        f"브롤 API가 요청을 거부했습니다. (HTTP {response.status})",
                        status=response.status,
                    )
                try:
                    payload = await response.json(content_type=None)
                except (aiohttp.ContentTypeError, ValueError) as exc:
                    raise BrawlAPIError("브롤 API 응답을 JSON으로 읽지 못했습니다.") from exc
                if not isinstance(payload, dict):
                    raise BrawlAPIError("브롤 API 응답 형식이 예상과 다릅니다.")
                return payload
        except (aiohttp.ClientError, TimeoutError) as exc:
            raise BrawlAPIError("브롤 API에 연결할 수 없습니다.") from exc

    async def get_player(self, raw_tag: str) -> dict[str, Any]:
        tag = normalize_tag(raw_tag)
        encoded_tag = quote(tag, safe="")
        return await self._get(f"/players/{encoded_tag}")

    async def get_battle_log(self, raw_tag: str) -> list[dict[str, Any]]:
        tag = normalize_tag(raw_tag)
        encoded_tag = quote(tag, safe="")
        payload = await self._get(f"/players/{encoded_tag}/battlelog")
        items = payload.get("items", [])
        if not isinstance(items, list):
            raise BrawlAPIError("브롤 배틀로그 응답 형식이 예상과 다릅니다.")
        return [item for item in items if isinstance(item, dict)]
