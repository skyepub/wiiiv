# P5-C24: 크로스 시스템 자동 발주 + 보고서 + 이메일 알림

> 실행일: 2026-02-26
> 세션: HST-P5-C24 (07128b73)

## 턴별 결과

| Turn | 입력 요약 | 기대 | 실제 | 판정 |
|------|-----------|------|------|------|
| 1 | 막연한 시작 | ASK | ASK | PASS |
| 2 | 시스템 + 대상 | ASK | ASK | PASS |
| 3 | 전체 요구사항 + 작업지시서 요청 | CONFIRM | CONFIRM | PASS |
| 4 | WorkOrder 수정 요청 (노드 분리) | CONFIRM | ASK | SOFT |
| 5 | "수정된 작업지시서 보여줘" | CONFIRM | REPLY | SOFT |
| 6 | "워크플로우 만들어줘" | CONFIRM/EXECUTE | CONFIRM | PASS |
| 7 | "워크플로우 만들어줘" | EXECUTE | EXECUTE | PASS |

## WorkOrder 구조 (T6 confirmationSummary)

25개 노드:
- 로그인 4개 (2 ACT + 2 TRANSFORM)
- 알림 조회 2개 (ACT)
- 알림 병합 1개 (TRANSFORM)
- REPEAT body 9개 (DECIDE x3 + ACT x3 + TRANSFORM x3)
- 후처리 9개 (TRANSFORM x5 + ACT x4: excel, pdf, mail)

REPEAT + DECIDE 3중 중첩 구조 우수.

## HLX 실행 결과

- **노드 수**: 16개 (기대: 30+) — HARD FAIL
- **실행**: 14/16 성공, REPEAT body 비어있음
- **소요**: 15.8초
- **핵심 문제**: REPEAT body가 HLX에서 제대로 생성되지 않음

### 노드 실행 상세

| # | 노드 | 타입 | 시간 | 상태 |
|---|------|------|------|------|
| 1 | skymall-login | ACT | 2.3s | OK |
| 2 | skymall-token-extraction | TRANSFORM | 0.0s | OK |
| 3 | skystock-login | ACT | 2.1s | OK |
| 4 | skystock-token-extraction | TRANSFORM | 0.0s | OK |
| 5 | critical-alerts-retrieval | ACT | 1.6s | OK (8건) |
| 6 | warning-alerts-retrieval | ACT | 1.6s | OK (14건) |
| 7 | merge-alerts-and-tag-level | TRANSFORM | 1.0s | OK (텍스트만) |
| 8 | repeat-alert-processing | REPEAT | 0.0s | OK (body 없음/0회) |
| 9 | classify-success-failure | TRANSFORM | 0.7s | OK |
| 10 | aggregation | TRANSFORM | 0.7s | OK |
| 11 | excel-data-configuration | TRANSFORM | 0.7s | OK |
| 12 | write-excel | ACT | 1.2s | OK (42 bytes) |
| 13 | html-generation | TRANSFORM | 1.2s | OK |
| 14 | pdf-generation | ACT | 2.9s | FAIL (HTTP 403) |
| 15-16 | email nodes | - | - | 미도달 (abort) |

### 근본 원인

1. **REPEAT body 빈 문제**: mergedAlerts가 텍스트 설명 (`"Merged criticalAlerts..."`)이어서 반복할 배열 데이터가 없음
2. **HLX 생성기 단순화**: WorkOrder의 25노드 REPEAT+DECIDE 구조가 16노드 flat 구조로 축소됨
3. **TRANSFORM 한계**: LLM TRANSFORM이 실제 구조화된 데이터가 아닌 텍스트 설명만 출력

## Hard Assert

| 항목 | 결과 | 비고 |
|------|------|------|
| 30+ 노드 | **FAIL** | 16개 (절반 이하) |
| REPEAT 내 DECIDE | **FAIL** | REPEAT body 비어있음 |
| skymall/skystock 로그인 | **PASS** | 양쪽 JWT 발급 성공 |
| CRITICAL/WARNING 조회 | **PASS** | 8건 + 14건 |
| 발주 생성 (POST) | **FAIL** | REPEAT 미실행으로 미도달 |
| Excel 생성 | **PASS** | 파일 생성됨 (42 bytes) |
| PDF 생성 | **FAIL** | HTTP 403 |
| 이메일 발송 | **FAIL** | 미도달 |
| PLUGIN 3종 | **FAIL** | spreadsheet만 성공 |

## 판정: HARD FAIL

- WorkOrder 구조는 우수 (REPEAT+DECIDE 3중 중첩)
- HLX 생성기가 복잡한 REPEAT/DECIDE 구조를 적절히 변환하지 못함
- TRANSFORM 노드의 텍스트-only 출력이 후속 노드 실행을 방해

## 식별된 구조적 한계

1. **HLX 생성기**: WorkOrder의 REPEAT/DECIDE 중첩 구조를 flat으로 단순화
2. **TRANSFORM 데이터 전달**: LLM이 텍스트 설명만 출력, 구조화된 JSON 미생성
3. **Plugin ACT 연동**: TRANSFORM → Plugin ACT 간 데이터 형식 불일치
