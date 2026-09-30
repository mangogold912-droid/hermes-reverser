# 브롤스타즈 바운티 10인 탈락전 디스코드 봇

매일 10명이 참가하면 순번대로 대진을 만들고, **바운티 모드의 실제 1대1 경기**에서 결과가 확인된 즉시 패자를 메인 서버에서 영구 밴하는 봇입니다. 밴 목록에는 Discord ID와 Brawl 태그를 함께 기록해 같은 태그를 다른 Discord 계정으로 등록하는 것도 차단합니다. 보호 대상은 패배해도 밴하지 않으며, 보호 대상을 직접 이긴 참가자는 별도 서버의 1회용 초대 링크를 DM으로 받습니다.

> **실제 밴은 기본적으로 꺼져 있습니다.** 먼저 `DRY_RUN=true`로 시험하고, 동작을 확인한 뒤 `.env`에서 `DRY_RUN=false`로 바꿔야 자동 영구 밴이 실행됩니다.

## 요청한 규칙

1. 기본 서울 시간 18:00에 메인 서버의 모집 채널에 참가 버튼을 게시합니다. 10명이 버튼을 누르면 신청이 닫힙니다. 서버에 그냥 가입한 사람을 자동 참가자로 세지는 않습니다.
2. 참가자는 `/register #태그`로 등록합니다. API로 태그가 유효한지 확인한 뒤 **관리자 승인 없이 바로 참가할 수 있습니다.**
3. 버튼을 누른 순서대로 1~10번을 받고, 1라운드는 `1 vs 2`, `3 vs 4` … `9 vs 10`입니다. 승자는 다음 라운드로 진출하고, 인원이 홀수인 라운드는 마지막 순번이 부전승합니다.
4. 경기 감지는 **Bounty 모드로 고정**되어 있습니다. 두 참가자의 태그가 동일한 배틀로그에 있어야 하고, 서로 반대편이어야 하며, API 기록의 양쪽 결과가 승리/패배로 일치해야 합니다. 또한 양 팀이 한 명씩인 기록만 1대1로 처리합니다. 일반 3대3 바운티는 판정하지 않습니다.
5. 무승부, 기록 누락, 팀 구성 불일치, API 결과 충돌은 자동 밴하지 않습니다. API로 결과를 확인할 수 없는 경기는 관리자가 `/resolve_match`로 직접 확정할 수 있습니다.
6. 승부가 확정되면 봇이 바로 처리합니다. 실제 Discord 영구 밴이 켜져 있으면 패자를 메인 서버에서 밴합니다. 보호 대상이 패자라면 **보호 우선**으로 밴하지 않고 대진에서만 탈락시킵니다.
7. 보호 대상을 직접 이긴 승자는 보조 서버 입장 자격에 기록되고, 설정된 경우 7일 동안 유효한 **사용 횟수 1회** 초대를 DM으로 받습니다. 그 승자가 이후 다른 경기에서 패해 메인 서버에서 밴되더라도 보조 서버 자격은 유지됩니다.

## 보조 서버에 “추가”되는 방식의 제한

Discord는 봇이 다른 서버에 사용자를 **강제로 가입시킬 수 없습니다.** 초대를 받은 사용자가 직접 링크를 눌러 참가해야 합니다. 이 봇은 그 대신 다음을 합니다.

- 보호 대상을 이긴 Discord 사용자 ID만 자격 목록에 저장합니다.
- 보조 서버에 봇을 먼저 초대한 뒤 관리자가 `/set_winner_server guild_id invite_channel_id`를 실행하거나 `WINNER_GUILD_ID`와 `WINNER_INVITE_CHANNEL_ID`를 설정하면, 자격이 있는 승자에게만 1회용 초대 링크를 DM합니다. 슬래시 명령으로 설정한 서버/채널은 SQLite에 저장되어 재시작 후에도 유지됩니다.
- 해당 보조 서버에 새로 들어온 사람은 자격 목록과 대조합니다. 보호 대상을 이긴 기록이 없는 사람은 서버에서 추방합니다.
- 이미 들어와 있던 계정은 관리자가 `/audit_winner_server confirm:true`를 실행하면 자격 없는 계정을 정리합니다.
- 이전 봇 버전에서 고정 보호 Brawl 태그 없이 저장된 승자 기록은 안전을 위해 유효 자격으로 인정하지 않습니다. 해당 사용자는 업데이트 후 보호 대상과 다시 경기해 승리해야 자격이 복구됩니다.

Discord 초대 링크 자체는 특정 계정에 묶이지 않습니다. 다른 사람이 먼저 링크를 사용하면 그 사람은 자격 확인 후 추방되고, 초대가 소진될 수 있습니다. 관리자는 `/resend_winner_invite user_id`로 승자에게 새 초대를 보낼 수 있습니다. 보조 서버 소유자와 `WINNER_SERVER_STAFF_IDS`에 지정된 운영자는 서버 관리용 예외입니다. **보조 서버를 승자 외 사용자에게 공개하지 않으려면 다른 초대 링크도 제한하세요.**

## 태그 등록 관련 주의

태그 조회는 계정의 존재를 확인할 뿐, 해당 계정이 Discord 사용자 본인의 것인지는 증명하지 않습니다. 요청대로 참가자 태그의 관리자 승인을 없앴기 때문에 사용자가 타인의 태그를 등록할 위험이 있습니다. 그 경우 잘못된 Discord 계정이 경기 결과와 연결될 수 있습니다. 태그 소유권을 확실히 확인해야 한다면 일반 참가자 등록에 관리자 검수가 필요합니다. 봇은 같은 Brawl 태그를 두 Discord 계정에 중복 등록하는 것은 막습니다.

고정 보호 대상은 예외적으로 관리자가 Discord 계정과 Brawl 태그를 함께 지정합니다. `/set_protected member tag`는 태그를 Supercell API로 확인하고 그 연결을 고정하므로, 보호 대상으로 지정된 Discord 계정이 임의의 다른 Brawl 태그를 등록해 보호 규칙이나 승자 자격을 잘못 적용하는 일을 막습니다. 경기 결과 처리에서는 이 Discord ID **또는** 고정 보호 Brawl 태그가 패자로 감지되면 밴하지 않습니다. 보조 서버 자격은 고정 Brawl 태그가 실제 패배자로 확인된 경기에서만 부여합니다.

### Discord ID·태그 차단과 전화 인증

실제 밴 모드에서는 패자의 Discord ID와 Brawl 태그를 별도의 영구 대회 차단 목록에 기록합니다. 따라서 Discord가 원래 계정을 밴해도 다른 Discord ID로 같은 Brawl 태그를 등록하거나 대기열에 들어갈 수 없습니다. 관리자 `/unban user_id`는 Discord 밴과 연결된 태그 차단을 함께 해제합니다. `DRY_RUN=true`에서는 테스트 계정을 영구 차단하지 않습니다.

봇은 Discord 계정의 전화번호를 읽거나 인증할 수 없고, 전화번호를 수집·저장하지 않습니다. 전화 인증은 Discord 서버의 기본 설정으로 강제해야 합니다. 메인 서버의 **Server Settings → Safety Setup → Verification Level → Highest**를 선택하면 Discord가 전화번호 인증이 완료되지 않은 계정의 서버 이용을 제한합니다. 봇은 이 수준이 아니면 모집을 열거나 참가 버튼을 받지 않습니다. `/phone_verification_status`로 현재 설정을 확인하고, 봇에 선택적으로 `Manage Server` 권한을 준 경우 `/enable_phone_verification confirm:true`로 켤 수 있습니다. 이 설정은 대회 참가자뿐 아니라 메인 서버 전체에 적용되며, 전화 인증만으로 동일인이 여러 계정을 쓰는 것을 완전히 증명하거나 막을 수는 없습니다.

## 경기 데이터

봇은 Brawlify 웹페이지를 스크래핑하지 않고 **Supercell 공식 Brawl Stars API의 플레이어 배틀로그**를 조회합니다. 기본 폴링 간격은 10초이며, 명확한 결과를 감지한 뒤 밴 처리를 시작합니다. API가 기록을 늦게 제공하거나 제한하면 실제 추방 시점도 늦어질 수 있습니다. 경기 중 봇을 계속 실행해 주세요.

## 필요한 것

- Python 3.11 이상 또는 Docker
- Discord 봇 애플리케이션/토큰
- Supercell Brawl Stars API 키: [developer.brawlstars.com](https://developer.brawlstars.com/)
- 메인 서버 ID와 모집 채널 ID
- 보호 대상 Discord ID와 해당 계정의 고정 Brawl 태그
- 보조 서버 ID와 초대 채널 ID (보조 서버 초대를 사용할 때)

API 키가 허용 IP를 요구하면 봇이 실행되는 서버의 외부 IP를 등록하세요. 토큰/API 키는 채팅이나 저장소에 올리지 말고 로컬 `.env`에만 입력하세요.

## Discord 설정과 권한

Discord Developer Portal에서 봇을 만들고 OAuth2 초대 링크에 `bot`, `applications.commands` scope를 넣습니다. 봇을 **메인 서버와 보조 서버 양쪽에 초대**해야 합니다. `Server Members Intent`를 Developer Portal에서 켜야 보조 서버 입장자를 확인할 수 있습니다.

필요 권한:

- 메인 서버: View Channel, Send Messages, Embed Links, Read Message History, **Ban Members**. `/enable_phone_verification`을 쓸 경우에만 **Manage Server**도 필요합니다.
- 보조 서버: View Channel, **Create Instant Invite**, **Kick Members**
- 봇 역할은 메인 서버 참가자 및 보조 서버에서 정리할 계정보다 위에 둡니다.
- `Administrator` 권한은 봇에 주지 마세요.

> `/unban` 명령은 Discord `Administrator` 권한이 있는 사용자만 사용할 수 있습니다. 하지만 Discord 자체 권한상 `Ban Members`를 가진 사람은 Discord UI/API에서도 밴을 풀 수 있습니다. 메인 서버에서 관리자만 밴 해제할 수 있게 하려면, 사람 운영자에게는 `Ban Members`를 관리자에게만 부여하세요. 자동 밴을 위해 봇 자체에는 이 권한이 필요합니다.

## 설치와 실행

```bash
cd brawl-discord-bot
cp .env.example .env
# .env에 토큰, 서버/채널 ID, 보호 대상 Discord ID와 Brawl 태그를 입력
python -m venv .venv
source .venv/bin/activate       # Windows PowerShell: .venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python bot.py
```

Docker를 사용할 경우:

```bash
cd brawl-discord-bot
cp .env.example .env
# .env 입력 후
docker compose up --build -d
```

`.env`의 주요 항목:

- `DISCORD_BOT_TOKEN`, `BRAWL_STARS_API_TOKEN`
- `DISCORD_GUILD_ID`, `QUEUE_CHANNEL_ID`, 선택적 `RESULT_CHANNEL_ID`
- `PROTECTED_DISCORD_ID`와 `PROTECTED_BRAWL_TAG` (또는 실행 후 관리자가 `/set_protected member tag` 사용)
- 보호 계정은 처음 DB를 만들 때 `.env` 값으로 초기화됩니다. 이후에는 SQLite에 저장된 설정이 기준이므로 변경은 `/set_protected`로 하세요.
- `WINNER_GUILD_ID`, `WINNER_INVITE_CHANNEL_ID` (선택; 또는 관리자 `/set_winner_server`로 설정)
- `WINNER_SERVER_STAFF_IDS` (보조 서버에서 자격 검사에서 제외할 운영자 ID, 쉼표 구분)
- `DRY_RUN=true` (시험 모드), `ALLOWED_MODES=bounty` (다른 모드로 변경 불가)

보조 서버 설정을 비워 두면 경기 결과와 자격은 기록되지만 초대/입장 제한은 실행되지 않습니다. 대신 봇을 양쪽 서버에 초대한 뒤 `/set_winner_server`를 사용하면 재시작 없이 서버와 초대 채널을 저장할 수 있습니다. 기존 `.env` 설정은 DB가 비어 있을 때 기본값으로 복사됩니다.

## 명령어

| 명령 | 권한 | 설명 |
| --- | --- | --- |
| `/register tag` | 참가자 | 유효한 브롤 태그 등록; 관리자 승인 없이 참가 가능 |
| `/my_tag` | 참가자 | 내 태그 등록 상태 확인 |
| `/leave_queue` | 참가자 | 10명 마감 전 대기열에서 나가기; 남은 번호 재정렬 |
| `/set_protected member tag` | 관리자 | 보호 Discord 계정과 고정 Brawl 태그 설정; 이벤트 진행 중 변경 불가 |
| `/open_event` | 관리자 | 정기 시간 외에 모집 즉시 시작 |
| `/event_status` | 서버 멤버 | 모집 인원, 대진, 결과 감지 상태 확인 |
| `/resolve_match slot winner` | 관리자 | API 판정이 안 된 현재 라운드 경기의 승자 직접 확정 |
| `/cancel_event` | 관리자 | 진행 중 이벤트 취소; 밴 처리 중에는 취소 불가 |
| `/set_winner_server guild_id invite_channel_id` | 관리자 | 승자가 갈 보조 서버와 초대 채널 설정; 설정은 SQLite에 저장 |
| `/phone_verification_status` | 관리자 | Discord 서버의 전화번호 인증 요구 수준 확인 |
| `/enable_phone_verification confirm:true` | 관리자 | Discord Verification Level을 Highest로 설정 (서버 전체 적용; 봇에 Manage Server 필요) |
| `/unban user_id reason` | 관리자 | Discord 밴 및 연결된 Brawl 태그 차단 해제 |
| `/resend_winner_invite user_id` | 관리자 | 보호 대상을 이긴 기록이 있는 참가자에게 보조 서버 초대 재전송 |
| `/audit_winner_server confirm:true` | 관리자 | 보조 서버 기존 멤버 중 자격 없는 계정 정리 |

일일 모집 버튼은 설정된 채널에 자동으로 올라옵니다. **Discord 서버에 입장하는 것만으로는 브롤 태그를 알 수 없으므로**, 참가자는 `/register`와 참가 버튼을 사용해야 합니다. 현재 Discord 밴된 사용자는 신청할 수 없고, 대회 차단 목록은 패자의 Discord ID와 Brawl 태그 양쪽을 저장해 같은 태그의 대체 계정 등록도 거부합니다.

## 처음 설정할 때

1. `.env`에 메인 서버/채널, Discord 토큰, Supercell API 키, 보호 대상의 Discord ID와 고정 Brawl 태그를 입력합니다. 또는 봇 실행 후 `/set_protected member tag`로 설정합니다. 이 명령은 해당 태그를 API로 검증하고 계정 연결을 고정합니다.
2. 전화 인증을 요구하려면 메인 서버에서 Verification Level을 **Highest**로 설정하고 `/phone_verification_status`로 확인합니다. 간편 설정을 원하면 봇에 Manage Server 권한을 부여한 뒤 `/enable_phone_verification confirm:true`를 실행할 수 있습니다. 보조 서버를 사용할 경우 봇을 먼저 초대하고 `/set_winner_server guild_id invite_channel_id`를 실행합니다. 운영자가 자격자 외에도 남아야 한다면 ID를 `WINNER_SERVER_STAFF_IDS`에 설정합니다.
3. `DRY_RUN=true`로 태그 등록, 바운티 1대1 판정, 보호 대상 예외를 시험합니다. 이때 Discord 밴과 내부 ID/태그 차단 목록은 기록하지 않습니다. 다만 보조 서버 설정이 있으면 실제 초대 DM과 입장 제한은 계속 동작하므로 테스트 서버/계정으로 확인하세요.
4. 실제 메인 서버 영구 밴을 켤 때만 `DRY_RUN=false`로 바꾸고 봇을 재시작합니다.
5. 보조 서버의 기존 멤버를 정리할 필요가 있을 때만 `/audit_winner_server confirm:true`를 실행합니다. 이 명령은 자격자/소유자/지정 운영자가 아닌 기존 멤버를 추방하므로 서버 ID를 꼭 확인하세요.

## 안전·운영 주의사항

- 결과가 명확하고 바운티 1대1로 확인될 때만 자동 밴합니다. 모호한 결과에서 추측하지 않습니다.
- 실제 밴 권한/역할 순서 오류나 Discord API 오류가 있으면 결과를 `pending_ban` 상태로 보존하고 10초 주기로 다시 시도합니다. 밴이 Discord에서 확인될 때까지 다음 라운드로 진행하지 않습니다. `DRY_RUN=true`일 때만 의도적으로 밴 없이 진행합니다.
- 보호 대상 패배 시 보호 우선 규칙이 적용됩니다. 보호 대상은 메인 서버에서 밴되지 않고, 그 승자만 보조 서버 자격을 얻습니다.
- 영구 밴이므로 실제 모드 전에 `DRY_RUN=true`로 충분히 테스트하세요.
- 봇이 꺼져 있거나 API가 늦으면 판정도 늦어집니다. 공식 배틀로그는 제한된 최근 기록을 제공하므로 봇을 경기 중 켜 두세요.
- 전일 이벤트가 아직 진행 중이면 새 일일 모집은 건너뜁니다. 관리자가 기존 이벤트를 끝내거나 취소해야 다음 모집이 열립니다.
- `/unban`을 사용해야 Discord 밴과 내부 Brawl 태그 차단이 함께 해제됩니다. Discord UI에서만 밴을 풀면 태그 차단이 남아 다시 참가할 수 없습니다.
- 메인 서버에서 사람 운영자에게 `Ban Members`를 넓게 부여하면 `/unban` 명령 외에도 직접 밴 해제가 가능합니다. Discord 역할 권한도 확인하세요.

## 테스트

```bash
cd brawl-discord-bot
python -m unittest discover -s tests -v
```

테스트는 태그 차단 목록, Bounty 1대1 판정, 대진, 설정 저장, 보호 대상 승자 등록 등을 검증합니다. 실제 Discord 전화 인증, 서버 권한/초대, Supercell API 연결은 테스트하지 않습니다.
