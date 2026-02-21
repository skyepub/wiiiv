# wiiiv Engine Test Plan v2

> **목적**: 완성된 엔진(Phase A~E-2)의 전 경로 검증
> **기준**: 커널 Freeze 판정을 위한 품질 게이트
> **전제**: 이전 Phase 통과가 다음 Phase 진입 조건

---

## 테스트 인프라

| 항목 | 값 |
|------|---|
| wiiiv 서버 | `http://localhost:8235` |
| skymall 백엔드 | `http://home.skyepub.net:9090` |
| skystock 백엔드 | `http://home.skyepub.net:9091` |
| RAG 보험문서 | `wiiiv 수동테스트/samsung_realloss.pdf` |
| RAG API 스펙 | `test-wiiiv/phase3/skymall-api-spec-deployed.md` |
| RAG API 스펙 | `test-wiiiv/phase3/skystock-api-spec-deployed.md` |

## 세션 프로토콜

```bash
# 1. 토큰 발급
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | jq -r '.data.accessToken')

# 2. 세션 생성
SID=$(curl -s -X POST http://localhost:8235/api/v2/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"test"}' | jq -r '.data.sessionId')

# 3. 메시지 전송 (SSE)
curl -N -X POST "http://localhost:8235/api/v2/sessions/$SID/chat" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"안녕?"}'

# 4. Audit 확인
curl -s http://localhost:8235/api/v2/audit \
  -H "Authorization: Bearer $TOKEN" | jq
```

## 판정 기준

| 레벨 | 의미 | 실패 시 |
|------|------|---------|
| **Hard Assert** | 절대 위반 불가 | 즉시 FAIL, 엔진 수정 필요 |
| **Soft Assert** | 권장 동작 | 기록 후 계속 진행, LLM 품질 이슈 |
| **Audit Assert** | 감사 기록 존재 | FAIL, 감사 누락은 구조적 결함 |

---

## Phase 구조

| Phase | 검증 대상 | Cases | 의존 |
|-------|----------|-------|------|
| **1. 대화 지능** | Governor REPLY 경로, 컨텍스트 유지, 경계 판단 | 10 | 없음 |
| **2. 기본 실행** | Blueprint 직행 (FILE_*, COMMAND), Audit | 10 | Phase 1 |
| **3. RAG 통합** | 문서 주입, 정밀 검색, 복합 추론 | 8 | Phase 1 |
| **4. API 통합** | skymall/skystock, HLX 자동생성, 크로스 시스템 | 10 | Phase 2,3 |
| **5. 워크플로우** | 인터뷰→Spec→HLX→실행→저장→재실행 | 10 | Phase 4 |
| **6. 코드 생성** | PROJECT_CREATE, 멀티턴 리파인, IntegrityAnalyzer | 8 | Phase 2 |
| **7. 거버넌스** | DACS, GateChain, Audit 완전성, 보안 경계 | 8 | 전체 |

**총 64 케이스**

---

## 실행 로그 규칙

각 케이스 실행 후 로그 저장:
```
test-wiiiv-2/phase-N-xxx/logs/case-NN-result.md
```

로그 내용:
- 실제 응답 (action, message 요약)
- Hard Assert 결과 (PASS/FAIL)
- Soft Assert 결과 (PASS/WARN)
- Audit Assert 결과 (PASS/FAIL)
- 특이사항/버그 메모
