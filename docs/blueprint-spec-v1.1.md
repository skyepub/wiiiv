# Execution Blueprint Specification v1.1

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | Execution Blueprint Specification |
| 버전 | v1.1 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-29 |
| 수정일 | 2026-01-30 |
| 상위 문서 | Governor 역할 정의서 v1.1, DACS v2 인터페이스, Gate 최소 스펙 정의서, Spec 정의서 v1.0 |
| 적용 범위 | wiiiv 실행 자산 전반 |
| 변경 사항 | v1.1: Spec 스냅샷 규칙 상세화, Executor-Spec 분리 명시 |

---

## 1. 정의 (Definition)

**Execution Blueprint**는 wiiiv 시스템에서 **Governor의 판단이 고정된 결과물**이며, **실행·검증·재현의 유일한 기준 자산**이다.

Blueprint는 "워크플로 정의"가 아니라 **하나의 실행 요청에 대해 확정된 판단 스냅샷**이다.

### 1.1 Blueprint의 기본 전제

Blueprint는 실행을 위한 **최종 자산**이다.

- Executor와 Gate는 **Blueprint만을 기준으로** 동작한다
- Executor와 Gate는 **Spec을 직접 참조하지 않는다**
- Blueprint는 자신이 어떤 판단을 근거로 생성되었는지를 **명확히, 변경 불가능한 방식으로** 포함해야 한다

> **Blueprint는 Spec 없이 생성될 수 없다.**

---

## 2. 핵심 원칙 (Core Principles)

### 2.1 요청 단위 불변 자산 (Immutable per Request)

- Blueprint는 **요청 단위로 생성**된다
- 한 번 생성된 Blueprint는 **절대 수정되지 않는다**
- 변경이 필요할 경우, **새로운 Blueprint를 생성**한다

> Blueprint는 Git commit과 동일한 위상을 가진다.

---

### 2.2 실행의 유일한 입력 (Single Source of Execution)

- 모든 실행은 반드시 Execution Blueprint를 입력으로 사용한다
- **Blueprint 없이 실행은 허용되지 않는다**
- Executor는 Blueprint를 **해석하지 않고 소비**한다

---

### 2.3 판단과 실행의 분리

- Blueprint는 **실행이 아니다**
- Blueprint는 **판단의 고정물**이다
- 실행은 Blueprint 이후 단계에서만 발생한다

---

### 2.4 Spec과의 관계 (Relation to Spec)

**Spec**은 사용자의 의도와 맥락을 보존한 **판단 자산**이며, **DACS의 합의 대상**이다.

**Execution Blueprint**는 다음 요소들이 결합되어 생성되는 **실행 판단의 고정물**이다:

- 원본 Spec (스냅샷)
- DACS 합의 결과
- Governor의 최종 판단

즉,

```
Spec + DACS 합의 결과 + Governor 판단 = Execution Blueprint
```

#### 관계 정리

| 항목 | 설명 |
|------|------|
| Spec | Blueprint의 입력 재료 (판단 자산) |
| Blueprint | Spec의 스냅샷을 포함 |
| 의존성 | Spec 없이 Blueprint는 존재할 수 없다 |
| 불변성 | Spec은 수정될 수 있으나, Blueprint에 포함된 Spec은 **고정된 스냅샷** |

#### 역할 구분

- **Spec**은 "무엇을 하려는가"와 "왜 그런 요청이 나왔는가"에 대한 **맥락 자산**이다
- **Blueprint**는 "무엇을 하기로 확정했는가"에 대한 **실행 계약**이다

> Blueprint가 생성되는 순간, Spec은 더 이상 판단 대상이 아니라 **기록 자산의 일부**가 된다.

---

## 3. Spec 스냅샷 규칙 (Spec Snapshot Rules)

> **v1.1 추가 섹션**

### 3.1 Spec 스냅샷의 정의

Blueprint에 포함되는 Spec은 **"살아있는 Spec"이 아니라**, Blueprint 생성 시점의 Spec 상태를 고정한 **스냅샷**이다.

이 스냅샷은 다음 성격을 가진다:

| 항목 | 규칙 |
|------|------|
| 변경 가능성 | Blueprint 생성 이후 **변경 불가** |
| 실행 중 참조 | ❌ (Executor는 Spec을 보지 않는다) |
| 용도 | 감사·재현·책임 추적 ✅ |

---

### 3.2 Spec 스냅샷의 필수 포함 요소

Execution Blueprint는 반드시 다음을 포함해야 한다:

#### Spec Snapshot ID

| 항목 | 설명 |
|------|------|
| `spec_id` | 원본 Spec의 식별자 |
| `spec_version` | 생성 시점의 버전 또는 해시 (선택) |

#### Spec Snapshot Content

| 방식 | 설명 |
|------|------|
| 전체 포함 | Blueprint 생성 시점의 Spec 전체를 포함 |
| 불변 참조 | Spec 원본에 대한 불변 참조(pointer) |

> **Spec 스냅샷이 없는 Blueprint는 유효하지 않은 Blueprint로 간주한다.**

#### Spec Snapshot Metadata

| 항목 | 필수 | 설명 |
|------|------|------|
| 생성 시각 | ✅ | 스냅샷이 고정된 시점 |
| Governor 식별자 | ✅ | 판단을 내린 Governor |
| DACS 판단 결과 요약 | ⚪ | 합의 결과 참조 |

---

### 3.3 Spec 변경과 Blueprint의 관계

Spec은 Blueprint 생성 이후에도 보완·확장될 수 있다.
**그러나 이는 이미 생성된 Blueprint에 어떠한 영향도 미치지 않는다.**

다음 원칙을 반드시 따른다:

| 상황 | 결과 |
|------|------|
| Spec 변경 → 기존 Blueprint 자동 갱신 | ❌ |
| Spec 변경 → 기존 Blueprint 무효화 | ❌ |
| Spec 변경 → 새로운 Blueprint 생성만 가능 | ✅ |

즉,

> **Blueprint는 "당시의 판단"을 고정하는 자산이며, Spec은 "미래 판단"을 위한 자산이다.**

---

### 3.4 Blueprint 생성 전 Spec 충분성 검증

Blueprint를 생성하기 전에 **Governor는 반드시 다음을 확인**해야 한다:

| 조건 | 설명 |
|------|------|
| Spec이 존재하는가 | Spec 없이 Blueprint 생성 불가 |
| Spec이 충분하다고 판단되었는가 | Governor의 판단 책임 |
| (DACS 위임 시) DACS 결과가 **YES**인가 | REVISION은 승인이 아님 |

이 조건 중 하나라도 충족되지 않으면 **Blueprint를 생성해서는 안 된다.**

---

### 3.5 Executor와 Spec의 분리

Executor는 다음을 **절대 수행하지 않는다**:

```
❌ Spec 해석
❌ Spec 참조
❌ Spec 변경
```

Executor는 **Blueprint만을 입력으로 받아** 그 안에 정의된 실행 단계만을 수행한다.

> **Spec 스냅샷은 Executor에게 의미 없는 데이터여야 하며, 이는 의도된 설계다.**

---

### 3.6 Blueprint 무결성과 감사 가능성

Spec 스냅샷은 다음 목적을 위해 존재한다:

- 실행 결과에 대한 **사후 감사**
- "왜 이 실행이 허용되었는가"에 대한 **설명**
- Governor/DACS 판단의 **재현**

Blueprint는 언제든 다음 질문에 답할 수 있어야 한다:

> **"이 실행은 어떤 Spec에 근거해 만들어졌는가?"**

이 질문에 답할 수 없는 Blueprint는 **정의상 불완전**하다.

---

### 3.7 Spec 스냅샷 핵심 요약

```
Blueprint는 Spec 없이 존재할 수 없다
Blueprint는 Spec의 스냅샷을 고정한다
Spec 변경은 Blueprint를 바꾸지 않는다
Blueprint는 실행 계약이며, 판단은 이미 끝난 상태다
```

> **Execution Blueprint는 Spec 위에서 내려진 판단을 실행 가능한 계약 형태로 고정한 결과물이다.**

---

## 4. 생성 시점과 생명주기 (Lifecycle)

### 4.1 생성 시점 (Canonical)

Blueprint는 다음 조건을 만족한 이후 생성된다:

1. Governor가 판단을 완료함
2. (필요 시) DACS 합의 결과가 **YES**
3. 사용자 실행 의도가 명시됨

> **Gate 통과 이전에 생성된다**

---

### 4.2 생명주기

```
User Request
     ↓
Spec 구성/보완
     ↓
Governor 판단 (Spec 충분성 검증)
     ↓
(선택) DACS 합의
     ↓
Execution Blueprint 생성 (Spec 스냅샷 고정)
     ↓
Gate 통과 여부 판단
     ↓
Executor 실행
     ↓
Blueprint + 실행 결과 영구 보관
```

---

## 5. 변경과 책임 모델 (Mutation & Responsibility)

### 5.1 변경 불가 원칙

- Blueprint는 생성 이후 **수정 불가**
- 수정은 기술적으로 허용되지 않으며, 개념적으로도 존재하지 않는다

> **"수정된 Blueprint"는 존재하지 않는다.**
> **오직 "새로운 Blueprint"만 존재한다.**

---

### 5.2 책임 귀속

- Blueprint의 내용에 대한 책임은 **실행 요청자에게 귀속**된다
- wiiiv는 Blueprint의 **의도를 판단하지 않는다**
- wiiiv는 오직 **실행 가능 여부만 판단**한다 (Gate)

> 이는 일반적인 컴퓨팅 보안 모델을 따른다
> (DB DROP TABLE, rm -rf, terraform destroy와 동일)

---

## 6. 저장과 보관 (Persistence)

### 6.1 영구 보관

- 모든 Blueprint는 **영구 보관 대상**이다
- **삭제는 허용되지 않는다**

보관 목적:
- 재현 (Replay)
- 감사 (Audit)
- 추적 (Traceability)

---

### 6.2 실행 기록 결합

하나의 실행은 다음 **3요소로 완결**된다:

| 요소 | 역할 |
|------|------|
| Execution Blueprint (+ Spec 스냅샷) | 판단의 고정물 |
| Gate 결정 로그 | 통제 기록 |
| Executor 실행 결과 | 실행 증거 |

> 이 세 가지의 결합이 **완전한 실행 증거**다.

---

## 7. 포맷 (Format)

### 7.1 기본 포맷

- Blueprint의 기본 표현은 **JSON**
- JSON은 **인간이 읽고 이해할 수 있어야** 한다
- DSL, 바이너리 포맷은 사용하지 않는다 (v1 기준)

---

### 7.2 Canonical JSON

- 동일한 Blueprint는 **동일한 Canonical JSON 표현**을 가진다
- Canonical JSON 정의는 향후:
  - 해시
  - 서명
  - 패키징

을 위한 기반으로 사용될 수 있다

---

## 8. 신뢰 경계 (Trust Boundary)

- v1에서 Blueprint는 **wiiiv 내부 신뢰 경계 내**에서만 사용된다
- 무결성 검증, 위변조 탐지는 **강제하지 않는다**

실행 허용의 기준은:
- 사용자 인증
- 권한
- Gate 정책

> **Blueprint 자체는 신뢰 대상이 아니라 검증 입력물이다.**

---

## 9. 미래 확장 (Non-binding)

본 사양은 향후 다음 확장을 배제하지 않는다:

- Blueprint 패키징 (binary wrapper)
- 무결성 해시
- 서명 및 provenance 메타데이터

단, 이러한 확장은:
- 실행 허용의 필수 조건이 아니다
- Blueprint의 책임 모델을 변경하지 않는다

---

## 10. 요약 (Canonical Summary)

```
Execution Blueprint는 요청 단위로 생성되는 불변 자산이다
모든 실행은 Blueprint를 기준으로 수행된다
Blueprint는 판단의 고정물이며, 실행은 아니다
Blueprint는 Spec의 스냅샷을 반드시 포함한다
Spec 변경은 기존 Blueprint에 영향을 주지 않는다
수정은 허용되지 않으며, 변경은 새로운 Blueprint 생성으로만 가능하다
실행 책임은 실행 요청자에게 귀속된다
Executor는 Spec을 직접 참조하지 않는다
Blueprint는 Spec + DACS 합의 + Governor 판단의 결합이다
```

---

## Canonical 상태 요약

| 구성 요소 | 상태 |
|-----------|------|
| Governor 역할 정의서 v1.1 | ✅ Canonical |
| DACS v2 인터페이스 | ✅ Canonical |
| Gate 최소 스펙 정의서 | ✅ Canonical |
| Spec 정의서 v1.0 | ✅ Canonical |
| Execution Blueprint Spec v1.1 | ✅ Canonical |
| Blueprint Structure Schema v1.0 | ✅ Canonical |
| Blueprint Node Type Spec v1.0 | ✅ Canonical |

---

## 하위 문서

- Blueprint Structure Schema v1.0
- Blueprint Node Type Specification v1.0

---

*wiiiv / 하늘나무 / SKYTREE*
