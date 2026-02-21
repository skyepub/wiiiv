# Phase 4: 백엔드 API 통합 (skymall + skystock)

> **검증 목표**: 자연어 → HLX 워크플로우 → 실제 백엔드 API 호출 → 결과 반환
> **핵심 관심사**: API 호출 정확성, 인증 처리, 크로스 시스템, 복합 Executor
> **전제**: Phase 2,3 통과, skymall/skystock 기동 확인, RAG에 API 스펙 주입

---

## 사전 준비

```bash
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | jq -r '.data.accessToken')

# API 스펙 RAG 주입
curl -X POST http://localhost:8235/api/v2/rag/ingest/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-wiiiv/phase3/skymall-api-spec-deployed.md"

curl -X POST http://localhost:8235/api/v2/rag/ingest/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-wiiiv/phase3/skystock-api-spec-deployed.md"

# 백엔드 생존 확인
curl -s http://home.skyepub.net:9090/api/categories | jq length  # → 8
curl -s http://home.skyepub.net:9091/api/suppliers | head -1       # → 응답 확인
```

---

## Case 1: 단순 조회 — 인증 불필요 API (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 카테고리 목록 보여줘" | EXECUTE |

**Hard Assert**:
- HLX 워크플로우 생성 + 실행
- GET /api/categories 호출
- 결과에 카테고리 목록 포함

**Soft Assert**:
- Electronics, Clothing 등 카테고리명 표시
- 8개 카테고리

**Audit Assert**:
- executionPath = API_WORKFLOW_HLX 또는 DB_QUERY_HLX

---

## Case 2: 인증 필요 조회 — 로그인 체인 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 주문 목록 보여줘" | EXECUTE |

**Hard Assert**:
- HLX가 자동으로 로그인 → 토큰 추출 → 주문 API 호출 체인 생성
- POST /api/auth/login → GET /api/orders

**Soft Assert**:
- 주문 데이터 표시

**의도**: Governor가 RAG에서 인증 필요 여부를 파악하고, 로그인 step을 자동 삽입하는지

---

## Case 3: 필터링 조회 — 조건 해석 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 전자제품 중 가장 비싼 상품 3개 알려줘" | EXECUTE |

**Hard Assert**:
- API 호출 성공
- 결과에 전자제품 카테고리 상품 포함

**Soft Assert**:
- 가격 내림차순 정렬
- 상위 3개만 표시
- HLX transform 노드에서 SORT + FILTER 처리

---

## Case 4: 집계 조회 — 데이터 가공 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 카테고리별 상품 수를 정리해줘" | EXECUTE |

**Hard Assert**:
- API 호출 성공

**Soft Assert**:
- 카테고리명 + 상품 수 테이블 형태 응답
- HLX transform 노드에서 AGGREGATE 처리

---

## Case 5: skystock 단순 조회 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock에서 공급업체 목록 보여줘" | EXECUTE |

**Hard Assert**:
- skystock API 호출 (home.skyepub.net:9091)
- 공급업체 데이터 반환

**Soft Assert**:
- 공급업체명, 연락처 등 표시

**의도**: skymall이 아닌 다른 백엔드(skystock)도 RAG 기반으로 올바르게 호출하는지

---

## Case 6: 복합 — API 호출 + 결과 파일 저장 (1~2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 카테고리 목록을 조회해서 /tmp/wiiiv-test-v2/categories.json으로 저장해줘" | EXECUTE |

**Hard Assert**:
- API 호출 + FILE_WRITE 두 가지 실행
- /tmp/wiiiv-test-v2/categories.json 파일 생성됨

**검증 명령**:
```bash
cat /tmp/wiiiv-test-v2/categories.json | jq length  # → 8
```

**Audit Assert**:
- Audit 레코드에 복합 실행 기록

**의도**: 서로 다른 Executor(API_CALL + FILE_WRITE)를 하나의 요청에서 조합하는 능력

---

## Case 7: 복합 — 조회 → 가공 → 2차 호출 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 재고가 30개 미만인 상품을 찾아서, 각 상품의 상세 정보를 조회해줘" | EXECUTE |

**Hard Assert**:
- 1차: GET /api/products/low-stock
- 2차: 각 상품에 대해 GET /api/products/{id} (반복 호출)

**Soft Assert**:
- 재고 부족 상품들의 상세 정보 표시

**의도**: HLX 체인에서 1차 결과를 파싱하여 2차 호출의 입력으로 사용하는 능력 (data flow)

---

## Case 8: 크로스 시스템 — skymall + skystock 통합 (1~2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 'Laptop Pro' 상품 정보를 찾고, skystock에서 이 상품의 발주 이력을 확인해줘" | EXECUTE |

**Hard Assert**:
- skymall API 호출 (9090)
- skystock API 호출 (9091)
- 두 시스템의 결과를 통합하여 응답

**Soft Assert**:
- 상품 정보 + 발주 이력이 하나의 응답에 포함

**의도**: 서로 다른 백엔드(JWT도 별개)를 하나의 워크플로우에서 통합 호출. wiiiv의 핵심 가치 — "다중 시스템 통합 자동화"

---

## Case 9: API 에러 처리 — 존재하지 않는 엔드포인트 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 /api/nonexistent-endpoint 호출해줘" | EXECUTE |

**Hard Assert**:
- 실행 시도 후 에러 처리
- Governor가 에러를 사용자에게 전달

**Soft Assert**:
- 404 또는 관련 에러 메시지 포함
- 시스템 크래시 없음

**Audit Assert**:
- status = FAILED
- error 필드에 원인 기록

---

## Case 10: 멀티턴 탐색 — 대화 속 점진적 요구 확장 (4턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에 어떤 데이터가 있는지 알려줘" | REPLY 또는 EXECUTE |
| 2 | "그러면 가장 많이 팔린 카테고리 탑3 알려줘" | EXECUTE |
| 3 | "1등 카테고리의 상품 리스트도 보여줘" | EXECUTE |
| 4 | "그 중 가장 비싼 상품의 상세 정보 보여줘" | EXECUTE |

**Hard Assert**:
- Turn 2~4 각각 API 호출 성공

**Soft Assert**:
- Turn 2 결과 → Turn 3 입력으로 자연 연결
- Turn 3 결과 → Turn 4 입력으로 자연 연결
- 세션 컨텍스트가 대화 전체에서 유지

**Audit Assert**:
- 실행 턴마다 Audit 레코드

**의도**: 자연어 대화 속에서 점진적으로 깊어지는 데이터 탐색. "드릴다운" 패턴.
