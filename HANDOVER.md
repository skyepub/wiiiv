# wiiiv 프로젝트 핸드오버

> 2026-02-01 작업 내용 및 Windows 환경 전환 가이드

---

## 오늘 완료한 작업

### 1. RAG 구현 (wiiiv-core)
- Embedding Provider (OpenAI, Mock)
- Vector Store (InMemory)
- Chunker (FixedSize, Sentence, Token)
- Retriever (Simple, Reranking)
- RagExecutor (Blueprint 통합)
- 46개 테스트

### 2. RAG API (wiiiv-api)
```
POST   /api/v2/rag/ingest        문서 수집
POST   /api/v2/rag/ingest/batch  배치 수집
POST   /api/v2/rag/search        유사도 검색
GET    /api/v2/rag/size          저장소 크기
GET    /api/v2/rag/documents     문서 목록
DELETE /api/v2/rag/{documentId}  문서 삭제
DELETE /api/v2/rag               저장소 초기화
```

### 3. RAG CLI (wiiiv-cli)
```bash
wiiiv rag ingest --file <path>    파일 수집
wiiiv rag ingest --content <text> 텍스트 수집
wiiiv rag search <query>          검색
wiiiv rag list                    문서 목록
wiiiv rag delete <id>             삭제
wiiiv rag clear                   초기화
wiiiv rag size                    크기 조회
```

### 4. CLI 테스트 (47개)
- AuthCommandTest
- SystemCommandTest
- RagCommandTest
- WiiivClientTest
- E2EIntegrationTest

### 5. 푸시 완료
```
https://github.com/skyepub/wiiiv.git
branch: main
commit: b605ede
```

---

## 전체 테스트 현황

```
Total: 456 tests passing
├── wiiiv-core: 363 tests
├── wiiiv-api: 45 tests
└── wiiiv-cli: 47 tests
```

---

## Windows 개발 환경 설정 가이드

### 1. 필수 설치

**Chocolatey (관리자 PowerShell):**
```powershell
Set-ExecutionPolicy Bypass -Scope Process -Force
[System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor 3072
iex ((New-Object System.Net.WebClient).DownloadString('https://community.chocolatey.org/install.ps1'))
```

**개발 도구:**
```powershell
choco install git
choco install jdk17
choco install gradle
choco install intellijidea-community  # 또는 ultimate
```

### 2. Windows Terminal + Git Bash 설정

**Windows Terminal 설치:**
```powershell
choco install microsoft-windows-terminal
```

**Git Bash 프로필 추가 (설정 → 새 프로필):**
```json
{
    "name": "Git Bash",
    "commandline": "C:\\Program Files\\Git\\bin\\bash.exe --login -i",
    "icon": "C:\\Program Files\\Git\\mingw64\\share\\git\\git-for-windows.ico",
    "startingDirectory": "%USERPROFILE%"
}
```

**기본 프로필로 설정** → Git Bash 선택

### 3. 프로젝트 클론

```bash
# Git Bash에서
cd /c/Users/<username>/Projects
git clone https://github.com/skyepub/wiiiv.git
cd wiiiv
```

### 4. 빌드 및 테스트

```bash
# 빌드
./gradlew build

# 테스트
./gradlew test

# API 서버 실행
./gradlew :wiiiv-api:run

# CLI 빌드
./gradlew :wiiiv-cli:jar
```

### 5. IntelliJ 설정

1. IntelliJ 실행
2. Open → `C:\Users\<username>\Projects\wiiiv`
3. JDK 17 설정 확인
4. Gradle import 자동 완료 대기

---

## 프로젝트 구조

```
wiiiv/
├── wiiiv-core/          # 핵심 로직
│   └── src/main/kotlin/io/wiiiv/
│       ├── execution/   # Executor
│       ├── rag/         # RAG Pipeline
│       ├── gate/        # Gate Chain
│       ├── dacs/        # DACS 합의
│       └── governor/    # Governor
├── wiiiv-api/           # REST API (Ktor)
│   └── src/main/kotlin/io/wiiiv/api/
│       ├── routes/      # API 라우트
│       └── dto/         # 요청/응답 DTO
├── wiiiv-cli/           # CLI (clikt)
│   └── src/main/kotlin/io/wiiiv/cli/
│       ├── commands/    # 명령어
│       └── client/      # HTTP 클라이언트
└── docs/                # 문서
```

---

## 주요 명령어

```bash
# 전체 빌드
./gradlew build

# 전체 테스트
./gradlew test

# 특정 모듈 테스트
./gradlew :wiiiv-core:test
./gradlew :wiiiv-api:test
./gradlew :wiiiv-cli:test

# API 서버 실행 (port 8235)
./gradlew :wiiiv-api:run

# CLI 사용
java -jar wiiiv-cli/build/libs/wiiiv-cli.jar --help
```

---

## API 서버 테스트

```bash
# 서버 실행 후

# 헬스 체크
curl http://localhost:8235/api/v2/system/health

# 자동 로그인
curl http://localhost:8235/api/v2/auth/auto-login

# 토큰으로 RAG 테스트
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | jq -r '.data.accessToken')

curl -X POST http://localhost:8235/api/v2/rag/ingest \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content": "Test document", "title": "Test"}'
```

---

## 다음 작업 (TODO)

- [ ] 배포 자동화 (Docker, CI/CD)
- [ ] 외부 Vector Store (Pinecone, PgVector)
- [ ] 외부 Embedding Provider (실제 OpenAI 연동)
- [ ] 실제 사용자 인증 (현재 admin/admin123 하드코딩)

---

## Git Bash vs WSL vs PowerShell

| 항목 | Git Bash | WSL | PowerShell |
|------|----------|-----|------------|
| 설치 복잡도 | 낮음 | 중간 | 없음 |
| Bash 문법 | ✅ | ✅ | ❌ |
| 이중 설치 필요 | ❌ | ✅ | ❌ |
| IDE 연동 | 자연스러움 | 설정 필요 | 자연스러움 |
| 성능 | 좋음 | /mnt/c 느림 | 좋음 |

**추천: Git Bash** (Bash 문법 + 단일 환경)

---

## 참고

- CLAUDE.md: 프로젝트 전체 문서
- docs/wiiiv-api-reference.md: API 문서
- docs/wiiiv-cli-reference.md: CLI 문서

---

*wiiiv / 하늘나무 / SKYTREE*
