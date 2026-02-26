# Phase 8: 통합 스트레스 테스트 — C25/C26

> Phase 8은 wiiiv 엔진의 극한 내구성을 검증한다.
> 단순히 "실행되는가"가 아니라, "혼란 속에서도 일관성을 유지하는가"를 묻는다.

---

## C25: 공급망 워크플로우 + 중간 간섭 (15턴)

> **난이도**: ★★★★★★★★
> **목표**: WORKFLOW_CREATE 인터뷰 중 /control cancel → 긴급 API 확인 → 재시작하여 완성
> **핵심 검증**: cancel 후 세션 정리, 새 작업 독립 시작, 재시작 시 이전 context 없음

### 턴별 스크립트

| Turn | 메시지 | 기대 액션 | 비고 |
|------|--------|-----------|------|
| T1 | "공급망 자동 발주 워크플로우를 만들어줘" | ASK | WORKFLOW_CREATE 시작 |
| T2 | "skystock 재고 알림 기반으로, CRITICAL은 즉시 발주, WARNING은 재고 확인 후 조건부" | ASK | 처리 흐름 |
| T3 | "skymall에서 상품 정보 조회, skystock에서 알림/공급사/발주 API 사용" | ASK | 데이터 소스 |
| T4 | "에러처리: API 실패 retry:1 then skip" | ASK/CONFIRM | 에러 정책 |
| T5 | `/control cancel` | CONTROL_OK | **인터뷰 중단** |
| T6 | "급해! skystock 대시보드 데이터 빨리 확인해줘" | EXECUTE | API_WORKFLOW (긴급) |
| T7 | "CRITICAL 알림 몇 개인지만 알려줘" | EXECUTE/REPLY | API_WORKFLOW (간단) |
| T8 | "OK, 아까 중단한 워크플로우 다시 만들자. 자동 발주 워크플로우" | ASK | WORKFLOW_CREATE 재시작 |
| T9 | "skystock CRITICAL/WARNING 알림 기반, skymall 상품 조회, 공급사 확인, 조건부 발주. 에러는 retry:1 then skip, 발주 실패 abort" | ASK/CONFIRM | 한 번에 많은 정보 |
| T10 | "결과는 /tmp/wiiiv-stress/reorder-result.json에 저장, PDF 보고서도" | CONFIRM | 출력 형식 |
| T11 | "인증: skymall jane_smith/pass1234, skystock admin/admin123" | CONFIRM | 인증 정보 |
| T12 | "확인. 작업지시서 만들어줘" | CONFIRM | WorkOrder 생성 |
| T13 | "좋아, 실행해" | EXECUTE | HLX 생성+실행 |
| T14 | "결과 알려줘" | REPLY | 실행 요약 |
| T15 | "'supply-chain-reorder' 이름으로 저장" | EXECUTE/REPLY | 워크플로우 저장 |

### Hard Assert

- **T5**: `/control cancel` 성공 → `activeTaskId=null`
- **T6**: cancel 후 독립 작업 시작 가능 (EXECUTE — 충분히 명확한 요청)
- **T8**: 완전히 새로운 WORKFLOW_CREATE 인터뷰 시작 (ASK)
- **T12 또는 이전**: CONFIRM (WorkOrder 제시)
- **T13**: EXECUTE (HLX 생성 + 실행)

### Soft Assert

- T5 cancel 후 DraftSpec이 완전히 비어있음
- 재시작(T8~) 시 이전 인터뷰 히스토리 참조 없음
- 워크플로우 실행 결과에 OK 노드 다수

### WorkOrder 품질 체크 (12항목)

| # | 항목 | 확인 |
|---|------|------|
| 1 | skystock | `"skystock" in wo` |
| 2 | skymall | `"skymall" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | 9091 | `"9091" in wo` |
| 5 | CRITICAL | `"CRITICAL" in wo` |
| 6 | WARNING | `"WARNING" in wo` |
| 7 | stock-alerts | `"stock-alerts" in wo or "알림" in wo` |
| 8 | purchase-orders | `"purchase-orders" in wo or "발주" in wo` |
| 9 | retry | `"retry" in wo.lower()` |
| 10 | abort | `"abort" in wo.lower()` |
| 11 | PDF | `"pdf" in wo.lower()` |
| 12 | JSON 저장 | `"json" in wo.lower() or "파일" in wo` |

**PASS**: 10/12 이상

---

## C26: 김현수 팀장의 하루 — 종합 선물 세트 (50턴)

> **난이도**: ★★★★★★★★★★ (MAX)
> **목표**: INFORMATION + API_WORKFLOW + WORKFLOW_CREATE + PROJECT_CREATE + 3회 task switch/cancel
> **핵심**: "이걸 통과했다면 정말 대단하다"

### Phase 구조

| Phase | Turn 범위 | TaskType | 핵심 |
|-------|-----------|----------|------|
| A | T1-T5 | INFORMATION + API_WORKFLOW | 아침 시스템 점검 |
| B | T6-T10 | RAG + API_WORKFLOW | 데이터 분석 |
| C | T11-T20 | WORKFLOW_CREATE #1 | 자동 발주 워크플로우 (풀 인터뷰) |
| D | T21-T24 | /control cancel → API_WORKFLOW | 긴급 간섭 #1 |
| E | T25-T37 | PROJECT_CREATE | 재고 동기화 마이크로서비스 개발 |
| F | T38-T41 | /control cancel → FILE_WRITE/READ | 긴급 간섭 #2 |
| G | T42-T48 | WORKFLOW_CREATE #2 | 모니터링 워크플로우 (빠른 인터뷰) |
| H | T49-T50 | INFORMATION | 마무리 — 오늘 작업 요약 |

### Hard Assert

**Phase A-B (정보 조회)**:
- T1-T5: EXECUTE (API 호출 성공)
- T6-T7: REPLY (RAG 기반 응답)

**Phase C (WORKFLOW_CREATE #1)**:
- T11: ASK (즉시 실행 금지)
- T16 또는 이전: CONFIRM (WorkOrder 제시)
- T17: EXECUTE (HLX 생성+실행)

**Phase D (긴급 간섭)**:
- T21: /control cancel 성공
- T22: cancel 후 즉시 새 작업 가능

**Phase E (PROJECT_CREATE)**:
- T25: ASK (프로젝트 정보 질문)
- T31 또는 이전: CONFIRM (WorkOrder)
- T33: EXECUTE (코드 생성)

**Phase F (긴급 간섭 #2)**:
- T38: /control cancel 성공
- T39-T40: EXECUTE (파일 확인)

**Phase G (WORKFLOW_CREATE #2)**:
- T42: ASK (인터뷰)
- T45 또는 이전: CONFIRM
- T46: EXECUTE

**세션 일관성**:
- 50턴 동안 세션 유지 (같은 sessionId)
- /control cancel 후에도 새 작업 정상 시작
- 마지막 턴(T50) 까지 응답 정상

### Soft Assert

- T6-T7: RAG 검색 결과에 API 경로/파라미터 포함
- Phase C WorkOrder에 6차원 정보 포함
- Phase E WorkOrder에 엔티티/API/보안 상세 포함
- T49: 오늘 수행한 작업 목록 (워크플로우 2개 + 프로젝트 1개) 언급

---

## 실행 방법

```bash
# C25만 실행 (15턴, ~5분)
python3 hst-runner.py --phases 8 --case P8-C25

# C26만 실행 (50턴, ~20분)
python3 hst-runner.py --phases 8 --case P8-C26

# Phase 8 전체 (65턴)
python3 hst-runner.py --phases 8

# 타임아웃 늘려서 실행 (스트레스 테스트는 응답이 느릴 수 있음)
python3 hst-runner.py --phases 8 --timeout 300

# 케이스 목록만 확인
python3 hst-runner.py --phases 8 --dry-run
```

## PASS 기준

- **C25**: Hard Assert 전부 통과
- **C26**: Hard Assert 80% 이상 (LLM 변동성 감안)
