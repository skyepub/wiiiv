# Phase 5: 워크플로우 라이프사이클 (HLX Full Cycle)

> **검증 목표**: 인터뷰 → Spec → 작업지시서 → HLX 생성 → 실행 → 저장 → 재실행
> **핵심 관심사**: 인터뷰 품질, Spec 정확성, HLX 자동생성, 저장/복원 무결성
> **전제**: Phase 4 통과 (API 호출, 다중 Executor 작동 확인)

---

## Case 1: 단순 워크플로우 — 2 step (인터뷰 최소화)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 카테고리 목록을 가져와서 /tmp/wiiiv-test-v2/cat-list.txt로 저장해줘" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "응 실행해" | EXECUTE |

**Hard Assert**:
- HLX 워크플로우: ACT(API 호출) → ACT(파일 저장)
- 파일 생성됨

**Soft Assert**:
- 인터뷰 없이 바로 실행 (충분히 명확한 요청)

**의도**: 요청이 충분히 명확하면 인터뷰를 건너뛰는지

---

## Case 2: 인터뷰 → Spec 수집 (5턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "정기적으로 skymall 재고 현황을 체크해서 보고서를 만들고 싶어" | ASK |
| 2 | "재고 30개 미만인 상품을 대상으로" | ASK |
| 3 | "보고서는 CSV 형식으로 /tmp/reports/ 폴더에 저장" | ASK 또는 CONFIRM |
| 4 | "파일명은 stock-report-날짜.csv로 해줘" | CONFIRM |
| 5 | "응 실행해" | EXECUTE |

**Hard Assert**:
- Turn 1: 즉시 EXECUTE 금지 (정보 부족)
- Turn 4 또는 그 이전: CONFIRM (Spec 완성)
- Turn 5: EXECUTE

**Soft Assert**:
- 각 ASK 턴에서 누락된 정보를 정확히 질문
- CONFIRM 시 수집된 Spec 요약 표시

**의도**: Governor의 인터뷰 능력 — 점진적 Spec 수집

---

## Case 3: 작업지시서 확인 — Spec 정확성 (CONFIRM 응답 검증)

> Case 2의 CONFIRM 단계를 상세 검증

**Hard Assert** (CONFIRM 응답 내용):
- intent: 재고 현황 체크 + 보고서 생성
- 실행 step 목록이 논리적으로 올바름:
  1. skymall 로그인
  2. 재고 부족 상품 조회
  3. 데이터 가공 (CSV 변환)
  4. 파일 저장

**Soft Assert**:
- 사용자가 입력한 조건(30개 미만, CSV, /tmp/reports/)이 모두 반영
- 빠진 조건 없음

---

## Case 4: HLX 워크플로우 생성 — 구조 검증

> Case 2~3 실행 시 생성되는 HLX 구조

**Hard Assert**:
- HLX 워크플로우가 유효한 6노드 구조
- ACT 노드에 BlueprintStep이 포함
- 노드 간 연결(next)이 논리적

**Soft Assert**:
- START → ACT(login) → TRANSFORM(extract-token) → ACT(query) → TRANSFORM(csv-format) → ACT(file-write) → END
- 또는 이에 준하는 논리적 흐름

---

## Case 5: 분기 포함 워크플로우 — 조건부 실행 (3턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 재고 부족 상품을 찾아서, 10개 미만이면 '긴급'이라고 표시하고 10~30개면 '주의'라고 표시해서 보여줘" | ASK 또는 CONFIRM |
| 2 | (필요시 추가 정보) | CONFIRM |
| 3 | "실행해" | EXECUTE |

**Hard Assert**:
- HLX에 BRANCH 노드 포함
- 분기 조건: stock < 10 → "긴급", 10 ≤ stock < 30 → "주의"

**Soft Assert**:
- 실행 결과에 "긴급"/"주의" 라벨 포함
- 분기가 올바르게 적용됨

**의도**: HLX의 BRANCH 노드 실전 동작 검증

---

## Case 6: 반복 포함 워크플로우 — 다중 API 호출 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall의 모든 카테고리를 하나씩 순회하면서 각 카테고리의 상품 수를 세어줘" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**:
- HLX에 LOOP 노드 포함
- 카테고리 수만큼 반복 실행

**Soft Assert**:
- 8개 카테고리 각각의 상품 수 표시

**의도**: HLX의 LOOP 노드 실전 동작 검증

---

## Case 7: 복합 Executor 워크플로우 — FILE + API + COMMAND (3턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 전체 상품 목록을 가져와서 JSON으로 /tmp/wiiiv-test-v2/products.json에 저장하고, wc -l로 줄 수를 세어줘" | ASK 또는 CONFIRM |
| 2 | (필요시) "john_doe 계정 사용" | CONFIRM |
| 3 | "실행" | EXECUTE |

**Hard Assert**:
- 3가지 Executor 사용: API_CALL + FILE_WRITE + COMMAND
- 파일 생성됨
- 줄 수 결과 반환

**Audit Assert**:
- 단일 워크플로우 실행에 대한 Audit 레코드

**의도**: 하나의 워크플로우에서 3종 이상의 Executor가 조합되는 시나리오

---

## Case 8: 워크플로우 저장 (1턴)

> Case 7 실행 후 동일 세션에서

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "방금 실행한 워크플로우를 'product-export'라는 이름으로 저장해줘" | EXECUTE 또는 REPLY |

**Hard Assert**:
- 워크플로우가 저장됨 (HLX 저장소에 등록)

**검증**:
```bash
curl -s http://localhost:8235/api/v2/hlx/workflows \
  -H "Authorization: Bearer $TOKEN" | jq '.data[] | select(.name == "product-export")'
```

**의도**: HLX 워크플로우의 영속성 — 실행 후 저장 가능 여부

---

## Case 9: 워크플로우 재로딩 + 재실행 (새 세션)

> 새 세션 생성 후

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "'product-export' 워크플로우를 다시 실행해줘" | EXECUTE |

**Hard Assert**:
- 저장된 워크플로우 로드
- 동일한 실행 결과

**검증**:
```bash
cat /tmp/wiiiv-test-v2/products.json | jq length  # → 이전과 동일
```

**의도**: 저장 → 로드 → 재실행의 전체 라이프사이클이 무결한지

---

## Case 10: 다중 시스템 통합 워크플로우 — skymall + skystock + 파일 (4턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 재고 부족 상품을 찾고, skystock에서 해당 상품의 최근 발주 정보를 확인해서, 발주가 필요한 상품 목록을 /tmp/wiiiv-test-v2/reorder-report.csv로 만들어줘" | ASK |
| 2 | "재고 30개 미만 기준, CSV에는 상품명/현재재고/최근발주일 포함" | CONFIRM |
| 3 | "실행해" | EXECUTE |
| 4 | "결과 파일 내용 보여줘" | EXECUTE |

**Hard Assert**:
- skymall API + skystock API + FILE_WRITE 모두 실행
- CSV 파일 생성됨
- Turn 4에서 파일 내용 표시

**Soft Assert**:
- CSV 헤더: 상품명, 현재재고, 최근발주일
- 데이터 행이 1개 이상

**Audit Assert**:
- 복합 워크플로우 Audit 레코드
- Turn 4의 FILE_READ Audit 레코드

**의도**: Phase 5의 피날레 — 다중 시스템 + 다중 Executor + 파일 출력의 엔드투엔드 시나리오
