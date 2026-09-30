# 브롤스타즈 바운티 보호 대상 연속 1대1 챌린지 Discord 봇

이 코드는 **공개 설치 가능한 다중 서버 봇**으로 동작할 수 있습니다. 앱을 각 서버에 설치하면 서버 멤버가 참가 기능을 사용하고, 서버 관리자는 해당 서버의 대회 설정을 따로 저장합니다. 각 메인 서버의 보호 대상, 채널, 대기열, 등록 태그, 밴 목록, 보조 서버 자격은 서로 분리됩니다. 하나의 봇 프로세스가 여러 메인 서버를 처리하며, 각 메인 서버는 자기 보조 서버 하나를 설정합니다.

매일 최대 10명의 도전자를 참가 순서대로 등록한 뒤, **고정 보호 대상이 각 도전자와 별도의 바운티 1대1 경기를 한 번씩, 한 경기씩 순서대로** 진행하는 봇입니다. 도전자끼리는 서로 경기하지 않으며 탈락 브래킷이나 최종 우승자를 만들지 않습니다. 보호 대상에게 진 도전자는 결과가 확정되면 해당 메인 서버에서 영구 밴되고, 보호 대상이 진 경우에는 보호 대상을 밴하지 않고 그 도전자에게 설정한 보조 서버 입장 자격을 줍니다. 밴 목록에는 Discord ID와 Brawl 태그를 함께 기록해 같은 태그를 다른 Discord 계정으로 등록하는 것도 차단합니다.

> **실제 밴은 이중 잠금입니다.** 새 서버는 시험 모드로 시작합니다. 운영자가 `.env`에서 전역 안전 잠금 `DRY_RUN=false`를 설정해도 각 서버 관리자가 `/set_auto_ban enabled:true confirm:true`로 명시적으로 켜야 실제 영구 밴이 실행됩니다. `DRY_RUN=true`이면 어떤 서버도 실제 밴을 할 수 없습니다.

## 공개 설치·다중 서버 사용 방식

Discord의 서버 설치형 앱은 OAuth2의 **Guild Install**과 `bot` + `applications.commands` 범위로 각 서버에 설치하고, 슬래시 명령은 전역(Global) 명령으로 등록해야 여러 서버에서 사용할 수 있습니다. 이 버전은 전역 명령을 등록하며, SQLite의 모든 대회 데이터와 설정을 메인 서버 ID별로 분리합니다. 각 서버 관리자는 봇을 자기 메인 서버와 승자 보조 서버에 초대한 뒤 `/configure_servers`를 실행합니다.

공개 설치를 위해서는 코드 변경 외에도 네가 직접 Developer Portal에서 앱의 **Public Bot**을 켜고 **Guild Install**을 허용해야 합니다. 설치 설정에서 Guild Install에 `bot` 및 `applications.commands`를 넣고, 필요한 권한을 선택하세요. 개인용으로만 둘 거면 Public Bot을 끄면 됩니다. 이 봇은 서버 권한을 요구하므로 User Install은 켜지 않도록 제한합니다. 새 전역 명령은 Discord 반영에 시간이 걸릴 수 있습니다.

봇은 계속 켜져 있는 프로세스가 필요합니다. 로컬에서 `python bot.py`를 실행하는 동안만 온라인이며, 모두가 쓸 수 있게 하려면 네가 통제하는 상시 실행 서버/VPS/Docker 호스트에 배포해야 합니다. 같은 SQLite 파일을 쓰는 봇 인스턴스를 여러 개 동시에 띄우지 마세요. Developer Portal의 Public Bot 설정, 토큰 입력, 서버 호스팅은 저장소 코드만으로 대신 바꿀 수 없습니다.

Discord는 100개 이상 서버에서 사용하는 앱에 봇 검증을 요구하며, 멤버 관련 privileged intent도 검토/승인이 필요할 수 있습니다. 단일 Gateway shard의 상한은 2,500개 서버이므로 그 이상 규모에는 sharding과 운영 구조 확장이 필요합니다. 모든 서버에서 공유하는 Supercell API 키도 사용량이 커지면 제한될 수 있어 배틀로그 확인이 늦어질 수 있습니다.

## 요청한 규칙

1. 기본 서울 시간 18:00에 메인 서버의 모집 채널에 참가 버튼을 게시합니다. 10명이 버튼을 누르면 신청이 닫힙니다. 서버에 그냥 가입한 사람을 자동 참가자로 세지는 않습니다.
2. 참가자는 `/register #태그`로 등록합니다. API로 태그가 유효한지 확인한 뒤 **관리자 승인 없이 바로 참가할 수 있습니다.**
3. 버튼을 누른 순서대로 도전자 1~10번을 받습니다. 모집이 닫히면 **보호 대상 vs 1번**, 결과가 확정된 뒤 **보호 대상 vs 2번** 순서로 이어서 경기하고, 최대 10번까지 반복합니다. 한 번에 활성화되는 경기는 하나뿐이며, 도전자끼리 맞붙거나 다른 경기 결과에 따라 탈락하는 일은 없습니다.
4. 경기 감지는 **Bounty 모드로 고정**되어 있습니다. 두 참가자의 태그가 동일한 배틀로그에 있어야 하고, 서로 반대편이어야 하며, API 기록의 양쪽 결과가 승리/패배로 일치해야 합니다. 또한 양 팀이 한 명씩인 기록만 1대1로 처리합니다. 일반 3대3 바운티는 판정하지 않습니다.
5. 무승부, 기록 누락, 팀 구성 불일치, API 결과 충돌은 자동 밴하지 않습니다. API로 결과를 확인할 수 없는 경기는 관리자가 `/resolve_match`로 직접 확정할 수 있습니다.
6. 현재 1대1의 승패가 확정되면 처리부터 마칩니다. 보호 대상에게 진 도전자는 실제 밴 모드에서 메인 서버 영구 밴을 받습니다. 보호 대상이 패자라면 **보호 우선**으로 밴하지 않고 현재 도전자에게 보조 서버 자격을 부여한 뒤, 순서상 다음 도전자와 경기합니다.
7. 보호 대상을 이긴 도전자는 보조 서버 입장 자격에 기록되고, 설정된 경우 7일 동안 유효한 **사용 횟수 1회** 초대를 DM으로 받습니다. 초대 수락을 위해 경기를 멈추지는 않으며, 다음 도전자는 현재 경기의 결과/밴 처리가 끝난 뒤 시작합니다.

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

고정 보호 대상은 예외적으로 관리자가 Discord 계정과 Brawl 태그를 함께 지정합니다. `/set_protected member tag` 또는 숫자 ID를 받는 `/set_protected_id user_id tag`는 태그를 Supercell API로 확인하고 그 연결을 고정하므로, 보호 대상으로 지정된 Discord 계정이 임의의 다른 Brawl 태그를 등록해 보호 규칙이나 승자 자격을 잘못 적용하는 일을 막습니다. 경기 결과 처리에서는 이 Discord ID **또는** 고정 보호 Brawl 태그가 패자로 감지되면 밴하지 않습니다. 보조 서버 자격은 고정 Brawl 태그가 실제 패배자로 확인된 경기에서만 부여합니다.

### Discord ID·태그 차단과 전화 인증

실제 밴 모드에서는 패자의 Discord ID와 Brawl 태그를 별도의 영구 대회 차단 목록에 기록합니다. 따라서 Discord가 원래 계정을 밴해도 다른 Discord ID로 같은 Brawl 태그를 등록하거나 대기열에 들어갈 수 없습니다. 관리자 `/unban user_id`는 Discord 밴과 연결된 태그 차단을 함께 해제합니다. `DRY_RUN=true`에서는 테스트 계정을 영구 차단하지 않습니다.

봇은 Discord 계정의 전화번호를 읽거나 인증할 수 없고, 전화번호를 수집·저장하지 않습니다. 전화 인증은 Discord 서버의 기본 설정으로 강제해야 합니다. 메인 서버의 **Server Settings → Safety Setup → Verification Level → Highest**를 선택하면 Discord가 전화번호 인증이 완료되지 않은 계정의 서버 이용을 제한합니다. 봇은 이 수준이 아니면 모집을 열거나 참가 버튼을 받지 않습니다. `/phone_verification_status`로 현재 설정을 확인하고, 봇에 선택적으로 `Manage Server` 권한을 준 경우 `/enable_phone_verification confirm:true`로 켤 수 있습니다. 이 설정은 대회 참가자뿐 아니라 메인 서버 전체에 적용되며, 전화 인증만으로 동일인이 여러 계정을 쓰는 것을 완전히 증명하거나 막을 수는 없습니다.

## 경기 데이터

봇은 Brawlify 웹페이지를 스크래핑하지 않고 **Supercell 공식 Brawl Stars API의 플레이어 배틀로그**를 조회합니다. 기본 폴링 간격은 10초이며, 여러 서버의 진행 경기에서 중복 플레이어 태그 요청은 한 폴링 주기 안에서 합쳐 최대 3개 요청을 동시에 처리합니다. 서버 설치 수와 동시 경기 수가 커지면 공유 API 키가 제한될 수 있으며, 429나 지연이 발생하면 실제 판정/밴 처리도 늦어집니다. 경기 중 봇을 계속 실행해 주세요.

## 필요한 것

- Python 3.11 이상 또는 Docker와 봇을 상시 실행할 호스트
- 네 Discord 봇 앱의 Bot Token
- Supercell Brawl Stars API 키: [developer.brawlstars.com](https://developer.brawlstars.com/)
- 각 메인 서버에서 관리자 명령으로 설정할 모집/결과 채널, 보호 대상 계정과 태그
- 승자 전용 보조 서버를 쓸 경우 그 서버와 초대 채널

`DISCORD_GUILD_ID`, `QUEUE_CHANNEL_ID`, 보호 대상, 승자 서버 환경 변수는 **선택 사항이며 레거시 단일 서버 초기 설정용**입니다. 공개 다중 서버 운영에서는 `.env`에 `DISCORD_BOT_TOKEN`, `BRAWL_STARS_API_TOKEN` 및 공통 운영 설정만 넣고, 각 메인 서버에서 `/configure_servers`와 `/set_protected_id`를 실행합니다. API 키가 허용 IP를 요구하면 봇이 실행되는 서버의 외부 IP를 등록하세요. 토큰/API 키는 채팅이나 저장소에 올리지 말고 로컬 `.env`에만 입력하세요.

## Discord 설정과 권한

네 Developer Portal 앱을 공개 설치형으로 쓰려면:

1. 앱의 **Bot** 설정에서 `Public Bot`을 켭니다.
2. **Installation**에서 `Guild Install`을 허용합니다. Guild Install의 기본 범위에 `bot`과 `applications.commands`를 넣고 아래 필요한 권한을 요청합니다. User Install은 이 봇에서 필요하지 않습니다.
3. 아래 초대 링크로 봇을 각 메인 서버와 보조 서버에 설치합니다. 링크는 기존 Application ID `1554729717188272128`을 사용합니다. Public Bot이 꺼져 있으면 다른 관리자는 이 링크로 설치할 수 없습니다.
4. 봇을 호스팅하고 온라인으로 유지합니다. 봇이 실행 중이어야 명령, 경기 감지, 자동화가 동작합니다.

- [메인 대회 서버에 추가](https://discord.com/oauth2/authorize?client_id=1554729717188272128&scope=bot%20applications.commands&permissions=84996)
- [보조 승자 서버에 추가](https://discord.com/oauth2/authorize?client_id=1554729717188272128&scope=bot%20applications.commands&permissions=1027)

`Server Members Intent`를 Developer Portal에서 켜야 보호 대상의 서버 멤버 확인 및 보조 서버 입장 자격 검사가 동작합니다. 모든 슬래시 명령은 전역 명령으로 등록되므로, 서버에 추가 직후 아직 명령이 안 보이면 Discord 전파를 기다려 주세요. 제공한 **Public Key는 이 Gateway 방식의 봇에서 사용하지 않습니다**. Public Key는 HTTP interactions endpoint로 들어오는 요청의 서명을 검증할 때 쓰며, 이 봇은 `discord.py` Gateway 연결로 명령을 처리합니다. Application ID와 Public Key는 Bot Token을 대체하지 않습니다.

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
# .env에는 토큰과 API 키를 입력; 공개 다중 서버에서는 서버별 값은 슬래시 명령으로 설정
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

- 필수: `DISCORD_BOT_TOKEN`, `BRAWL_STARS_API_TOKEN`
- `DISCORD_GUILD_ID`, `QUEUE_CHANNEL_ID`, `RESULT_CHANNEL_ID`: 선택적 레거시 단일 서버 초기값. 공개 다중 서버 모드에서는 비워 둡니다. 이제 전역 슬래시 명령 동기화에 이 값은 필요하지 않습니다.
- `PROTECTED_DISCORD_ID` + `PROTECTED_BRAWL_TAG`, `WINNER_GUILD_ID` + `WINNER_INVITE_CHANNEL_ID`: 레거시 단일 서버 초기값일 때만 사용합니다. 공개 모드에서는 각 서버 관리자가 명령으로 저장합니다.
- `WINNER_SERVER_STAFF_IDS`: 모든 설정된 보조 서버에서 자격 검사 예외로 둘 운영자 ID (쉼표 구분)
- `DRY_RUN=true`: 운영자 전역 안전 잠금. `true`면 모든 서버에서 실제 밴이 불가능합니다. `false`로 바꿔도 서버별 관리자가 `/set_auto_ban enabled:true confirm:true`로 추가 허용해야 합니다.
- `ALLOWED_MODES=bounty`: 다른 모드로 변경 불가

기존 단일 서버 버전에서 서버/채널/보호 대상 값이 `.env`에만 있고 아직 `/configure_servers`로 저장하지 않았다면, 최초 업데이트 실행 때는 기존 값을 남겨 두어 SQLite로 가져오세요. 새 `/configure_servers` 설정이 저장된 뒤에는 공개 다중 서버 운영을 위해 레거시 값들을 비워도 됩니다.

각 메인 서버와 그 서버의 보조 서버에 봇을 초대한 뒤, **메인 서버에서** 관리자가 `/configure_servers main_guild_id queue_channel_id result_channel_id winner_guild_id invite_channel_id`를 실행합니다. `main_guild_id`는 명령을 실행 중인 현재 서버 ID여야 하며, `winner_guild_id`는 별도 보조 서버 ID입니다. 채널/서버 소속과 봇 권한을 검증한 후 해당 메인 서버에만 설정을 저장합니다. 각 메인 서버가 한 쌍의 보조 서버를 따로 설정할 수 있고, 같은 보조 서버를 여러 메인 서버가 공유할 수도 있습니다. `/set_winner_server`로 보조 서버만 별도로 변경할 수도 있습니다.

## 명령어

| 명령 | 권한 | 설명 |
| --- | --- | --- |
| `/register tag` | 참가자 | 유효한 브롤 태그 등록; 관리자 승인 없이 참가 가능 |
| `/my_tag` | 참가자 | 내 태그 등록 상태 확인 |
| `/leave_queue` | 참가자 | 10명 마감 전 대기열에서 나가기; 남은 번호 재정렬 |
| `/set_protected member tag` | 관리자 | 멤버 선택으로 보호 Discord 계정과 고정 Brawl 태그 설정; 이벤트 진행 중 변경 불가 |
| `/set_protected_id user_id tag` | 관리자 | 숫자 Discord ID로 보호 대상 지정; 메인 서버 멤버여야 하며 이벤트 중 변경 불가 |
| `/configure_servers main_guild_id queue_channel_id result_channel_id winner_guild_id invite_channel_id` | 관리자 | 현재 서버를 메인으로, 보조 서버와 채널을 검증해 저장; 서버별 독립 설정 |
| `/set_auto_ban enabled:true confirm:true` | 관리자 | 이 서버에서만 실제 영구 밴을 허용; 운영자 전역 `DRY_RUN=false`도 필요 |
| `/open_event` | 관리자 | 정기 시간 외에 모집 즉시 시작 |
| `/event_status` | 서버 멤버 | 모집 순번, 보호 대상 1대1 경기 및 결과 감지 상태 확인 |
| `/resolve_match slot winner` | 관리자 | API 판정이 안 된 현재 도전자 경기의 승자 직접 확정 |
| `/cancel_event` | 관리자 | 진행 중 이벤트 취소; 밴 처리 중에는 취소 불가 |
| `/set_winner_server guild_id invite_channel_id` | 관리자 | 보조 서버와 초대 채널만 변경; 설정은 SQLite에 저장 |
| `/phone_verification_status` | 관리자 | Discord 서버의 전화번호 인증 요구 수준 확인 |
| `/enable_phone_verification confirm:true` | 관리자 | Discord Verification Level을 Highest로 설정 (서버 전체 적용; 봇에 Manage Server 필요) |
| `/unban user_id reason` | 관리자 | Discord 밴 및 연결된 Brawl 태그 차단 해제 |
| `/resend_winner_invite user_id` | 관리자 | 보호 대상을 이긴 기록이 있는 참가자에게 보조 서버 초대 재전송 |
| `/audit_winner_server confirm:true` | 관리자 | 보조 서버 기존 멤버 중 자격 없는 계정 정리 |

일일 모집 버튼은 설정된 채널에 자동으로 올라옵니다. **Discord 서버에 입장하는 것만으로는 브롤 태그를 알 수 없으므로**, 참가자는 `/register`와 참가 버튼을 사용해야 합니다. 현재 Discord 밴된 사용자는 신청할 수 없고, 대회 차단 목록은 패자의 Discord ID와 Brawl 태그 양쪽을 저장해 같은 태그의 대체 계정 등록도 거부합니다.

## 처음 설정할 때

1. `.env`에 Bot Token과 Supercell API 키를 입력하고 `DRY_RUN=true`로 봇을 상시 실행할 호스트에 배포합니다. 공개 운영에서는 서버별 ID를 `.env`에 넣지 않습니다.
2. Developer Portal에서 Public Bot과 Guild Install을 켠 뒤, 봇을 각 메인 서버와 보조 서버에 설치합니다. 봇이 초대된 **메인 서버마다** 관리자가 `/configure_servers main_guild_id queue_channel_id result_channel_id winner_guild_id invite_channel_id`를 실행합니다.
3. 각 메인 서버에서 `/set_protected member tag` 또는 `/set_protected_id user_id tag`로 보호 Discord 계정과 Brawl 태그를 설정합니다. 태그는 API로 확인됩니다. 전화 인증을 요구하려면 해당 서버에서 Verification Level을 **Highest**로 설정하고 `/phone_verification_status`로 확인합니다. 간편 설정을 원하면 Manage Server 권한을 준 뒤 `/enable_phone_verification confirm:true`를 실행할 수 있습니다.
4. `DRY_RUN=true` 상태에서 태그 등록, Bounty 1대1 결과, 보호 대상 예외를 시험합니다. 이때 Discord 밴과 내부 ID/태그 차단은 동작하지 않습니다. 보조 서버 설정이 있으면 초대 DM과 입장 제한은 계속 실제로 동작하므로 테스트 서버/계정으로 확인하세요.
5. 실제 밴을 허용하려면 운영자가 `.env`에서 `DRY_RUN=false`로 바꾸고 봇을 재시작합니다. 그런 다음 **각 메인 서버에서** 관리자만 `/set_auto_ban enabled:true confirm:true`를 실행해야 그 서버에서 자동 영구 밴이 켜집니다. 필요하다면 `/set_auto_ban enabled:false`로 해당 서버에서 끌 수 있습니다.
6. 보조 서버의 기존 멤버를 정리할 필요가 있을 때만 `/audit_winner_server confirm:true`를 실행합니다. 이 명령은 연결된 대회 서버들 중 하나라도 승자 자격이 있는 사용자, 소유자, 지정 운영자를 제외하고 기존 멤버를 추방합니다.

## 안전·운영 주의사항

- 결과가 명확하고 바운티 1대1로 확인될 때만 자동 밴합니다. 모호한 결과에서 추측하지 않습니다.
- 실제 밴 권한/역할 순서 오류나 Discord API 오류가 있으면 현재 결과를 `pending_ban` 상태로 보존하고 10초 주기로 다시 시도합니다. 필요한 밴 처리가 끝날 때까지 다음 순번의 도전자는 시작하지 않습니다. `DRY_RUN=true`일 때만 의도적으로 밴 없이 진행합니다.
- 보호 대상 패배 시 보호 우선 규칙이 적용됩니다. 보호 대상은 메인 서버에서 밴되지 않고, 그 승자만 보조 서버 자격을 얻습니다.
- 영구 밴이므로 실제 모드 전에 `DRY_RUN=true`로 충분히 테스트하세요.
- 봇이 꺼져 있거나 API가 늦으면 판정도 늦어집니다. 공식 배틀로그는 제한된 최근 기록을 제공하므로 봇을 경기 중 켜 두세요.
- 전일 이벤트가 아직 진행 중이면 새 일일 모집은 건너뜁니다. 관리자가 기존 이벤트를 끝내거나 취소해야 다음 모집이 열립니다.
- 이전 브래킷 버전이 남긴 모집/진행 중 이벤트와 미완료 경기는 보호 대상과의 1대1 기록이 아니므로 업데이트 후 자동 취소되고, 그 경기들로 새 자동 밴을 실행하지 않습니다. 새 순차 1대1 모집을 다시 열어 주세요.
- `/unban`을 사용해야 Discord 밴과 내부 Brawl 태그 차단이 함께 해제됩니다. Discord UI에서만 밴을 풀면 태그 차단이 남아 다시 참가할 수 없습니다.
- 메인 서버에서 사람 운영자에게 `Ban Members`를 넓게 부여하면 `/unban` 명령 외에도 직접 밴 해제가 가능합니다. Discord 역할 권한도 확인하세요.

## 테스트

```bash
cd brawl-discord-bot
python -m unittest discover -s tests -v
```

테스트는 태그 차단 목록, Bounty 1대1 판정, 순차 경기 진행, 다중 서버 데이터 격리, 전역 서버 설치용 명령 등록, 보호 대상에게 이긴 도전자 자격 등록 등을 검증합니다. 실제 Discord 전화 인증, Public Bot/Guild Install Portal 설정, 서버 권한/초대, 호스팅 상태, Supercell API 연결은 테스트하지 않습니다.
