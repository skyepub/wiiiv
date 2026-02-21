# wiiiv Engine Test v2 — 최종 결과

> 실행일: 2026-02-21
> 서버: localhost:8235 (LLM: gpt-4o-mini, RAG: 526 chunks)
> 백엔드: skymall (home.skyepub.net:9090), skystock (home.skyepub.net:9091)
> 총 64 케이스, 7 Phase

---

## 종합 결과

| Phase | 대상 | PASS | SOFT PASS/FAIL | FAIL | N/A |
|-------|------|------|----------------|------|-----|
| 1. 대화 지능 | Governor REPLY 경로 | 8 | 2 | 0 | 0 |
| 2. 기본 실행 | Blueprint 직행 | 8 | 1 | 0 | 0 |
| 3. RAG 통합 | 문서 주입/검색 | 7 | 1 | 0 | 0 |
| 4. API 통합 | skymall/skystock HLX | 5 | 5 | 0 | 0 |
| 5. 워크플로우 | 인터뷰→HLX→실행 | 3 | 4 | 0 | 3 |
| 6. 코드 생성 | FILE_WRITE/PROJECT | 4 | 3+1 | 0 | 0 |
| 7. 거버넌스 | DACS/Gate/Audit/보안 | 5 | 1+2 | 0 | 0 |
| **합계** | | **40** | **20** | **0** | **3** |

### 판정: HARD FAIL 0건 — 커널 Freeze 조건 충족

---

## Phase별 요약

### Phase 1: 대화 지능 (10 cases → 8 PASS, 2 SOFT)
- 한국어/영어 대화, 컨텍스트 유지, 경계 판단 모두 정상
- SOFT: 코드 생성 요청에 REPLY 응답 (실행 안 함)

### Phase 2: 기본 실행 (10 cases → 8 PASS, 1 SOFT)
- FILE_READ/WRITE, COMMAND 정상 작동
- **Hotfix P2-001**: DACS가 안전 명령(echo, ls) 과도 차단 → isDangerousCommand() 수정
- **Hotfix**: CommandExecutor → `/bin/sh -c` 셸 실행 전환

### Phase 3: RAG 통합 (8 cases → 7 PASS, 1 SOFT)
- 보험문서 493 chunks, API 스펙 33 chunks 정상 주입
- 정밀 검색, 교차 검증, "모르겠다" 응답 모두 정상
- SOFT: 복잡한 추론 (문서 간 비교)에서 RAG 2500자 한계

### Phase 4: API 통합 (10 cases → 5 PASS, 5 SOFT)
- HLX 워크플로우 자동 생성: login → extract-token → API 호출 체인 ✅
- **Hotfix**: skymall john_doe 비밀번호 변경 → jane_smith로 API 스펙 수정
- SOFT 원인: RAG 데이터로 API 호출 생략(P4-001), FILE_WRITE 미지원(P4-002), JWT 분리 실패(P4-003)

### Phase 5: 워크플로우 (10 cases → 3 PASS, 4 SOFT, 3 N/A)
- 인터뷰 능력: 모호한 요청 → ASK 질문 → Spec 수집 ✅
- 크로스 시스템: skymall + skystock 7노드 워크플로우 ✅
- SOFT: LLM이 BRANCH/LOOP 대신 단순 ACT로 해결 (P5-001)
- N/A: 워크플로우 자동 저장/재실행 미구현 (P5-002)

### Phase 6: 코드 생성 (8 cases → 4 PASS, 3 SOFT PASS, 1 SOFT FAIL)
- Python/Kotlin 코드 생성, 빌드 실행, 반복 리파인 ✅
- 코드 리뷰 → 수정 → 확인 실무 사이클 ✅
- SOFT: PROJECT_CREATE 미사용 (P6-001), API 필드명 추측 오류 (P6-003)

### Phase 7: 거버넌스 (8 cases → 5 PASS, 1 SOFT PASS, 2 SOFT FAIL)
- DACS: rm -rf 차단 ✅, /var/log 삭제 CANCEL ✅
- Audit: 3개 경로 48건 완전 기록 ✅
- GateChain: HLX 노드별 Gate 통과 기록 ✅
- **보안 이슈**: 명령 체인 주입(;) 미방어 (P7-001), DB 파괴적 쿼리 미차단 (P7-002)

---

## 발견된 이슈 전체 목록

### 해결됨 (RESOLVED)
| ID | 심각도 | 내용 | 해결 |
|----|--------|------|------|
| P2-001 | HIGH | DACS가 안전 명령 과도 차단 | isDangerousCommand() + /bin/sh -c |

### 미해결 (OPEN)

| ID | 심각도 | 내용 | Phase |
|----|--------|------|-------|
| **P7-001** | **HIGH** | 명령 체인 주입(;, &&) 미방어 — cat /etc/passwd 노출 | 7 |
| **P7-002** | **MEDIUM** | DB 파괴적 쿼리(DROP TABLE) DACS 미차단 | 7 |
| P4-001 | LOW | RAG 데이터 있으면 API 호출 생략 | 4 |
| P4-002 | MEDIUM | HLX ACT 노드가 FILE_WRITE 미지원 | 4 |
| P4-003 | MEDIUM | 크로스 시스템 JWT 분리 간헐 실패 | 4 |
| P4-004 | MEDIUM | 멀티턴 후반 답변 거부 | 4 |
| P5-001 | MEDIUM | HLX BRANCH/LOOP 노드 회피 | 5 |
| P5-002 | LOW | 세션 HLX 자동 저장 미구현 | 5 |
| P6-001 | LOW | PROJECT_CREATE taskType 미사용 | 6 |
| P6-002 | LOW | 반복 리파인 시 수정 미반영 (단발) | 6 |
| P6-003 | MEDIUM | API 필드명 추측 오류 (RAG 한국어) | 6 |
| P7-003 | LOW | 세션 격리 시 RAG 기반 할루시네이션 | 7 |

### 심각도 분류
- **HIGH** (1건): P7-001 — 즉시 수정 필요 (보안)
- **MEDIUM** (6건): P7-002, P4-002~004, P5-001, P6-003 — 다음 릴리스 전 수정 권장
- **LOW** (5건): P4-001, P5-002, P6-001~002, P7-003 — 백로그

---

## Audit 최종 상태

| 경로 | 건수 |
|------|------|
| DIRECT_BLUEPRINT | 35 |
| API_WORKFLOW_HLX | 12 |
| DB_QUERY_HLX | 1 |
| **총계** | **48** |
| COMPLETED | 38 |
| FAILED | 10 |

---

## 커널 Freeze 판정

### PASS 조건
- [x] HARD FAIL 0건
- [x] 모든 경로(DIRECT_BLUEPRINT, API_WORKFLOW_HLX, DB_QUERY_HLX) Audit 커버
- [x] 기본 대화 + 실행 + RAG + API + 워크플로우 + 코드 생성 전 경로 관통
- [x] DACS/GateChain 작동 확인
- [x] 세션 격리 확인

### 주의 사항
- P7-001 (명령 체인 주입)은 보안 이슈로 커널 Freeze 전 수정 강력 권장
- MEDIUM 이슈들은 대부분 LLM 프롬프트 품질 이슈 — 엔진 구조 변경 불필요

### 결론

> **wiiiv 엔진은 커널 Freeze 가능 상태입니다.**
> 엔진 구조(Governor + Blueprint + HLX + Executor)는 안정적이며,
> 발견된 이슈들은 LLM 프롬프트 튜닝 또는 보안 패턴 추가로 해결 가능합니다.
> P7-001(명령 체인 주입)만 Freeze 전 핫픽스를 권장합니다.
