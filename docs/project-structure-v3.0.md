# wiiiv 프로젝트 구조 v3.0

> 2026-02-12 구조 재설계 계획
>
> 상태: **2단계 완료**

---

## 배경

### 현재 구조 (v2.1)

```
wiiiv/                      <- git root (모노레포)
├── wiiiv-core/             <- 엔진 라이브러리
├── wiiiv-api/              <- HTTP 서버 (Ktor/Netty, port 8235)
├── wiiiv-cli/              <- 원격 CLI 클라이언트 (HTTP)
├── wiiiv-shell/            <- 대화형 REPL (core 직접 호출)
└── docs/
```

### 문제

1. **wiiiv-shell이 core를 직접 내장한다**
   - wiiiv-server(api)를 거치지 않고 Governor, DACS, Executor를 in-process로 사용
   - 인증/권한/감사 계층 우회
   - 다중 사용자 서비스화 불가

2. **wiiiv-cli와 wiiiv-shell의 역할 중복**
   - CLI: 단발성 명령, HTTP 호출 (표준적)
   - Shell: 대화형 REPL, core 직접 호출 (비표준)
   - 하나의 클라이언트가 양쪽 모두 담당할 수 있음 (mysql, psql, redis-cli 패턴)

3. **명명 불일치**
   - `wiiiv-api`: 이름은 API 계층이지만 실제로는 유일한 서버 프로세스
   - forgium 프로젝트의 명명 규칙(`forgium_backend/forgium-server`)과 불일치

---

## 새 구조 (v3.0)

```
wiiiv/                          <- git root (모노레포 유지)
│
├── wiiiv-backend/              <- 서버측 (Kotlin/Ktor)
│   ├── wiiiv-core/             <- 엔진 라이브러리
│   └── wiiiv-server/           <- HTTP/WebSocket 서버 (현 wiiiv-api)
│
├── wiiiv-cli/                  <- 터미널 클라이언트 (대화형 + 명령형)
│   (실행 바이너리: wiiiv)       <- 현 wiiiv-shell + wiiiv-cli 통합
│
├── wiiiv-frontend/             <- 웹 클라이언트 (향후)
├── wiiiv-app/                  <- 데스크톱 클라이언트 (향후)
│
└── docs/                       <- 프로젝트 전체 문서
```

### 명명 체계

| 이름 | 역할 | 형태 |
|------|------|------|
| `wiiiv-backend` | 서버측 프로젝트 그룹 | Kotlin/Gradle 멀티모듈 |
| `wiiiv-core` | 핵심 엔진 라이브러리 | 라이브러리 (JAR) |
| `wiiiv-server` | HTTP/WebSocket 서버 | 서버 프로세스 |
| `wiiiv-cli` | 터미널 클라이언트 | CLI 바이너리 (`wiiiv`) |
| `wiiiv-frontend` | 웹 클라이언트 | 웹앱 (향후) |
| `wiiiv-app` | 데스크톱 클라이언트 | 네이티브 앱 (향후) |

### 설계 원칙

- **모든 클라이언트는 서버에 접속한다** (core 직접 호출 금지)
- **서버가 유일한 엔진 호스트다** (Governor, DACS, Executor는 서버에서만 실행)
- **클라이언트는 형태(form factor)로 구분한다** (cli, frontend, app)

### MySQL 비유

```
mysqld          = wiiiv-server      (서버 데몬)
mysql           = wiiiv (cli)       (대화형 클라이언트)
mysqladmin      = wiiiv -e "..."    (단발 명령 = 같은 cli의 옵션)
phpMyAdmin      = wiiiv-frontend    (웹 클라이언트)
MySQL Workbench = wiiiv-app         (데스크톱 클라이언트)
```

---

## 마이그레이션 단계

### 1단계: 구조 정리 (폴더 + 이름)

- [x] `wiiiv-api/` -> `wiiiv-backend/wiiiv-server/` (패키지: io.wiiiv.api → io.wiiiv.server)
- [x] `wiiiv-core/` -> `wiiiv-backend/wiiiv-core/`
- [x] `wiiiv-shell/` -> `wiiiv-cli/` (패키지: io.wiiiv.shell → io.wiiiv.cli, 구 wiiiv-cli 삭제)
- [x] `build.gradle.kts`, `settings.gradle.kts` 수정
- [x] CLAUDE.md 업데이트

### 2단계: 서버에 대화형 API 추가

- [x] ConversationalGovernor 세션 관리 API (SessionManager)
- [x] SSE 엔드포인트 (대화 스트리밍 — POST /sessions/{id}/chat)
- [x] Progress 이벤트 스트리밍 (SseProgressBridge → GovernorProgressListener)
- [x] 세션 기반 JWT 인증 연동
- [x] Auto-continue 서버 측 처리 (nextAction == CONTINUE_EXECUTION 루프)
- [x] Mutex 기반 chat() 직렬화 (progressListener 동시성 보호)

### 3단계: CLI를 서버 접속 클라이언트로 전환

- [ ] core 직접 호출 제거
- [ ] HTTP/SSE 클라이언트로 전환
- [ ] 대화형 모드: SSE 기반 REPL (POST /sessions/{id}/chat)
- [ ] 명령형 모드: REST 기반 단발 호출 (`wiiiv -e "..."`)
- [ ] 기존 wiiiv-cli 기능 흡수 (auth, config 등)

---

## 의존 관계 변화

### 현재 (v2.1)

```
wiiiv-shell ──(in-process)──> wiiiv-core      <- 직접 호출 (문제)
wiiiv-cli   ──(HTTP)────────> wiiiv-api ──> wiiiv-core
```

### 목표 (v3.0)

```
wiiiv-cli      ──(HTTP/WS)──> wiiiv-server ──> wiiiv-core
wiiiv-frontend ──(HTTP/WS)──> wiiiv-server ──> wiiiv-core
wiiiv-app      ──(HTTP/WS)──> wiiiv-server ──> wiiiv-core
```

모든 클라이언트가 동일한 경로로 서버에 접속한다.

---

*wiiiv / 하늘나무 / SKYTREE*
