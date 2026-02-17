# test-wiiiv - ConversationalGovernor E2E 테스트 문서

> wiiiv ConversationalGovernor의 E2E 테스트 케이스 기획, 실행 로그, 결과를 관리한다.

## 구조

```
test-wiiiv/
├── README.md           ← 이 파일
├── phase1/
│   └── cases-01-10.md  ← Phase 1 케이스 기획서 (기본 시나리오 10개)
├── phase2/
│   └── cases-11-20.md  ← Phase 2 케이스 기획서 (심화 시나리오 10개)
└── phase3/
    ├── cases-21-23.md  ← Phase 3 케이스 기획서 (프로젝트 생성 E2E 3개)
    └── logs/           ← Phase 3 실행 로그 (자동 생성)
```

## Phase 요약

| Phase | Cases | 검증 대상 | 상태 |
|-------|-------|-----------|------|
| Phase 1 | 1-10 | 기본 대화/인터뷰/실행 판단 | 통과 |
| Phase 2 | 11-20 | 심화 인터뷰/피봇/에스컬레이션/라이프사이클 | 통과 |
| Phase 3 | 21-23 | 실제 프로젝트 생성 → 빌드 → 테스트 E2E | 구현 완료 |

## 실행 방법

```bash
# 전체 실행
OPENAI_API_KEY=sk-... ./gradlew :wiiiv-core:test --tests "io.wiiiv.integration.ConversationalGovernorE2ETest"

# Phase별 실행
OPENAI_API_KEY=sk-... ./gradlew :wiiiv-core:test --tests "*Case 1*"   # Phase 1
OPENAI_API_KEY=sk-... ./gradlew :wiiiv-core:test --tests "*Case 21*"  # Phase 3 개별

# Phase 3 로그 확인
cat test-wiiiv/phase3/logs/case21.log
cat test-wiiiv/phase3/logs/case22.log
cat test-wiiiv/phase3/logs/case23.log
```

## Assertion 전략

- **Hard Assert**: 테스트 실패 시 빌드 실패 (핵심 행동 검증)
- **Soft Assert**: 로그 출력 + 경고 (LLM 비결정성 허용)
