# Phase 3: RAG 통합 (Knowledge Augmentation)

> **검증 목표**: RAG 파이프라인이 정확한 문서 기반 응답을 생성하는가?
> **핵심 관심사**: 주입 정확성, 검색 정밀도, 복합 추론, "모름" 정직성
> **전제**: Phase 1 통과, 서버 기동, LLM 연결

---

## 사전 준비

```bash
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | jq -r '.data.accessToken')

# 보험 약관 PDF 주입
curl -X POST http://localhost:8235/api/v2/rag/ingest/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@wiiiv 수동테스트/samsung_realloss.pdf"

# RAG 크기 확인
curl -s http://localhost:8235/api/v2/rag/size -H "Authorization: Bearer $TOKEN"
```

---

## Case 1: RAG 주입 확인 — 문서 존재 확인

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "지금 어떤 문서가 등록되어 있어?" | REPLY |

**Hard Assert**:
- REPLY
- 보험 관련 문서 존재 언급

**의도**: RAG 파이프라인에 문서가 정상 적재되었는지 기본 확인

---

## Case 2: 직접적 사실 추출 — 단순 검색 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "이 보험의 정식 상품명이 뭐야?" | REPLY |

**Hard Assert**:
- REPLY
- 정확한 상품명 포함 (PDF 원문과 일치)

**Soft Assert**:
- 보험사명(삼성화재) 언급

**의도**: RAG 검색 → 정확한 사실 추출 능력

---

## Case 3: 조건부 사실 추출 — 필터링 검색 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "이 보험에서 통원 치료 시 1회당 보장 한도가 얼마야?" | REPLY |

**Hard Assert**:
- REPLY
- 구체적 금액 포함

**Soft Assert**:
- PDF 원문의 통원 보장 한도와 일치

**의도**: 특정 조건(통원)에 해당하는 정보만 정확히 추출하는지

---

## Case 4: 복합 추론 — 조건 결합 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "이 보험에서 입원과 통원 보장 한도를 각각 알려줘" | REPLY |
| 2 | "그러면 입원 10일 + 통원 5회를 했을 때 총 받을 수 있는 최대 금액은?" | REPLY |

**Hard Assert**:
- 전 턴 REPLY

**Soft Assert**:
- Turn 1: 입원/통원 각각의 한도 명시
- Turn 2: Turn 1의 숫자를 기반으로 계산한 총액 제시

**의도**: RAG에서 가져온 복수 데이터를 결합하여 추론하는 능력

---

## Case 5: 면책 조항 — 심층 검색 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "이 보험에서 보장하지 않는 경우(면책사항)를 알려줘" | REPLY |
| 2 | "음주 운전으로 사고가 나면 보장이 되나?" | REPLY |

**Hard Assert**:
- 전 턴 REPLY

**Soft Assert**:
- Turn 1: 면책사항 목록 제시 (PDF 원문 기반)
- Turn 2: 면책 해당 여부를 명확히 판단 ("보장되지 않습니다" 또는 조건부 설명)

**의도**: 약관의 부정적 조건(~하지 않는 경우)을 정확히 이해하고 적용하는지

---

## Case 6: RAG에 없는 정보 — 정직한 "모름" (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "이 보험의 2025년 3분기 손해율이 얼마야?" | REPLY |

**Hard Assert**:
- REPLY

**Soft Assert**:
- "문서에 해당 정보가 없" 또는 "확인할 수 없" 등 정직한 응답
- 환각(hallucination)으로 숫자를 지어내면 FAIL

**의도**: RAG에 없는 정보를 요청했을 때 환각 대신 정직하게 "모른다"고 하는지. LLM+RAG 시스템의 가장 중요한 품질 지표.

---

## Case 7: 멀티턴 보험 상담 시나리오 (5턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "나 이 보험 가입자인데, 상담 좀 해줘" | REPLY |
| 2 | "어제 병원에서 MRI를 찍었는데 비용이 30만원 나왔어" | REPLY |
| 3 | "이거 보험 청구할 수 있어?" | REPLY |
| 4 | "청구하려면 어떤 서류가 필요해?" | REPLY |
| 5 | "보장 한도를 넘으면 나머지는 내가 내야 하는 거지?" | REPLY |

**Hard Assert**:
- 전 턴 REPLY
- 실행 시도 없음

**Soft Assert**:
- Turn 3: MRI 검사가 보장 대상인지 약관 기반 판단
- Turn 4: 필요 서류 안내 (약관 또는 일반 보험 지식 기반)
- Turn 5: 자기부담금/한도 초과 관련 설명

**의도**: 연속 상담에서 RAG 문맥 + 대화 문맥이 모두 유지되는지

---

## Case 8: RAG + 일반 지식 교차 — 법률 관점 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "이 보험 약관의 면책조항 중에 소비자보호법과 충돌할 수 있는 게 있어?" | REPLY |
| 2 | "그런 조항이 실제 소송에서 무효 판정을 받은 사례가 있어?" | REPLY |

**Hard Assert**:
- 전 턴 REPLY

**Soft Assert**:
- Turn 1: 약관(RAG) + 소비자보호법(일반 지식) 교차 분석
- Turn 2: 일반 지식 기반 사례 언급 (RAG에는 없는 정보)
- RAG 정보와 일반 지식의 경계를 명확히 구분해서 답변

**의도**: RAG 문서 내 정보와 LLM 자체 지식을 자연스럽게 결합하되, 출처를 혼동하지 않는지
