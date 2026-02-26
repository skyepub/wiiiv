# Phase 5: 워크플로우 라이프사이클 (HLX Full Cycle) — v2

> **검증 목표**: 대규모 HLX 워크플로우 생성 → 실행 → 저장 → 로딩 → 재실행 → 서브워크플로우 조합
> **핵심 관심사**: 대규모 워크플로우 생성 품질, 저장/복원 무결성, 서브워크플로우 조합, 파일 기반 라이프사이클
> **전제**: Phase 4 통과 (API 호출, 다중 Executor 작동 확인)
> **백엔드**: skymall (home.skyepub.net:9090), skystock (home.skyepub.net:9091)
> **저장소**: `~/.wiiiv/hlx/` (워크플로우 파일), `~/.wiiiv/data/` (DB)

---

## 케이스 구조 (28 cases)

| 섹션 | 범위 | 케이스 수 | 핵심 |
|------|------|-----------|------|
| A | 단순 워크플로우 (워밍업) | 2 | 기본 경로 확인 |
| B | 중규모 인터뷰 → 워크플로우 | 3 | 점진적 Spec 수집, 노드 타입별 검증 |
| **C** | **대규모 워크플로우 — 단일 시스템** | **3** | **10+ 노드, 분기/반복/에러처리 복합** |
| **D** | **대규모 워크플로우 — 크로스 시스템** | **3** | **15+ 노드, 멀티시스템 인증, 데이터 조합** |
| **E** | **저장 + 로딩 + 재실행** | **4** | **DB 저장, 파일 Export, 파일 Import, 재실행** |
| **F** | **서브워크플로우 조합** | **4** | **워크플로우 간 호출, inputMapping/outputMapping** |
| G | 워크플로우 수정 + 버전 관리 | 3 | 실행 후 수정, 이름 변경, 삭제 |
| **H** | **대규모 크로스 + 플러그인 (풀 인터뷰)** | **3** | **10~12턴 인터뷰, WorkOrder, 플러그인 4종, 25~40 노드** |
| **I** | **Executor 풀 결합 (풀 인터뷰)** | **3** | **10~12턴, API+FILE+COMMAND+플러그인 혼합, 전 Executor 관통** |

---

## A. 단순 워크플로우 (워밍업)

### Case 1: skymall 단순 워크플로우 — 2 step (인터뷰 최소화)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 카테고리 목록을 가져와서 ~/.wiiiv/projects/test/cat-list.txt로 저장해줘" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "응 실행해" | EXECUTE |

**Hard Assert**:
- HLX 워크플로우: ACT(API 호출 GET /api/categories) → ACT(파일 저장)
- 파일 생성됨

**Soft Assert**:
- 인터뷰 없이 바로 실행 (충분히 명확한 요청)
- skymall 카테고리 API는 인증 불필요 → 로그인 step 없음

**의도**: 요청이 충분히 명확하면 인터뷰를 건너뛰는지, 인증 불필요 API를 올바르게 처리하는지

---

### Case 2: skystock 단순 워크플로우 — 인증 포함 2 step

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock에서 활성 공급사 목록을 조회해서 ~/.wiiiv/projects/test/suppliers.json으로 저장해줘" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**:
- skystock 로그인 (POST /api/auth/login) → GET /api/suppliers/active → FILE_WRITE
- skystock은 모든 API가 인증 필요 → 로그인 step 필수
- JSON 파일 생성됨

**Soft Assert**:
- skystock 계정 정보(admin/admin123)를 RAG에서 올바르게 가져옴
- skymall이 아닌 skystock(9091)으로 호출

**의도**: 인증 필요 API에 대한 로그인 step 자동 생성 검증

---

## B. 중규모 인터뷰 → 워크플로우

### Case 3: 인터뷰 → Spec 수집 — skymall 재고 보고서 (5턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "정기적으로 skymall 재고 현황을 체크해서 보고서를 만들고 싶어" | ASK |
| 2 | "재고 30개 미만인 상품을 대상으로" | ASK |
| 3 | "보고서는 CSV 형식으로 ~/.wiiiv/projects/test/reports/ 폴더에 저장" | ASK 또는 CONFIRM |
| 4 | "파일명은 stock-report.csv로 해줘" | CONFIRM |
| 5 | "응 실행해" | EXECUTE |

**Hard Assert**:
- Turn 1: 즉시 EXECUTE 금지 (정보 부족)
- Turn 4 또는 그 이전: CONFIRM (Spec 완성)
- Turn 5: EXECUTE
- skymall 로그인 → GET /api/products/low-stock?threshold=30 → CSV 변환 → FILE_WRITE

**HLX 노드 최소 기대**:
- ACT(로그인) → ACT(API 조회) → TRANSFORM(CSV 변환) → ACT(파일 저장) — 4+ 노드

**의도**: Governor의 인터뷰 능력 — 점진적 Spec 수집

---

### Case 4: 분기 포함 워크플로우 — skystock 재고알림 레벨 분류 (3턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock 재고알림을 레벨별로 분류해서 보여줘. CRITICAL이면 '즉시 발주 필요', WARNING이면 '주의 관찰', NORMAL이면 '정상'으로 표시해줘" | ASK 또는 CONFIRM |
| 2 | (필요시 추가 정보) | CONFIRM |
| 3 | "실행해" | EXECUTE |

**Hard Assert**:
- HLX에 DECIDE 노드 포함 (또는 TRANSFORM에서 분기 로직)
- skystock 로그인 → GET /api/stock-alerts → 레벨별 분류
- 분기 조건: CRITICAL → "즉시 발주 필요", WARNING → "주의 관찰", NORMAL → "정상"

**HLX 노드 최소 기대**:
- ACT(로그인) → ACT(조회) → DECIDE(레벨분류) → TRANSFORM(라벨링) — 4+ 노드

**의도**: HLX DECIDE 노드 실전 동작 + 도메인 로직 검증

---

### Case 5: 반복 포함 워크플로우 — skymall 카테고리별 상품 집계 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall의 모든 카테고리를 하나씩 순회하면서 각 카테고리의 상품 수를 세어줘" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**:
- HLX에 REPEAT 노드 포함 (또는 카테고리 summary API 사용)
- GET /api/categories → 각 카테고리에 대해 GET /api/categories/{id}/products

**HLX 노드 최소 기대**:
- ACT(카테고리 목록) → REPEAT(각 카테고리) { ACT(상품 조회) → TRANSFORM(카운트) } → TRANSFORM(집계) — 5+ 노드

**의도**: HLX REPEAT 노드 실전 동작 검증

---

## C. 대규모 워크플로우 — 단일 시스템

### Case 6: skymall 종합 운영 보고서 ⚡ (5턴, 10+ 노드)

> **난이도**: ★★★★
> **목표**: 단일 시스템에서 10개 이상의 HLX 노드를 가진 복합 워크플로우 생성
> **기대 노드 수**: 10~15개

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall의 종합 운영 현황 보고서를 자동 생성하는 워크플로우를 만들어줘" | ASK |
| 2 | "보고서 내용: 1) 카테고리별 상품 수/평균가격, 2) 재고 30개 미만 상품 목록, 3) 최근 주문 현황(2025~2026), 4) 미판매 상품 목록, 5) 전체 요약" | ASK 또는 CONFIRM |
| 3 | "결과는 Markdown 형식으로 ~/.wiiiv/projects/test/skymall-ops-report.md에 저장. jane_smith/pass1234 계정 사용" | CONFIRM |
| 4 | "실행해" | EXECUTE |

**Hard Assert**:
- **최소 10개 노드**:
  1. ACT(로그인 POST /api/auth/login)
  2. ACT(카테고리 요약 GET /api/categories/summary)
  3. ACT(재고부족 GET /api/products/low-stock?threshold=30)
  4. ACT(주문 리포트 GET /api/orders/report?from=...&to=...)
  5. ACT(미판매 상품 GET /api/products/unsold)
  6. TRANSFORM(카테고리 데이터 표 형식화)
  7. TRANSFORM(재고부족 데이터 정리)
  8. TRANSFORM(주문 데이터 요약)
  9. TRANSFORM(전체 섹션 병합 → Markdown)
  10. ACT(파일 저장)
- Markdown 파일 생성됨
- 5개 API 엔드포인트 모두 호출됨

**HLX 구조 검증**:
- 각 OBSERVE/ACT 노드가 구체적 API 호출 정보 포함
- TRANSFORM 노드에 hint(filter/map/merge/summarize) 적절히 사용
- 에러 시 onError 정책 포함 (최소 1개)

**의도**: 단일 시스템에서 다양한 데이터를 수집-변환-병합-저장하는 복합 워크플로우

---

### Case 7: skystock 발주 자동화 워크플로우 ⚡ (5턴, 12+ 노드)

> **난이도**: ★★★★★
> **목표**: 분기(DECIDE) + 반복(REPEAT) + 에러처리(onError)가 모두 포함된 대규모 워크플로우
> **기대 노드 수**: 12~18개

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock에서 재고 알림을 기반으로 자동 발주 프로세스를 만들어줘" | ASK |
| 2 | "전체 흐름: 1) CRITICAL/WARNING 알림 조회, 2) 각 알림에 대해 공급사 조회, 3) CRITICAL이면 즉시 발주 생성(수량 100), WARNING이면 발주 필요 목록에만 추가, 4) 발주 결과를 CSV로 저장" | ASK 또는 CONFIRM |
| 3 | "발주 생성 실패 시 skip하고 다음 상품 진행. 최종 CSV는 ~/.wiiiv/projects/test/auto-reorder-result.csv. admin/admin123 사용" | CONFIRM |
| 4 | "실행해" | EXECUTE |

**Hard Assert**:
- **최소 12개 노드**:
  1. ACT(로그인)
  2. ACT(CRITICAL 알림 조회 GET /api/stock-alerts/level/CRITICAL)
  3. ACT(WARNING 알림 조회 GET /api/stock-alerts/level/WARNING)
  4. TRANSFORM(알림 병합 + 레벨 태깅)
  5. REPEAT(각 알림에 대해) {
     6. ACT(공급사 조회 GET /api/suppliers/by-product/{skymallProductId})
     7. DECIDE(레벨이 CRITICAL인가?)
     8. → YES: ACT(발주 생성 POST /api/purchase-orders, onError: "skip")
     9. → NO: TRANSFORM(대기 목록 추가)
  }
  10. TRANSFORM(발주 결과 집계)
  11. TRANSFORM(CSV 형식 변환)
  12. ACT(파일 저장)
- REPEAT 내부에 DECIDE 포함 (중첩 구조)
- onError: "skip" 또는 "retry:2 then skip" 포함
- CSV 파일 생성됨

**HLX 구조 검증**:
- REPEAT.body 안에 ACT + DECIDE + ACT/TRANSFORM 중첩
- DECIDE.branches에 "critical" → jumpTo, "warning" → jumpTo 분기
- 발주 ACT 노드에 onError 정책 명시

**의도**: 분기+반복+에러처리가 모두 조합된 실전 워크플로우 — HLX 엔진의 모든 노드 타입 활용

---

### Case 8: skymall 가격 분석 + 카테고리 최적화 제안 ⚡ (4턴, 10+ 노드)

> **난이도**: ★★★★
> **목표**: OBSERVE/TRANSFORM 중심의 분석 워크플로우, LLM 판단 포함
> **기대 노드 수**: 10~14개

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 전체 상품을 분석해서 카테고리별 가격 분포, 재고 현황, 매출 기여도를 종합 분석하고 카테고리 최적화 제안을 해줘" | ASK |
| 2 | "분석 항목: 카테고리별 상품 수/평균가격/가격범위, 재고 부족 비율, 미판매 상품 비율. 최적화 제안은 AI가 데이터 기반으로 만들어줘. jane_smith/pass1234, 결과는 ~/.wiiiv/projects/test/category-optimization.md" | CONFIRM |
| 3 | "실행해" | EXECUTE |

**Hard Assert**:
- **최소 10개 노드**:
  1. ACT(로그인)
  2. ACT(카테고리 요약)
  3. ACT(전체 상품 조회)
  4. ACT(재고부족 상품)
  5. ACT(미판매 상품)
  6. TRANSFORM(카테고리별 가격 분포 계산, hint: aggregate)
  7. TRANSFORM(재고 부족 비율 계산, hint: aggregate)
  8. TRANSFORM(미판매 비율 계산, hint: aggregate)
  9. TRANSFORM(데이터 병합, hint: merge) — **aiRequired: true** (LLM이 최적화 제안 생성)
  10. ACT(파일 저장)
- TRANSFORM 노드 중 최소 1개는 `aiRequired: true` (LLM 판단 필요)
- Markdown 파일에 데이터 기반 분석 결과 + AI 제안 포함

**의도**: 데이터 수집 → 통계 변환 → AI 분석의 3단계 파이프라인

---

## D. 대규모 워크플로우 — 크로스 시스템

### Case 9: 크로스 시스템 재고 위험 분석 파이프라인 ⚡ (5턴, 15+ 노드)

> **난이도**: ★★★★★
> **목표**: skymall + skystock 양쪽 데이터를 수집-조합-분석하는 대규모 파이프라인
> **기대 노드 수**: 15~20개

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall과 skystock 양쪽 데이터를 종합 분석하는 재고 위험 분석 워크플로우를 만들어줘" | ASK |
| 2 | "분석 단계: 1) skymall에서 재고 부족 상품 + 매출 리포트 조회, 2) skystock에서 CRITICAL/WARNING 알림 + 공급사 성과 + 대시보드 조회, 3) 상품별로 skymall 재고/매출 + skystock 알림/공급사 데이터 조합, 4) 위험도 분류(HIGH/MEDIUM/LOW), 5) CSV + Markdown 이중 출력" | ASK 또는 CONFIRM |
| 3 | "skymall: jane_smith/pass1234, skystock: admin/admin123. HIGH 기준: 재고 10 미만 AND CRITICAL 알림. MEDIUM: 재고 30 미만 OR WARNING 알림. 나머지 LOW. 출력: ~/.wiiiv/projects/test/risk-analysis.csv + ~/.wiiiv/projects/test/risk-analysis.md" | CONFIRM |
| 4 | "실행해" | EXECUTE |

**Hard Assert**:
- **최소 15개 노드**:
  1. ACT(skymall 로그인 9090)
  2. ACT(skymall 재고부족 조회)
  3. ACT(skymall 매출 리포트)
  4. ACT(skystock 로그인 9091)
  5. ACT(skystock CRITICAL 알림)
  6. ACT(skystock WARNING 알림)
  7. ACT(skystock 공급사 성과)
  8. ACT(skystock 대시보드)
  9. TRANSFORM(skymall 데이터 정규화)
  10. TRANSFORM(skystock 알림 병합)
  11. TRANSFORM(skymall+skystock 데이터 조합, hint: merge)
  12. DECIDE(위험도 분류: HIGH/MEDIUM/LOW)
  13. TRANSFORM(CSV 형식 변환)
  14. ACT(CSV 파일 저장)
  15. TRANSFORM(Markdown 리포트 생성, aiRequired: true)
  16. ACT(Markdown 파일 저장)
- skymall(9090)과 skystock(9091) 각각 별도 로그인
- CSV + Markdown 두 파일 모두 생성됨
- DECIDE 노드에 HIGH/MEDIUM/LOW 3가지 분기

**의도**: 크로스 시스템 대규모 파이프라인 — 양쪽 인증 분리, 다중 API, 데이터 조합, 분류, 이중 출력

---

### Case 10: 크로스 시스템 자동 발주 + 결과 보고 ⚡⚡ (5턴, 18+ 노드)

> **난이도**: ★★★★★★
> **목표**: Phase 5의 피날레 — 크로스 시스템 데이터 수집 → 분기/반복 → 쓰기 액션 → 보고
> **기대 노드 수**: 18~25개

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 재고 부족 상품을 자동으로 skystock에 발주하고, 발주 결과를 종합 보고서로 만드는 전체 자동화 워크플로우를 만들어줘" | ASK |
| 2 | "단계별: 1) skymall에서 재고 30 미만 상품 조회, 2) 각 상품에 대해 skystock에서 공급사 조회, 3) 공급사가 있으면 → 발주 생성(수량 100), 없으면 → '공급사 미등록' 표시, 4) 발주 결과를 상품별로 정리, 5) 전체 요약 보고서(총 발주건수, 총 비용, 실패 목록) 생성, 6) CSV + Markdown 저장" | ASK 또는 CONFIRM |
| 3 | "skymall은 인증 불필요(low-stock API). skystock은 admin/admin123. 발주 실패 시 skip. 보고서 경로: ~/.wiiiv/projects/test/auto-reorder-report.csv + ~/.wiiiv/projects/test/auto-reorder-report.md" | CONFIRM |
| 4 | "실행해" | EXECUTE |

**Hard Assert**:
- **최소 18개 노드**:
  1. ACT(skystock 로그인)
  2. ACT(skymall 재고부족 조회 GET /api/products/low-stock — 인증 없음)
  3. TRANSFORM(상품 목록 추출)
  4. REPEAT(각 상품에 대해) {
     5. ACT(skystock 공급사 조회 GET /api/suppliers/by-product/{id})
     6. DECIDE(공급사 존재 여부)
     7. → 있음: TRANSFORM(발주 데이터 구성)
     8. → 있음: ACT(발주 생성 POST /api/purchase-orders, onError: "skip")
     9. → 없음: TRANSFORM(미등록 표시)
  }
  10. TRANSFORM(발주 성공/실패 분류, hint: filter)
  11. TRANSFORM(총 발주건수/비용 집계, hint: aggregate)
  12. TRANSFORM(실패 목록 정리)
  13. TRANSFORM(CSV 형식 변환)
  14. ACT(CSV 저장)
  15. TRANSFORM(Markdown 보고서 생성, aiRequired: true)
  16. ACT(Markdown 저장)
- REPEAT 내부에 ACT + DECIDE + ACT/TRANSFORM 중첩
- onError 정책 포함
- skymall은 인증 없이, skystock은 인증 포함
- CSV + Markdown 두 파일 생성

**HLX 구조 검증**:
- REPEAT.body 내 최소 4개 노드 (ACT, DECIDE, ACT/TRANSFORM 분기)
- DECIDE.branches에 "found" → ACT(발주), "not_found" → TRANSFORM(미등록)
- 전체 워크플로우 JSON 크기: 3000+ 문자 (대규모)

**의도**: 읽기+쓰기+분기+반복+에러처리+이중출력 — HLX의 모든 역량을 동원하는 최대 규모 워크플로우

---

### Case 11: 크로스 시스템 매출-재고-공급사 360도 분석 ⚡ (4턴, 12+ 노드)

> **난이도**: ★★★★★
> **목표**: 3종 데이터 소스(매출/재고/공급사) 조합 + AI 분석 요약

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 매출 데이터, skystock 재고 알림, skystock 공급사 성과를 모두 조합해서 360도 분석 보고서를 만들어줘. 2025~2026 기간, 결과는 ~/.wiiiv/projects/test/360-analysis.md" | ASK 또는 CONFIRM |
| 2 | "skymall: jane_smith/pass1234, skystock: admin/admin123. 분석: 상품별 매출-재고-공급사 종합, AI가 패턴/이상치 분석해서 액션 아이템 제안" | CONFIRM |
| 3 | "실행해" | EXECUTE |

**Hard Assert**:
- skymall 로그인 + 매출 리포트 + 재고부족 + 미판매 상품 조회
- skystock 로그인 + 재고알림 전체 + 공급사 성과 조회
- TRANSFORM(merge) 노드에서 상품 ID 기반 조합
- TRANSFORM(aiRequired: true) 노드에서 AI 분석 생성
- Markdown 파일에 데이터 테이블 + AI 인사이트 포함

**의도**: 다중 시스템 데이터 통합 + AI 분석의 실전 시나리오

---

## E. 저장 + 로딩 + 재실행 (워크플로우 라이프사이클)

### Case 12: 워크플로우 DB 저장 (1턴)

> Case 10(자동 발주 워크플로우) 실행 후 동일 세션에서

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "방금 실행한 워크플로우를 'auto-reorder-pipeline'이라는 이름으로 저장해줘" | EXECUTE 또는 REPLY |

**Hard Assert**:
- 워크플로우가 DB에 저장됨 (hlx_workflows 테이블)
- 이름이 'auto-reorder-pipeline'

**검증**:
```bash
curl -s http://localhost:8235/api/v2/workflows \
  -H "Authorization: Bearer $TOKEN" | jq '.data[] | select(.name == "auto-reorder-pipeline")'
```

**의도**: 대규모 워크플로우(18+ 노드)의 DB 영속성 검증

---

### Case 13: 워크플로우 파일 Export (1턴)

> Case 12 이후 동일 세션

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "'auto-reorder-pipeline' 워크플로우를 ~/.wiiiv/hlx/auto-reorder-pipeline.hlx로 내보내줘" | EXECUTE |

**Hard Assert**:
- `~/.wiiiv/hlx/auto-reorder-pipeline.hlx` 파일 생성됨
- 파일 내용이 유효한 HLX JSON
- `HlxParser.parse(fileContent)` 성공

**검증**:
```bash
cat ~/.wiiiv/hlx/auto-reorder-pipeline.hlx | python3 -m json.tool
# nodes 배열에 18+ 노드 포함 확인
```

**의도**: DB 저장된 워크플로우를 파일 시스템으로 내보내기 — 백업/공유/버전관리 용도

---

### Case 14: 워크플로우 파일 Import + 실행 (새 세션, 2턴)

> 새 세션 생성 후, Case 13에서 내보낸 파일 사용

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "~/.wiiiv/hlx/auto-reorder-pipeline.hlx 파일의 워크플로우를 로드해서 실행해줘" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**:
- .hlx 파일에서 워크플로우 파싱 성공
- 모든 노드(18+)가 순서대로 실행
- skymall + skystock 양쪽 API 호출 발생
- CSV + Markdown 파일 재생성됨

**검증**:
```bash
# CLI에서:
/hlx create ~/.wiiiv/hlx/auto-reorder-pipeline.hlx
/hlx run <id>
```

**의도**: 파일 기반 워크플로우 로드 → 실행 — 다른 서버, 다른 세션에서도 동일 워크플로우 재현 가능

---

### Case 15: DB 로딩 + 재실행 (새 세션, 1턴)

> 새 세션 생성 후

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "'auto-reorder-pipeline' 워크플로우를 다시 실행해줘" | EXECUTE |

**Hard Assert**:
- DB에서 이름으로 워크플로우 로드 (findByName)
- 전체 노드 실행
- 이전 실행과 동일한 구조의 결과 파일 생성

**의도**: DB 저장 → 로드 → 재실행 전체 라이프사이클 — 서버 재시작 후에도 워크플로우 유지

---

## F. 서브워크플로우 조합

### Case 16: 서브워크플로우용 빌딩 블록 — skymall 인증 모듈 (2턴)

> 다른 워크플로우에서 재사용할 인증 서브워크플로우 생성

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 로그인을 하고 JWT 토큰을 반환하는 작은 워크플로우를 만들어줘. 이름은 'skymall-auth'. jane_smith/pass1234로 POST /api/auth/login 호출" | CONFIRM 또는 EXECUTE |
| 2 | (실행 후) "이 워크플로우를 'skymall-auth'로 저장해줘" | EXECUTE |

**Hard Assert**:
- 2~3개 노드: ACT(로그인) → TRANSFORM(토큰 추출)
- output 매핑: "token" → JWT 토큰값
- DB에 'skymall-auth' 이름으로 저장됨

**의도**: 서브워크플로우의 최소 단위 — 인증 모듈을 독립 워크플로우로 분리

---

### Case 17: 서브워크플로우용 빌딩 블록 — skystock 인증 모듈 (2턴)

> Case 16과 동일한 패턴, skystock용

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock 로그인 워크플로우도 만들어줘. 이름 'skystock-auth'. admin/admin123, POST /api/auth/login" | CONFIRM 또는 EXECUTE |
| 2 | "저장해줘" | EXECUTE |

**Hard Assert**:
- 'skystock-auth' 이름으로 DB 저장
- 실행하면 skystock JWT 토큰 반환

---

### Case 18: 서브워크플로우 조합 — 크로스 시스템 재고 체크 ⚡ (4턴, SUBWORKFLOW 노드 사용)

> Case 16, 17에서 만든 인증 모듈을 서브워크플로우로 호출

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 재고 부족 상품을 조회하고, 각 상품의 skystock 공급사를 확인하는 워크플로우를 만들어줘. 로그인은 이미 저장된 'skymall-auth'와 'skystock-auth' 워크플로우를 사용해" | ASK 또는 CONFIRM |
| 2 | "결과를 ~/.wiiiv/projects/test/cross-stock-check.csv로 저장해줘" | CONFIRM |
| 3 | "실행해" | EXECUTE |

**Hard Assert**:
- **SUBWORKFLOW 노드 2개 포함**:
  - SUBWORKFLOW(workflowRef: "skymall-auth", outputMapping: {"token" → "skymall_token"})
  - SUBWORKFLOW(workflowRef: "skystock-auth", outputMapping: {"token" → "skystock_token"})
- 이후 ACT 노드들이 서브워크플로우에서 받은 토큰 사용
- REPEAT(각 상품) { ACT(skystock 공급사 조회) }
- CSV 파일 생성됨

**HLX 구조 검증**:
- SUBWORKFLOW.inputMapping / outputMapping 올바르게 구성
- 서브워크플로우 실행 → 메인 워크플로우 context에 토큰 주입
- depth 제한 (순환 참조 방지)

**의도**: 서브워크플로우의 실전 활용 — 인증 모듈을 재사용 가능한 빌딩 블록으로 분리/조합

---

### Case 19: 서브워크플로우 조합 — 3레벨 중첩 ⚡⚡ (4턴, 깊은 중첩)

> **난이도**: ★★★★★★
> **목표**: 서브워크플로우가 또 다른 서브워크플로우를 호출하는 3레벨 중첩

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 재고 부족 상품을 찾아서, skystock 공급사별로 묶고, 공급사별 발주서를 생성하는 워크플로우를 만들어줘" | ASK |
| 2 | "구조: 메인 워크플로우 → 'stock-check' 서브워크플로우(skymall 재고조회, 이것도 skymall-auth를 서브워크플로우로 호출) → 메인의 REPEAT에서 공급사별 발주. skystock은 skystock-auth 서브워크플로우 사용" | ASK 또는 CONFIRM |
| 3 | "발주 결과를 ~/.wiiiv/projects/test/supplier-orders.csv로 저장. 발주 실패 시 skip" | CONFIRM |
| 4 | "실행해" | EXECUTE |

**Hard Assert**:
- **3레벨 중첩**: 메인 → stock-check → skymall-auth
- 메인 워크플로우에 SUBWORKFLOW 노드 최소 2개 (stock-check, skystock-auth)
- stock-check 워크플로우 내에 SUBWORKFLOW(skymall-auth) 포함
- depth 제한 내에서 정상 실행 (기본 depth 제한: 5)
- CSV 파일 생성됨

**HLX 구조 검증**:
- HlxRunner depth tracking: main(0) → stock-check(1) → skymall-auth(2) — 3레벨
- visited set에 순환 참조 없음
- inputMapping/outputMapping이 레벨 간 올바르게 전달

**의도**: 서브워크플로우 깊은 중첩 — 모듈화의 극한 테스트

---

## G. 워크플로우 수정 + 버전 관리

### Case 20: 실행 후 워크플로우 수정 (3턴)

> Case 6(종합 운영 보고서) 실행 후 동일 세션

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "방금 워크플로우에 skymall 가격 범위별(1만 이하, 1~5만, 5~10만, 10만 이상) 상품 분포를 추가해줘" | CONFIRM |
| 2 | "실행" | EXECUTE |
| 3 | "'skymall-ops-report-v2'로 저장해줘" | EXECUTE |

**Hard Assert**:
- 기존 노드 유지 + 새 노드 추가 (ACT: price-range API + TRANSFORM: 분포 계산)
- 전체 노드 수 증가 (12+ → 14+)
- 실행 성공
- 'skymall-ops-report-v2' 이름으로 저장됨

**의도**: 기존 워크플로우에 단계 추가 — 증분 수정 능력

---

### Case 21: 워크플로우 이름 변경 + 목록 확인 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "저장된 워크플로우 목록을 보여줘" | REPLY |
| 2 | "'auto-reorder-pipeline'을 'daily-reorder-batch'로 이름 변경해줘" | EXECUTE |

**Hard Assert**:
- Turn 1: 저장된 워크플로우 목록 표시 (최소 auto-reorder-pipeline, skymall-auth, skystock-auth 포함)
- Turn 2: 이름 변경됨, 기존 이름으로 검색 시 없음, 새 이름으로 검색 시 있음

**검증**:
```bash
curl -s http://localhost:8235/api/v2/workflows \
  -H "Authorization: Bearer $TOKEN" | jq '.data[].name'
```

---

### Case 22: 워크플로우 삭제 + 확인 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "'skymall-ops-report-v2' 워크플로우를 삭제해줘" | EXECUTE |
| 2 | "저장된 워크플로우 목록 보여줘" | REPLY |

**Hard Assert**:
- Turn 1: 삭제 성공
- Turn 2: 목록에 'skymall-ops-report-v2' 없음
- 다른 워크플로우(daily-reorder-batch, skymall-auth, skystock-auth)는 여전히 존재

**의도**: 삭제가 대상 워크플로우만 제거하고 다른 워크플로우에 영향 없는지

---

## H. 대규모 크로스 시스템 + 플러그인 워크플로우 ⚡⚡ (풀 인터뷰)

> **공통 전제**:
> - WORKFLOW_CREATE 6차원 인터뷰 → 작업지시서(WorkOrder) → HLX 생성 → 실행 → 저장
> - skymall(home.skyepub.net:9090) + skystock(home.skyepub.net:9091) 크로스 시스템
> - 플러그인 Executor 활용: spreadsheet, pdf, webfetch, mail, cron
> - 10턴 이상의 점진적 Spec 수집

---

### Case 23: 크로스 시스템 월간 경영 보고서 자동 생성 ⚡⚡ (12턴, 25+ 노드)

> **난이도**: ★★★★★★
> **목표**: skymall 매출 + skystock 재고/공급사 데이터 → Excel + PDF 이중 보고서 생성
> **플러그인**: spreadsheet(write_excel), pdf(generate), webfetch(fetch_json)
> **기대 노드 수**: 25~35개

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
매달 경영 보고서를 자동으로 만들고 싶어
```
**기대**: ASK (어떤 보고서인지, 어떤 시스템인지 질문)

**Turn 2 — 도메인 + 시스템**
```
우리 쇼핑몰(skymall)과 재고관리(skystock) 두 시스템의 데이터를 종합하는 보고서야
```
**기대**: ASK (보고서에 포함할 항목 질문)

**Turn 3 — 보고서 항목 (skymall 측)**
```
skymall에서 수집할 데이터:
1. 카테고리별 상품 수/평균가격 (GET /api/categories/summary)
2. 재고 30개 미만 상품 (GET /api/products/low-stock?threshold=30)
3. 미판매 상품 (GET /api/products/unsold)
4. 2025~2026 매출 리포트 (GET /api/orders/report?from=2025-01-01T00:00:00&to=2026-12-31T23:59:59)
5. 전체 상품 가격 분포
```
**기대**: ASK (skystock 데이터, 출력 형식 등 추가 질문)

**Turn 4 — 보고서 항목 (skystock 측)**
```
skystock에서 수집할 데이터:
1. 대시보드 전체 현황 (GET /api/stats/dashboard)
2. CRITICAL/WARNING 재고알림 (GET /api/stock-alerts/level/CRITICAL, /WARNING)
3. 전체 공급사 성과 (GET /api/stats/supplier-performance)
4. 최근 발주 현황 (GET /api/purchase-orders)
```
**기대**: ASK (데이터 처리 방법, 출력 형식 질문)

**Turn 5 — 데이터 처리 흐름**
```
처리 흐름:
1. skymall/skystock 각각 로그인해서 데이터 수집 (병렬 아님, 순차)
2. 카테고리별 매출 vs 재고 위험도 교차 분석
3. 공급사 성과와 재고알림 연계 — CRITICAL 알림 상품의 공급사 fulfillmentRate 확인
4. 전체 데이터를 AI가 종합 분석해서 "경영 인사이트 3줄 요약" 생성
```
**기대**: ASK (출력 형식, 파일 경로, 에러처리 등 질문)

**Turn 6 — 출력 형식**
```
출력을 두 가지로 만들어줘:
1. Excel 파일: 시트별로 — (1) 카테고리 요약, (2) 재고위험 상품, (3) 공급사 성과, (4) 발주 현황
2. PDF 보고서: HTML 템플릿 기반 — 제목, 날짜, 각 섹션별 표, AI 인사이트 요약
```
**기대**: ASK (파일 경로, 인증정보, 에러처리 질문)

**Turn 7 — 인증 + 경로**
```
인증:
- skymall: jane_smith/pass1234 (home.skyepub.net:9090)
- skystock: admin/admin123 (home.skyepub.net:9091)

출력 경로:
- Excel: /tmp/wiiiv-test-v2/monthly-report.xlsx
- PDF: /tmp/wiiiv-test-v2/monthly-report.pdf
```
**기대**: ASK (에러처리, 추가 요구사항 질문)

**Turn 8 — 에러 처리**
```
에러 처리:
- API 호출 실패 시 retry:2 then skip (2번 재시도 후 건너뛰기)
- 데이터가 비어있으면 "데이터 없음"으로 표시하고 계속 진행
- Excel/PDF 생성 실패 시 abort (보고서가 반쪽짜리면 의미 없으니까)
```
**기대**: ASK 또는 CONFIRM (추가 질문 또는 WorkOrder 제시)

**Turn 9 — 추가 요구사항**
```
추가:
- PDF 보고서 첫 페이지에 "월간 경영 보고서 — {현재년월}" 제목
- webfetch로 skymall 헬스체크(GET http://home.skyepub.net:9090/actuator/health)부터 해서 서버 상태도 보고서에 포함
- Excel 파일명에 날짜 포함하면 좋겠는데, 일단 고정 경로로 하자
```
**기대**: CONFIRM (WorkOrder 제시)

**Turn 10 — WorkOrder 검토 + 수정 요청**
```
WorkOrder 보니까 공급사 성과에서 fulfillmentRate 50% 미만인 공급사를 "위험 공급사"로 따로 분류하는 게 빠졌어. 추가해줘
```
**기대**: CONFIRM (수정된 WorkOrder)

**Turn 11 — WorkOrder 확정**
```
좋아, 이제 워크플로우 만들어줘
```
**기대**: EXECUTE (HLX 생성 + 실행)

**Turn 12 — 저장**
```
'monthly-biz-report' 이름으로 저장해줘
```
**기대**: EXECUTE 또는 REPLY (워크플로우 저장)

#### WorkOrder 품질 체크 (18항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skymall | `"skymall" in wo` |
| 2 | skystock | `"skystock" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | 9091 | `"9091" in wo` |
| 5 | jane_smith | `"jane_smith" in wo` |
| 6 | admin/admin123 | `"admin" in wo and "admin123" in wo` |
| 7 | /api/categories/summary | `"/api/categories/summary" in wo` |
| 8 | /api/products/low-stock | `"/api/products/low-stock" in wo` |
| 9 | /api/stats/dashboard | `"/api/stats/dashboard" in wo` |
| 10 | /api/stats/supplier-performance | `"supplier-performance" in wo` |
| 11 | spreadsheet | `"spreadsheet" in wo.lower() or "excel" in wo.lower() or "write_excel" in wo.lower()` |
| 12 | pdf | `"pdf" in wo.lower() or "generate" in wo.lower()` |
| 13 | webfetch | `"webfetch" in wo.lower() or "health" in wo.lower()` |
| 14 | fulfillmentRate | `"fulfillment" in wo.lower() or "이행률" in wo` |
| 15 | retry:2 then skip | `"retry" in wo.lower() and "skip" in wo.lower()` |
| 16 | abort | `"abort" in wo.lower()` |
| 17 | AI 인사이트 | `"인사이트" in wo or "aiRequired" in wo.lower() or "ai" in wo.lower()` |
| 18 | monthly-report | `"monthly-report" in wo or "월간" in wo` |

**PASS 기준**: 15/18 이상

#### Hard Assert

- **최소 25개 노드**:
  1. ACT(webfetch: skymall 헬스체크)
  2. ACT(skymall 로그인)
  3. TRANSFORM(skymall 토큰 추출)
  4. ACT(카테고리 요약 조회)
  5. ACT(재고부족 상품 조회)
  6. ACT(미판매 상품 조회)
  7. ACT(매출 리포트 조회)
  8. ACT(skystock 로그인)
  9. TRANSFORM(skystock 토큰 추출)
  10. ACT(대시보드 조회)
  11. ACT(CRITICAL 알림 조회)
  12. ACT(WARNING 알림 조회)
  13. ACT(공급사 성과 조회)
  14. ACT(발주 현황 조회)
  15. TRANSFORM(카테고리 매출-재고 교차분석)
  16. TRANSFORM(공급사 성과 분류 — fulfillmentRate 50% 기준)
  17. TRANSFORM(CRITICAL 알림-공급사 연계)
  18. TRANSFORM(전체 데이터 병합, hint: merge)
  19. TRANSFORM(AI 종합 분석, aiRequired: true)
  20. TRANSFORM(Excel 시트 데이터 구성)
  21. ACT(spreadsheet: write_excel)
  22. TRANSFORM(HTML 보고서 템플릿 생성)
  23. ACT(pdf: generate)
  24. TRANSFORM(결과 요약)
- skymall(9090)과 skystock(9091) 별도 로그인
- Excel + PDF 두 파일 생성됨
- PLUGIN stepType 최소 3회 (webfetch 1 + spreadsheet 1 + pdf 1)

**HLX 구조 검증**:
- ACT 노드 중 PLUGIN stepType 사용 확인 (pluginId: "spreadsheet", "pdf", "webfetch")
- TRANSFORM(aiRequired: true) 최소 1개
- onError 정책: API 조회 → retry:2 then skip, 파일 생성 → abort

**의도**: 크로스 시스템 전체 데이터 수집 → 교차 분석 → 플러그인 이중 출력 — 풀 인터뷰 + WorkOrder 기반 대규모 워크플로우

---

### Case 24: 크로스 시스템 자동 발주 + 보고서 + 이메일 알림 파이프라인 ⚡⚡ (11턴, 30+ 노드)

> **난이도**: ★★★★★★★
> **목표**: CRITICAL 알림 기반 자동 발주 → 발주 결과를 Excel/PDF로 정리 → 이메일 알림
> **플러그인**: spreadsheet(write_excel), pdf(generate), mail(send)
> **기대 노드 수**: 30~40개
> **핵심**: REPEAT + DECIDE 중첩 + 플러그인 3종 체이닝

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
재고 알림이 뜨면 자동으로 발주하고 보고서까지 만들어주는 워크플로우를 만들고 싶어
```
**기대**: ASK (어떤 시스템, 어떤 알림, 어떤 보고서)

**Turn 2 — 시스템 + 대상**
```
skystock에서 CRITICAL/WARNING 재고알림을 확인하고, skymall에서 해당 상품의 실제 재고를 크로스체크한 뒤, 발주가 필요하면 skystock에 발주서를 자동 생성하는 거야
```
**기대**: ASK (발주 기준, 세부 흐름 질문)

**Turn 3 — 발주 기준**
```
발주 기준:
- CRITICAL 알림: 무조건 발주 (reorderQuantity 사용)
- WARNING 알림: skymall 실제 재고가 20개 미만이면 발주, 이상이면 skip
- NORMAL 알림: 무시
- 발주 수량은 StockAlert의 reorderQuantity 값 사용
```
**기대**: ASK (공급사 조회, 발주서 구성 방법 질문)

**Turn 4 — 공급사 + 발주 구성**
```
발주 흐름:
1. 각 알림 상품에 대해 skystock에서 공급사 조회 (GET /api/suppliers/by-product/{skymallProductId})
2. 공급사가 있으면 → 발주서 생성 (POST /api/purchase-orders)
3. 공급사가 없으면 → "공급사 미등록" 목록에 추가
4. 발주서 body: {supplierId, expectedDate(오늘+leadTimeDays), items:[{skymallProductId, skymallProductName, quantity(reorderQuantity), unitCost}]}
```
**기대**: ASK (에러처리, 출력 형식 질문)

**Turn 5 — 에러 처리**
```
에러 처리:
- 발주 생성 실패: retry:2 then skip (실패해도 다른 상품 발주 계속)
- 공급사 조회 실패: skip
- 로그인 실패: abort (인증 안 되면 전체 중단)
- skymall API 실패: retry:1 then skip
```
**기대**: ASK (출력 형식 질문)

**Turn 6 — 출력 형식 (보고서)**
```
발주 완료 후 보고서 3종:
1. Excel 파일: 시트 2개
   - "발주 성공": 상품명, 공급사, 수량, 단가, 소계, 납기일
   - "발주 실패/건너뜀": 상품명, 사유(공급사 미등록/API 실패/WARNING skip)
2. PDF 보고서: 발주 요약 (총 건수, 총 비용, 성공/실패 비율) + 상세 표
3. 이메일 알림: 발주 요약을 담당자에게 발송
```
**기대**: ASK (인증, 경로, 이메일 수신자 질문)

**Turn 7 — 인증 + 경로**
```
인증:
- skymall: jane_smith/pass1234 (home.skyepub.net:9090)
- skystock: admin/admin123 (home.skyepub.net:9091)

출력 경로:
- Excel: /tmp/wiiiv-test-v2/auto-reorder-result.xlsx
- PDF: /tmp/wiiiv-test-v2/auto-reorder-report.pdf
```
**기대**: ASK (이메일 설정 질문)

**Turn 8 — 이메일 설정**
```
이메일:
- 수신자: warehouse-team@skytree.io
- 제목: "[wiiiv] 자동 발주 완료 보고 — {날짜}"
- 본문: 발주 건수, 총 비용, CRITICAL/WARNING 각 건수, 실패 목록 요약
- HTML 형식
```
**기대**: CONFIRM (WorkOrder 제시)

**Turn 9 — WorkOrder 검토 + 수정**
```
WorkOrder에서 skymall 재고 크로스체크 부분이 빠진 것 같아. WARNING 알림에 대해 skymall GET /api/products/{id}로 실제 재고(stock 필드) 확인해서 20개 미만일 때만 발주하는 거 추가해줘
```
**기대**: CONFIRM (수정된 WorkOrder)

**Turn 10 — WorkOrder 확정 + 실행**
```
좋아, 워크플로우 만들어줘
```
**기대**: EXECUTE (HLX 생성 + 실행)

**Turn 11 — 저장**
```
'auto-reorder-pipeline-v2' 이름으로 저장해줘
```
**기대**: EXECUTE 또는 REPLY (워크플로우 저장)

#### WorkOrder 품질 체크 (20항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skymall | `"skymall" in wo` |
| 2 | skystock | `"skystock" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | 9091 | `"9091" in wo` |
| 5 | CRITICAL | `"CRITICAL" in wo` |
| 6 | WARNING | `"WARNING" in wo` |
| 7 | reorderQuantity | `"reorderQuantity" in wo or "reorder" in wo.lower()` |
| 8 | /api/stock-alerts | `"/api/stock-alerts" in wo` |
| 9 | /api/suppliers/by-product | `"by-product" in wo` |
| 10 | /api/purchase-orders (POST) | `"/api/purchase-orders" in wo` |
| 11 | skymall 재고 크로스체크 | `"stock" in wo.lower() and ("20" in wo or "크로스" in wo or "확인" in wo)` |
| 12 | spreadsheet/Excel | `"spreadsheet" in wo.lower() or "excel" in wo.lower()` |
| 13 | pdf | `"pdf" in wo.lower()` |
| 14 | mail/이메일 | `"mail" in wo.lower() or "이메일" in wo` |
| 15 | warehouse-team@skytree.io | `"warehouse-team" in wo` |
| 16 | retry:2 then skip | `"retry" in wo.lower()` |
| 17 | abort (로그인) | `"abort" in wo.lower()` |
| 18 | 공급사 미등록 | `"미등록" in wo or "not found" in wo.lower()` |
| 19 | leadTimeDays | `"leadTime" in wo or "납기" in wo` |
| 20 | HTML | `"html" in wo.lower() or "HTML" in wo` |

**PASS 기준**: 16/20 이상

#### Hard Assert

- **최소 30개 노드**:
  1. ACT(skymall 로그인)
  2. TRANSFORM(skymall 토큰 추출)
  3. ACT(skystock 로그인)
  4. TRANSFORM(skystock 토큰 추출)
  5. ACT(CRITICAL 알림 조회)
  6. ACT(WARNING 알림 조회)
  7. TRANSFORM(알림 병합 + 레벨 태깅)
  8. REPEAT(각 알림에 대해) {
     9. DECIDE(알림 레벨 분기)
     10. → CRITICAL: TRANSFORM(발주 대상 확정)
     11. → WARNING: ACT(skymall 상품 재고 조회 GET /api/products/{id})
     12. → WARNING: DECIDE(재고 20 미만?)
     13.   → YES: TRANSFORM(발주 대상 추가)
     14.   → NO: TRANSFORM(skip 기록)
     15. ACT(공급사 조회, onError: skip)
     16. DECIDE(공급사 존재?)
     17. → YES: TRANSFORM(발주 데이터 구성)
     18. → YES: ACT(발주 생성 POST, onError: retry:2 then skip)
     19. → NO: TRANSFORM(미등록 기록)
  }
  20. TRANSFORM(발주 성공/실패 분류)
  21. TRANSFORM(총 건수/비용 집계)
  22. TRANSFORM(실패 목록 정리)
  23. TRANSFORM(Excel 시트 데이터 구성)
  24. ACT(spreadsheet: write_excel)
  25. TRANSFORM(HTML 보고서 생성)
  26. ACT(pdf: generate)
  27. TRANSFORM(이메일 본문 생성, aiRequired: true)
  28. ACT(mail: send)
  29. TRANSFORM(최종 결과 요약)
- REPEAT 내부에 DECIDE 2중 중첩 (레벨분기 → 재고체크 → 공급사 분기)
- PLUGIN stepType 최소 3종: spreadsheet + pdf + mail
- onError 정책 다양: abort(로그인), retry:2 then skip(발주), skip(공급사)
- Excel + PDF + 이메일 3종 출력

**HLX 구조 검증**:
- REPEAT.body 내 DECIDE 2개 이상 (레벨분기 + 공급사분기)
- ACT(PLUGIN, pluginId: "spreadsheet", action: "write_excel")
- ACT(PLUGIN, pluginId: "pdf", action: "generate")
- ACT(PLUGIN, pluginId: "mail", action: "send")
- TRANSFORM(aiRequired: true) 최소 1개 (이메일 본문 또는 요약)

**의도**: 판단(DECIDE) + 반복(REPEAT) + 크로스체크 + 쓰기(발주) + 3종 플러그인 출력 — Phase 5 최대 규모의 복합 워크플로우

---

### Case 25: 크로스 시스템 공급망 종합 분석 + 스케줄 등록 ⚡⚡ (10턴, 20+ 노드)

> **난이도**: ★★★★★★
> **목표**: 양쪽 시스템 전체 데이터 수집 → AI 분석 → Excel/PDF → 주간 스케줄 등록
> **플러그인**: webfetch(fetch_json), spreadsheet(write_excel), pdf(generate), cron(schedule)
> **기대 노드 수**: 20~28개
> **핵심**: 4종 플러그인 전부 사용 + cron 스케줄 등록

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
공급망 전체를 분석하는 워크플로우를 만들어줘
```
**기대**: ASK (어떤 분석, 어떤 시스템)

**Turn 2 — 분석 범위**
```
skymall 쇼핑몰과 skystock 재고 시스템을 연동해서 공급망 전체를 분석하고 싶어. 매출-재고-공급사를 360도로 볼 수 있게
```
**기대**: ASK (구체적 분석 항목 질문)

**Turn 3 — skymall 데이터**
```
skymall에서 가져올 것:
1. 카테고리 요약 (GET /api/categories/summary)
2. 매출 리포트 (GET /api/orders/report?from=2025-01-01T00:00:00&to=2026-12-31T23:59:59)
3. 미판매 상품 (GET /api/products/unsold)
4. 재고부족 상품 (GET /api/products/low-stock?threshold=30)
```
**기대**: ASK (skystock 데이터 질문)

**Turn 4 — skystock 데이터**
```
skystock에서 가져올 것:
1. 대시보드 (GET /api/stats/dashboard)
2. 전체 재고 알림 (GET /api/stock-alerts)
3. 공급사 성과 (GET /api/stats/supplier-performance)
4. 발주서 전체 (GET /api/purchase-orders)
또, webfetch로 skymall과 skystock의 서버 상태(actuator/health)도 확인해줘
```
**기대**: ASK (분석 방법, 출력 질문)

**Turn 5 — 분석 방법**
```
데이터 분석:
1. 상품별 매출-재고-알림 레벨 종합 뷰 (merge)
2. 공급사별 발주 성공률/평균 리드타임 계산
3. 카테고리별 재고 건전성 점수 (재고부족 비율, 미판매 비율)
4. AI가 위의 데이터를 기반으로 "공급망 리스크 TOP 5"와 "개선 액션 아이템 3개"를 생성
```
**기대**: ASK (출력 형식, 경로 질문)

**Turn 6 — 출력 형식 + 경로**
```
출력:
1. Excel: 시트 4개 — 종합 뷰, 공급사 분석, 카테고리 건전성, AI 인사이트
   경로: /tmp/wiiiv-test-v2/supply-chain-analysis.xlsx
2. PDF: 경영진 브리핑용 1~2페이지 요약
   경로: /tmp/wiiiv-test-v2/supply-chain-brief.pdf
```
**기대**: ASK (인증, 에러처리, 스케줄 질문)

**Turn 7 — 인증 + 에러처리**
```
인증:
- skymall: jane_smith/pass1234 (home.skyepub.net:9090)
- skystock: admin/admin123 (home.skyepub.net:9091)

에러처리:
- 서버 헬스체크 실패 시 abort (서버 다운이면 의미 없음)
- API 조회 실패 시 retry:1 then skip
- 파일 생성 실패 시 abort
```
**기대**: ASK (스케줄 등록 질문)

**Turn 8 — 스케줄 등록**
```
이 워크플로우를 매주 월요일 오전 9시에 자동 실행되게 cron 스케줄로 등록해줘.
크론 표현식: 0 9 * * 1
콜백 URL: http://localhost:8235/api/v2/workflows/trigger
```
**기대**: CONFIRM (WorkOrder 제시)

**Turn 9 — WorkOrder 확정**
```
좋아, 워크플로우 만들어줘
```
**기대**: EXECUTE (HLX 생성 + 실행)

**Turn 10 — 저장**
```
'weekly-supply-chain-analysis' 이름으로 저장해줘
```
**기대**: EXECUTE 또는 REPLY (워크플로우 저장)

#### WorkOrder 품질 체크 (18항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skymall | `"skymall" in wo` |
| 2 | skystock | `"skystock" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | 9091 | `"9091" in wo` |
| 5 | /api/categories/summary | `"/api/categories/summary" in wo` |
| 6 | /api/orders/report | `"/api/orders/report" in wo` |
| 7 | /api/stats/dashboard | `"/api/stats/dashboard" in wo` |
| 8 | /api/stats/supplier-performance | `"supplier-performance" in wo` |
| 9 | webfetch/헬스체크 | `"webfetch" in wo.lower() or "health" in wo.lower()` |
| 10 | spreadsheet/Excel | `"spreadsheet" in wo.lower() or "excel" in wo.lower()` |
| 11 | pdf | `"pdf" in wo.lower()` |
| 12 | cron | `"cron" in wo.lower()` |
| 13 | 0 9 * * 1 | `"0 9" in wo or "월요일" in wo` |
| 14 | AI 분석 | `"ai" in wo.lower() or "인사이트" in wo or "리스크" in wo` |
| 15 | 재고 건전성 | `"건전성" in wo or "health" in wo.lower()` |
| 16 | abort | `"abort" in wo.lower()` |
| 17 | retry | `"retry" in wo.lower()` |
| 18 | supply-chain | `"supply-chain" in wo or "공급망" in wo` |

**PASS 기준**: 15/18 이상

#### Hard Assert

- **최소 20개 노드**:
  1. ACT(webfetch: skymall 헬스체크)
  2. ACT(webfetch: skystock 헬스체크)
  3. DECIDE(서버 상태 확인 — 둘 다 OK?)
  4. ACT(skymall 로그인)
  5. TRANSFORM(skymall 토큰)
  6. ACT(카테고리 요약)
  7. ACT(매출 리포트)
  8. ACT(미판매 상품)
  9. ACT(재고부족 상품)
  10. ACT(skystock 로그인)
  11. TRANSFORM(skystock 토큰)
  12. ACT(대시보드)
  13. ACT(재고알림)
  14. ACT(공급사 성과)
  15. ACT(발주서)
  16. TRANSFORM(상품별 종합 뷰 merge)
  17. TRANSFORM(공급사 발주 성공률 계산)
  18. TRANSFORM(카테고리 건전성 점수)
  19. TRANSFORM(AI 분석, aiRequired: true)
  20. ACT(spreadsheet: write_excel)
  21. ACT(pdf: generate)
  22. ACT(cron: schedule)
- PLUGIN stepType 최소 4종: webfetch + spreadsheet + pdf + cron
- Excel + PDF 파일 생성됨
- cron 스케줄 등록됨 (결과에 job_id 포함)
- TRANSFORM(aiRequired: true) 최소 1개
- DECIDE 노드 최소 1개 (서버 상태 분기)

**HLX 구조 검증**:
- ACT(PLUGIN, pluginId: "webfetch", action: "fetch_json") 2개 (skymall/skystock 헬스)
- ACT(PLUGIN, pluginId: "spreadsheet", action: "write_excel") 1개
- ACT(PLUGIN, pluginId: "pdf", action: "generate") 1개
- ACT(PLUGIN, pluginId: "cron", action: "schedule") 1개 — cron_expr: "0 9 * * 1"
- 플러그인 4종 전부 사용

**의도**: 4종 플러그인 전부 활용 + AI 분석 + 스케줄 자동화 — 엔터프라이즈 정기 보고 시나리오

---

## I. Executor 풀 결합 — 크로스 시스템 + 전 Executor 관통 ⚡⚡⚡ (풀 인터뷰)

> **공통 전제**:
> - Built-in Executor(API_CALL, FILE_WRITE/READ, COMMAND) + Plugin Executor(spreadsheet, pdf, webfetch, mail, cron) 혼합
> - skymall(home.skyepub.net:9090) + skystock(home.skyepub.net:9091) 크로스 시스템
> - WORKFLOW_CREATE 6차원 인터뷰 → WorkOrder → HLX 생성 → 실행 → 저장
> - 셸 명령(COMMAND)으로 데이터 검증/변환/백업, 파일 I/O로 중간 데이터 저장

---

### Case 26: 크로스 시스템 재고 동기화 감사 + 셸 백업 + 보고서 ⚡⚡⚡ (12턴, 28+ 노드)

> **난이도**: ★★★★★★★
> **목표**: API 수집 → 파일 저장 → 셸 검증/압축 → Excel/PDF 보고서 — Executor 5종 이상 혼합
> **Executor 혼합**: API_CALL + FILE_WRITE + FILE_READ + COMMAND + spreadsheet + pdf + webfetch
> **기대 노드 수**: 28~38개

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
skymall과 skystock의 재고 데이터가 일치하는지 감사하는 워크플로우를 만들고 싶어
```
**기대**: ASK (어떤 재고 데이터를 비교하는지 질문)

**Turn 2 — 감사 대상**
```
skymall에는 상품별 stock 필드가 있고, skystock에는 StockAlert에 safetyStock/reorderPoint가 있잖아. skymall 재고부족 상품과 skystock CRITICAL/WARNING 알림이 일치하는지 교차 검증하고 싶어. 불일치 항목을 찾아내는 거야
```
**기대**: ASK (구체적 비교 로직, 출력 형식 질문)

**Turn 3 — 비교 로직**
```
비교 로직:
1. skymall 재고 30 미만 상품 목록 수집
2. skystock 전체 StockAlert 수집
3. 교차 비교:
   - skymall 재고부족인데 skystock에 알림 없음 → "알림 누락"
   - skystock CRITICAL인데 skymall 재고 충분(30 이상) → "과잉 알림"
   - 양쪽 일치 → "정상"
4. 각 결과를 JSON으로 중간 파일에 저장 (감사 추적용)
```
**기대**: ASK (파일 저장, 백업, 출력 형식 질문)

**Turn 4 — 파일 저장 + 셸 백업**
```
중간 데이터 저장:
- /tmp/wiiiv-test-v2/audit/skymall-low-stock.json (skymall 원본)
- /tmp/wiiiv-test-v2/audit/skystock-alerts.json (skystock 원본)
- /tmp/wiiiv-test-v2/audit/cross-check-result.json (교차 비교 결과)

셸 명령으로 검증 + 백업:
- wc -l로 각 JSON 파일 줄 수 확인 (데이터 비어있지 않은지)
- tar -czf /tmp/wiiiv-test-v2/audit/audit-backup.tar.gz /tmp/wiiiv-test-v2/audit/*.json
```
**기대**: ASK (보고서 형식, 인증 정보 질문)

**Turn 5 — 보고서 형식**
```
감사 보고서:
1. Excel: 시트 3개
   - "정상 일치": 상품ID, 상품명, skymall재고, skystock알림레벨
   - "알림 누락": 상품ID, 상품명, skymall재고, 비고("skystock 알림 미등록")
   - "과잉 알림": 상품ID, 상품명, skymall재고, skystock알림레벨, 비고
   경로: /tmp/wiiiv-test-v2/audit/stock-audit-report.xlsx
2. PDF: 감사 요약 — 총 상품수, 일치/누락/과잉 건수, 일치율(%), AI 코멘트
   경로: /tmp/wiiiv-test-v2/audit/stock-audit-report.pdf
```
**기대**: ASK (인증, 에러처리 질문)

**Turn 6 — 인증 정보**
```
인증:
- skymall: jane_smith/pass1234 (home.skyepub.net:9090)
- skystock: admin/admin123 (home.skyepub.net:9091)

시작 전에 webfetch로 양쪽 서버 상태 확인(actuator/health)부터 해줘
```
**기대**: ASK (에러처리 질문)

**Turn 7 — 에러 처리**
```
에러 처리:
- 서버 헬스체크 실패: abort
- API 호출 실패: retry:2 then skip
- 파일 쓰기 실패: abort (감사 기록이 없으면 의미 없음)
- 셸 명령(tar) 실패: skip (백업은 선택사항)
- Excel/PDF 생성 실패: abort
```
**기대**: ASK 또는 CONFIRM

**Turn 8 — 추가 요구사항**
```
추가:
- 교차 비교 후 불일치 항목이 있으면 FILE_WRITE로 /tmp/wiiiv-test-v2/audit/discrepancy-alert.txt에 "불일치 N건 발견" 경고 메시지 저장
- tar 백업 후 ls -lh로 백업 파일 크기 확인
```
**기대**: CONFIRM (WorkOrder 제시)

**Turn 9 — WorkOrder 검토**
```
WorkOrder에 셸 명령 부분에 mkdir -p /tmp/wiiiv-test-v2/audit/ 가 빠져있어. 디렉토리 생성부터 해야 파일 저장이 되니까 추가해줘
```
**기대**: CONFIRM (수정된 WorkOrder)

**Turn 10 — WorkOrder 확정 + 실행**
```
좋아, 워크플로우 만들어줘
```
**기대**: EXECUTE (HLX 생성 + 실행)

**Turn 11 — 셸 검증 결과 확인**
```
tar 백업 파일이 잘 생성됐는지 확인해줘
```
**기대**: EXECUTE (FILE_READ 또는 COMMAND: ls -lh) 또는 REPLY

**Turn 12 — 저장**
```
'stock-sync-audit' 이름으로 저장해줘
```
**기대**: EXECUTE 또는 REPLY

#### WorkOrder 품질 체크 (20항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skymall | `"skymall" in wo` |
| 2 | skystock | `"skystock" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | 9091 | `"9091" in wo` |
| 5 | /api/products/low-stock | `"low-stock" in wo` |
| 6 | /api/stock-alerts | `"stock-alerts" in wo` |
| 7 | FILE_WRITE/파일 저장 | `"FILE_WRITE" in wo or "파일 저장" in wo or "file" in wo.lower()` |
| 8 | COMMAND/셸 | `"COMMAND" in wo or "셸" in wo or "tar" in wo or "wc" in wo` |
| 9 | tar 백업 | `"tar" in wo` |
| 10 | wc -l 검증 | `"wc" in wo` |
| 11 | mkdir | `"mkdir" in wo` |
| 12 | spreadsheet/Excel | `"spreadsheet" in wo.lower() or "excel" in wo.lower()` |
| 13 | pdf | `"pdf" in wo.lower()` |
| 14 | webfetch/헬스체크 | `"webfetch" in wo.lower() or "health" in wo.lower()` |
| 15 | 알림 누락 | `"누락" in wo` |
| 16 | 과잉 알림 | `"과잉" in wo` |
| 17 | cross-check/교차 | `"교차" in wo or "cross" in wo.lower()` |
| 18 | abort | `"abort" in wo.lower()` |
| 19 | retry | `"retry" in wo.lower()` |
| 20 | discrepancy/불일치 | `"불일치" in wo or "discrepancy" in wo.lower()` |

**PASS 기준**: 16/20 이상

#### Hard Assert

- **최소 28개 노드**:
  1. ACT(COMMAND: mkdir -p /tmp/wiiiv-test-v2/audit/)
  2. ACT(webfetch: skymall 헬스체크)
  3. ACT(webfetch: skystock 헬스체크)
  4. DECIDE(서버 상태 확인)
  5. ACT(API_CALL: skymall 로그인)
  6. TRANSFORM(skymall 토큰 추출)
  7. ACT(API_CALL: skymall 재고부족 조회)
  8. ACT(FILE_WRITE: skymall-low-stock.json 저장)
  9. ACT(API_CALL: skystock 로그인)
  10. TRANSFORM(skystock 토큰 추출)
  11. ACT(API_CALL: skystock 전체 알림 조회)
  12. ACT(FILE_WRITE: skystock-alerts.json 저장)
  13. ACT(COMMAND: wc -l skymall-low-stock.json)
  14. ACT(COMMAND: wc -l skystock-alerts.json)
  15. TRANSFORM(skymall 상품 ID 추출)
  16. TRANSFORM(skystock 알림 상품 ID 추출)
  17. TRANSFORM(교차 비교 — 일치/누락/과잉 분류)
  18. ACT(FILE_WRITE: cross-check-result.json 저장)
  19. DECIDE(불일치 존재 여부)
  20. → YES: ACT(FILE_WRITE: discrepancy-alert.txt)
  21. ACT(COMMAND: tar -czf 백업)
  22. ACT(COMMAND: ls -lh 백업 크기 확인)
  23. TRANSFORM(Excel 데이터 구성 — 3시트)
  24. ACT(spreadsheet: write_excel)
  25. TRANSFORM(AI 감사 코멘트 생성, aiRequired: true)
  26. TRANSFORM(HTML 보고서 구성)
  27. ACT(pdf: generate)
  28. TRANSFORM(최종 요약)
- **Executor 타입 검증**:
  - API_CALL: 최소 5회 (로그인 2 + API 조회 2+)
  - FILE_WRITE: 최소 4회 (JSON 3 + alert 1)
  - COMMAND: 최소 4회 (mkdir + wc 2 + tar + ls)
  - PLUGIN(spreadsheet): 1회
  - PLUGIN(pdf): 1회
  - PLUGIN(webfetch): 2회
- 중간 JSON 파일 3개 + tar.gz 1개 + Excel + PDF 생성

**의도**: API 수집 → 파일 아카이브 → 셸 검증/백업 → 교차 분석 → 플러그인 보고서 — Built-in + Plugin Executor 전면 결합

---

### Case 27: 크로스 시스템 주문-발주 ETL 배치 + 스크립트 검증 + 이메일 ⚡⚡⚡ (11턴, 32+ 노드)

> **난이도**: ★★★★★★★★
> **목표**: skymall 주문 → skystock 발주 변환(ETL) → 셸 스크립트로 데이터 검증 → 보고서 + 이메일
> **Executor 혼합**: API_CALL + FILE_WRITE + FILE_READ + COMMAND + LLM_CALL(TRANSFORM aiRequired) + spreadsheet + pdf + mail
> **기대 노드 수**: 32~42개
> **핵심**: ETL 파이프라인(Extract→Transform→Load) + REPEAT/DECIDE + 8종 Executor

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
skymall 주문 데이터를 skystock 발주로 변환하는 배치 파이프라인을 만들어줘
```
**기대**: ASK (어떤 주문, 어떤 변환, 어떤 발주)

**Turn 2 — ETL 개요**
```
이런 흐름이야:
1. Extract: skymall에서 최근 주문(2025~2026)과 주문 내 상품 정보를 가져오고
2. Transform: 각 주문 상품에 대해 skystock 공급사를 매칭하고, 발주서 데이터 형태로 변환
3. Load: skystock에 발주서를 생성하고, 결과를 보고서로 만들어
```
**기대**: ASK (상세 변환 로직, 매칭 기준 질문)

**Turn 3 — Extract 상세**
```
Extract 단계:
- skymall 로그인 (jane_smith/pass1234)
- GET /api/orders/date-range?from=2025-01-01T00:00:00&to=2026-12-31T23:59:59 (최근 주문)
- 주문별로 OrderItem의 productId, quantity 추출
- 원본 주문 데이터를 /tmp/wiiiv-test-v2/etl/raw-orders.json에 저장 (감사 추적)
```
**기대**: ASK (Transform 상세 질문)

**Turn 4 — Transform 상세**
```
Transform 단계:
- 주문 상품을 skymallProductId 기준으로 중복 제거 + 수량 합산
- 각 상품에 대해 skystock에서 공급사 조회 (GET /api/suppliers/by-product/{skymallProductId})
- 공급사별로 그룹핑:
  - 같은 공급사 상품은 하나의 발주서로 묶음
  - 공급사 없는 상품은 "미매칭 상품" 목록으로 분리
- 변환된 발주 데이터를 /tmp/wiiiv-test-v2/etl/transformed-orders.json에 저장
- 셸 명령으로 변환 데이터 검증: python3 -c "import json; d=json.load(open('/tmp/wiiiv-test-v2/etl/transformed-orders.json')); print(f'공급사 {len(d)}건, 총 상품 {sum(len(v) for v in d.values())}건')"
```
**기대**: ASK (Load 상세, 에러처리 질문)

**Turn 5 — Load 상세**
```
Load 단계:
- skystock 로그인 (admin/admin123)
- 공급사별 발주서 생성 (POST /api/purchase-orders)
  - body: {supplierId, expectedDate(오늘+leadTimeDays), items:[{skymallProductId, skymallProductName, quantity, unitCost}]}
- 발주 생성 결과를 /tmp/wiiiv-test-v2/etl/load-results.json에 저장
- 셸 명령으로 결과 검증: wc -l + jq '.[] | .status' 확인
```
**기대**: ASK (보고서, 이메일, 에러처리 질문)

**Turn 6 — 보고서 + 이메일**
```
결과 보고서:
1. Excel (/tmp/wiiiv-test-v2/etl/etl-batch-report.xlsx):
   - "ETL 요약" 시트: 원본 주문 수, 변환 건수, 발주 생성 수, 성공/실패
   - "발주 상세" 시트: 공급사명, 상품 수, 총 비용, 납기일, 상태
   - "미매칭 상품" 시트: 상품ID, 상품명, 수량, 사유
2. PDF (/tmp/wiiiv-test-v2/etl/etl-batch-summary.pdf):
   - AI가 ETL 결과를 분석해서 1페이지 요약 (성공률, 병목 공급사, 개선 제안)
3. 이메일:
   - 수신자: ops-team@skytree.io
   - 제목: "[wiiiv] ETL 배치 완료 — 주문→발주 변환 결과"
   - HTML 본문: 요약 테이블 + 미매칭 상품 경고
```
**기대**: ASK (에러처리 질문)

**Turn 7 — 에러 처리**
```
에러 처리:
- 로그인 실패: abort
- 주문 조회 실패: abort (원본 없으면 ETL 불가)
- 공급사 조회 실패: skip (해당 상품 미매칭 처리)
- 발주 생성 실패: retry:2 then skip (실패해도 다음 공급사 계속)
- 셸 검증 실패: skip (검증은 부가 기능)
- 파일 저장 실패: abort (감사 추적 필수)
- Excel/PDF 실패: abort
- 이메일 실패: skip (보고서가 핵심)
```
**기대**: CONFIRM (WorkOrder 제시)

**Turn 8 — WorkOrder 검토**
```
WorkOrder에서 mkdir -p /tmp/wiiiv-test-v2/etl/ 추가해줘. 그리고 Extract에서 skymall 주문의 OrderItem에 있는 pricePerItem을 unitCost로 사용한다는 것도 명시해줘
```
**기대**: CONFIRM (수정된 WorkOrder)

**Turn 9 — WorkOrder 보완**
```
하나 더. 발주 생성 전에 skystock에서 이미 같은 공급사에 REQUESTED 상태인 발주가 있으면 중복 발주 방지로 skip하는 로직도 추가해줘. GET /api/purchase-orders/supplier/{supplierId}로 확인
```
**기대**: CONFIRM (보완된 WorkOrder)

**Turn 10 — 실행**
```
좋아, 워크플로우 만들어줘
```
**기대**: EXECUTE (HLX 생성 + 실행)

**Turn 11 — 저장**
```
'order-to-po-etl-batch' 이름으로 저장해줘
```
**기대**: EXECUTE 또는 REPLY

#### WorkOrder 품질 체크 (22항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skymall | `"skymall" in wo` |
| 2 | skystock | `"skystock" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | 9091 | `"9091" in wo` |
| 5 | ETL/Extract | `"extract" in wo.lower() or "ETL" in wo` |
| 6 | Transform | `"transform" in wo.lower()` |
| 7 | Load | `"load" in wo.lower()` |
| 8 | /api/orders/date-range | `"date-range" in wo` |
| 9 | /api/suppliers/by-product | `"by-product" in wo` |
| 10 | /api/purchase-orders (POST) | `"/api/purchase-orders" in wo` |
| 11 | 중복 발주 방지 | `"중복" in wo or "REQUESTED" in wo` |
| 12 | FILE_WRITE/파일 저장 | `"raw-orders" in wo or "FILE_WRITE" in wo or "파일 저장" in wo` |
| 13 | COMMAND/셸 | `"COMMAND" in wo or "셸" in wo or "wc" in wo or "python3" in wo` |
| 14 | python3 검증 | `"python3" in wo or "python" in wo.lower()` |
| 15 | spreadsheet/Excel | `"spreadsheet" in wo.lower() or "excel" in wo.lower()` |
| 16 | pdf | `"pdf" in wo.lower()` |
| 17 | mail/이메일 | `"mail" in wo.lower() or "이메일" in wo` |
| 18 | ops-team@skytree.io | `"ops-team" in wo` |
| 19 | 미매칭 | `"미매칭" in wo or "미등록" in wo` |
| 20 | pricePerItem→unitCost | `"pricePerItem" in wo or "unitCost" in wo` |
| 21 | abort | `"abort" in wo.lower()` |
| 22 | retry | `"retry" in wo.lower()` |

**PASS 기준**: 18/22 이상

#### Hard Assert

- **최소 32개 노드**:
  — **Extract** —
  1. ACT(COMMAND: mkdir -p)
  2. ACT(API_CALL: skymall 로그인)
  3. TRANSFORM(skymall 토큰)
  4. ACT(API_CALL: 주문 조회 date-range)
  5. TRANSFORM(OrderItem 상품별 중복제거 + 수량합산)
  6. ACT(FILE_WRITE: raw-orders.json)
  — **Transform** —
  7. ACT(API_CALL: skystock 로그인)
  8. TRANSFORM(skystock 토큰)
  9. REPEAT(각 상품에 대해) {
     10. ACT(API_CALL: 공급사 조회, onError: skip)
     11. DECIDE(공급사 존재?)
     12. → YES: TRANSFORM(공급사별 그룹핑)
     13. → NO: TRANSFORM(미매칭 기록)
  }
  14. TRANSFORM(공급사별 발주 데이터 구성)
  15. ACT(FILE_WRITE: transformed-orders.json)
  16. ACT(COMMAND: python3 -c 변환 데이터 검증)
  — **Load** —
  17. REPEAT(각 공급사 발주에 대해) {
     18. ACT(API_CALL: 기존 REQUESTED 발주 조회)
     19. DECIDE(중복 발주?)
     20. → 중복: TRANSFORM(skip 기록)
     21. → 신규: ACT(API_CALL: 발주 생성 POST, onError: retry:2 then skip)
  }
  22. TRANSFORM(발주 결과 집계)
  23. ACT(FILE_WRITE: load-results.json)
  24. ACT(COMMAND: wc -l 결과 검증)
  — **Report** —
  25. TRANSFORM(Excel 3시트 데이터 구성)
  26. ACT(spreadsheet: write_excel)
  27. TRANSFORM(AI 분석 요약, aiRequired: true)
  28. TRANSFORM(HTML 보고서)
  29. ACT(pdf: generate)
  30. TRANSFORM(이메일 본문 구성)
  31. ACT(mail: send)
  32. TRANSFORM(최종 결과)
- **Executor 타입 검증**:
  - API_CALL: 8+회 (로그인 2 + 주문조회 + 공급사조회N + 기존발주조회N + 발주생성N)
  - FILE_WRITE: 3회 (raw + transformed + load-results)
  - COMMAND: 3회 (mkdir + python3 + wc)
  - PLUGIN(spreadsheet): 1회
  - PLUGIN(pdf): 1회
  - PLUGIN(mail): 1회
- REPEAT 2개 (상품별 공급사 조회 + 공급사별 발주 생성)
- DECIDE 2개 (공급사 존재? + 중복 발주?)

**의도**: ETL(Extract-Transform-Load) 정석 패턴 — API→파일→셸검증→API→보고서→이메일 — 8종 Executor 관통

---

### Case 28: 크로스 시스템 전사 데이터 아카이브 + 분석 + 스케줄 ⚡⚡⚡ (12턴, 35+ 노드)

> **난이도**: ★★★★★★★★
> **목표**: 양쪽 시스템 전체 데이터 추출 → 파일 아카이브 → 셸 전처리 → AI 분석 → 3종 보고서 → 스케줄
> **Executor 혼합**: API_CALL + FILE_WRITE + FILE_READ + COMMAND + spreadsheet + pdf + webfetch + mail + cron
> **기대 노드 수**: 35~45개
> **핵심**: 모든 Built-in + 모든 Plugin = 최대 Executor 커버리지

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
전사 데이터를 정기적으로 아카이브하고 분석 보고서까지 자동으로 만드는 워크플로우가 필요해
```
**기대**: ASK (어떤 시스템, 어떤 데이터)

**Turn 2 — 시스템 + 데이터 범위**
```
skymall과 skystock 양쪽의 핵심 데이터를 전부 추출해서 보관하고 분석하고 싶어.
skymall: 상품, 카테고리, 주문, 매출
skystock: 공급사, 발주, 재고알림, 대시보드
```
**기대**: ASK (추출 방법, 저장 형식 질문)

**Turn 3 — 추출 + 아카이브 방법**
```
데이터 추출:
- skymall: GET /api/products, /api/categories/summary, /api/orders/report, /api/products/low-stock, /api/products/unsold
- skystock: GET /api/stats/dashboard, /api/stock-alerts, /api/stats/supplier-performance, /api/purchase-orders, /api/suppliers

각 API 결과를 개별 JSON 파일로 저장:
- /tmp/wiiiv-test-v2/archive/skymall/ (5파일)
- /tmp/wiiiv-test-v2/archive/skystock/ (5파일)
```
**기대**: ASK (셸 처리, 분석, 출력 질문)

**Turn 4 — 셸 전처리**
```
셸 처리:
1. mkdir -p로 디렉토리 구조 생성
2. 각 JSON 파일 저장 후 wc -c로 파일 크기 확인 (0바이트면 경고)
3. 모든 JSON 파일을 tar -czf /tmp/wiiiv-test-v2/archive/full-archive-{date}.tar.gz로 압축
4. 압축 후 ls -lh로 아카이브 크기 확인
5. md5sum으로 아카이브 체크섬 생성 → /tmp/wiiiv-test-v2/archive/checksum.md5
```
**기대**: ASK (분석, 보고서 질문)

**Turn 5 — 분석 항목**
```
데이터 분석:
1. 카테고리별 매출-재고-공급사 종합 뷰
2. 공급사별 발주 성공률 + CRITICAL 알림 연계
3. 미판매 상품 vs 재고부족 상품 교차 분석
4. AI 종합: "전사 공급망 건전성 점수(0~100)" + "TOP 3 리스크" + "액션 아이템 3건"
```
**기대**: ASK (보고서 형식, 이메일, 스케줄 질문)

**Turn 6 — 보고서 형식**
```
보고서 3종:
1. Excel (/tmp/wiiiv-test-v2/archive/enterprise-report.xlsx):
   - "카테고리 분석" 시트
   - "공급사 분석" 시트
   - "상품 교차분석" 시트
   - "AI 인사이트" 시트
2. PDF (/tmp/wiiiv-test-v2/archive/enterprise-brief.pdf):
   - 경영진 1페이지 브리핑 (건전성 점수, 리스크, 액션)
3. 이메일:
   - 수신자: ceo@skytree.io
   - 제목: "[wiiiv] 전사 데이터 아카이브 + 분석 완료"
   - HTML 본문: 아카이브 크기, 건전성 점수, 핵심 리스크 요약
```
**기대**: ASK (인증, 에러처리, 스케줄 질문)

**Turn 7 — 인증**
```
인증:
- skymall: jane_smith/pass1234 (home.skyepub.net:9090)
- skystock: admin/admin123 (home.skyepub.net:9091)

시작 전 webfetch로 양쪽 서버 헬스체크 필수
```
**기대**: ASK (에러처리, 스케줄 질문)

**Turn 8 — 에러 처리**
```
에러 처리:
- 헬스체크 실패: abort
- 로그인 실패: abort
- API 조회 실패: retry:2 then skip (개별 데이터 누락은 허용)
- 파일 저장 실패: abort
- 셸 명령 실패: skip (아카이브 체크섬 등은 선택)
- tar 압축 실패: abort (아카이브가 핵심)
- Excel/PDF 실패: abort
- 이메일 실패: skip
```
**기대**: ASK (스케줄 질문)

**Turn 9 — 스케줄**
```
이 워크플로우를 매일 새벽 3시에 자동 실행해줘.
크론: 0 3 * * *
콜백 URL: http://localhost:8235/api/v2/workflows/trigger
```
**기대**: CONFIRM (WorkOrder 제시)

**Turn 10 — WorkOrder 검토 + 수정**
```
WorkOrder 좋은데, 아카이브 tar.gz 파일명에 날짜가 들어가야 해. 근데 HLX에서 동적 파일명은 어려우니까 fixed name으로 가되, 기존 파일이 있으면 덮어쓰기로 해줘. 파일명: full-archive.tar.gz
```
**기대**: CONFIRM (수정된 WorkOrder)

**Turn 11 — 실행**
```
좋아, 워크플로우 만들어줘
```
**기대**: EXECUTE (HLX 생성 + 실행)

**Turn 12 — 저장**
```
'daily-enterprise-archive' 이름으로 저장해줘
```
**기대**: EXECUTE 또는 REPLY

#### WorkOrder 품질 체크 (24항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skymall | `"skymall" in wo` |
| 2 | skystock | `"skystock" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | 9091 | `"9091" in wo` |
| 5 | /api/products | `"/api/products" in wo` |
| 6 | /api/categories/summary | `"/api/categories/summary" in wo` |
| 7 | /api/orders/report | `"/api/orders/report" in wo` |
| 8 | /api/stats/dashboard | `"/api/stats/dashboard" in wo` |
| 9 | /api/stats/supplier-performance | `"supplier-performance" in wo` |
| 10 | /api/stock-alerts | `"stock-alerts" in wo` |
| 11 | FILE_WRITE/파일 저장 | `"FILE_WRITE" in wo or "JSON 파일" in wo or "파일 저장" in wo` |
| 12 | COMMAND/셸 | `"COMMAND" in wo or "mkdir" in wo or "tar" in wo` |
| 13 | mkdir -p | `"mkdir" in wo` |
| 14 | tar | `"tar" in wo` |
| 15 | wc | `"wc" in wo` |
| 16 | md5sum/체크섬 | `"md5sum" in wo or "checksum" in wo.lower() or "체크섬" in wo` |
| 17 | spreadsheet/Excel | `"spreadsheet" in wo.lower() or "excel" in wo.lower()` |
| 18 | pdf | `"pdf" in wo.lower()` |
| 19 | mail/이메일 | `"mail" in wo.lower() or "이메일" in wo` |
| 20 | ceo@skytree.io | `"ceo@skytree" in wo` |
| 21 | cron | `"cron" in wo.lower()` |
| 22 | 0 3 * * * | `"0 3" in wo or "새벽 3시" in wo` |
| 23 | webfetch/헬스 | `"webfetch" in wo.lower() or "health" in wo.lower()` |
| 24 | AI 건전성 점수 | `"건전성" in wo or "점수" in wo or "인사이트" in wo` |

**PASS 기준**: 20/24 이상

#### Hard Assert

- **최소 35개 노드**:
  — **Prepare** —
  1. ACT(COMMAND: mkdir -p skymall/ skystock/)
  2. ACT(webfetch: skymall 헬스)
  3. ACT(webfetch: skystock 헬스)
  4. DECIDE(서버 상태)
  — **Extract skymall** —
  5. ACT(API_CALL: skymall 로그인)
  6. TRANSFORM(skymall 토큰)
  7. ACT(API_CALL: /api/products)
  8. ACT(FILE_WRITE: products.json)
  9. ACT(API_CALL: /api/categories/summary)
  10. ACT(FILE_WRITE: categories.json)
  11. ACT(API_CALL: /api/orders/report)
  12. ACT(FILE_WRITE: orders.json)
  13. ACT(API_CALL: /api/products/low-stock)
  14. ACT(FILE_WRITE: low-stock.json)
  15. ACT(API_CALL: /api/products/unsold)
  16. ACT(FILE_WRITE: unsold.json)
  — **Extract skystock** —
  17. ACT(API_CALL: skystock 로그인)
  18. TRANSFORM(skystock 토큰)
  19. ACT(API_CALL: /api/stats/dashboard)
  20. ACT(FILE_WRITE: dashboard.json)
  21. ACT(API_CALL: /api/stock-alerts)
  22. ACT(FILE_WRITE: alerts.json)
  23. ACT(API_CALL: /api/stats/supplier-performance)
  24. ACT(FILE_WRITE: performance.json)
  25. ACT(API_CALL: /api/purchase-orders)
  26. ACT(FILE_WRITE: purchase-orders.json)
  27. ACT(API_CALL: /api/suppliers)
  28. ACT(FILE_WRITE: suppliers.json)
  — **Shell Archive** —
  29. ACT(COMMAND: tar -czf)
  30. ACT(COMMAND: ls -lh)
  31. ACT(COMMAND: md5sum → checksum.md5)
  — **Analyze** —
  32. TRANSFORM(카테고리 매출-재고-공급사 종합)
  33. TRANSFORM(공급사 성과-알림 연계)
  34. TRANSFORM(미판매-재고부족 교차)
  35. TRANSFORM(AI 종합 분석, aiRequired: true)
  — **Report** —
  36. ACT(spreadsheet: write_excel, 4시트)
  37. ACT(pdf: generate)
  38. ACT(mail: send)
  — **Schedule** —
  39. ACT(cron: schedule, "0 3 * * *")
- **Executor 타입 검증**:
  - API_CALL: 12+회 (로그인 2 + API 10)
  - FILE_WRITE: 10회 (JSON 파일 10개)
  - COMMAND: 3+회 (mkdir + tar + ls + md5sum)
  - PLUGIN(webfetch): 2회
  - PLUGIN(spreadsheet): 1회
  - PLUGIN(pdf): 1회
  - PLUGIN(mail): 1회
  - PLUGIN(cron): 1회
  - **총 9종 Executor 사용** (API + FILE + COMMAND + webfetch + spreadsheet + pdf + mail + cron + TRANSFORM/AI)

**의도**: Phase 5의 피날레 — 전 Executor 관통 + 전 데이터 아카이브 + AI 분석 + 5종 플러그인 + cron — 엔터프라이즈 데이터 거버넌스 시나리오

---

## 수동 테스트 가이드

### 준비

```bash
# 1. wiiiv 서버 확인
curl -s http://localhost:8235/api/v2/system/health | python3 -m json.tool

# 2. 토큰 발급
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

# 3. 세션 생성
SID=$(curl -s -X POST http://localhost:8235/api/v2/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}' | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['data']['sessionId'])")
echo "Session: $SID"

# 4. 디렉토리 준비
mkdir -p ~/.wiiiv/projects/test ~/.wiiiv/hlx
```

### 대화

```bash
# 메시지 전송 (SSE)
curl -N -X POST "http://localhost:8235/api/v2/sessions/$SID/chat" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message": "skymall 종합 운영 보고서를 자동 생성하는 워크플로우를 만들어줘"}'
```

### 워크플로우 관리

```bash
# 저장된 워크플로우 목록
curl -s http://localhost:8235/api/v2/workflows \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# 특정 워크플로우 상세 (원본 JSON)
curl -s http://localhost:8235/api/v2/workflows/{id} \
  -H "Authorization: Bearer $TOKEN" | jq '.data.rawJson' -r | python3 -m json.tool

# CLI에서 파일 기반
/hlx create ~/.wiiiv/hlx/auto-reorder-pipeline.hlx
/hlx list
/hlx run <id>
```

### 노드 수 검증

```bash
# 생성된 워크플로우의 노드 수 확인
curl -s http://localhost:8235/api/v2/workflows/{id} \
  -H "Authorization: Bearer $TOKEN" | \
  jq '.data.rawJson | fromjson | .nodes | length'
```

---

## 핵심 검증 지표 요약

| 지표 | 기준 |
|------|------|
| 최대 워크플로우 크기 | 35~45 노드 (Case 28) |
| 최대 중첩 depth | 3레벨 (Case 19) |
| 최대 인터뷰 턴 수 | 12턴 (Case 23, 26, 28) |
| 크로스 시스템 인증 분리 | skymall(9090) ≠ skystock(9091) JWT |
| HLX 노드 타입 커버리지 | OBSERVE, TRANSFORM, DECIDE, ACT, REPEAT, SUBWORKFLOW 전부 |
| 저장/복원 무결성 | DB 저장 → 로드 → 재실행 결과 동일 |
| 파일 Export/Import | .hlx 파일 → 파싱 → 실행 성공 |
| onError 정책 | skip, retry:N, retry:N then skip, abort |
| REPEAT+DECIDE 중첩 | REPEAT body 내 DECIDE 2중 중첩 (Case 24) |
| TRANSFORM aiRequired | LLM 판단이 필요한 데이터 분석 노드 |
| 이중/삼중 출력 | Excel + PDF + Email 동시 생성 (Case 24, 27, 28) |
| 플러그인 커버리지 | spreadsheet, pdf, webfetch, mail, cron — 5종 전부 (Case 23~28) |
| Executor 최대 혼합 | API+FILE+COMMAND+플러그인 5종 = 9종 동시 (Case 28) |
| WorkOrder 품질 | 15/18+ 항목 PASS (Phase 6 수준) |
| WorkOrder 최대 체크 | 24항목 (Case 28) |
| 풀 인터뷰 | 6차원 점진적 Spec 수집 (Case 23~28) |
| ETL 패턴 | Extract→Transform→Load 정석 (Case 27) |
| 셸 검증 | COMMAND로 데이터 무결성 확인 (wc, tar, md5sum) (Case 26~28) |
