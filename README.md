# wiiiv

> **wiiiv** (pronounced "Weave" / 위브) - LLM Governor 기반 실행 시스템

---

## Overview

wiiiv는 **LLM Governor 기반의 실행 시스템**입니다.

자연어 요청을 받아 구조화된 판단을 거쳐 안전하게 실행하는 것이 목표입니다.

---

## Architecture

```
Spec → Governor → DACS → Blueprint → Gate → Executor
```

| Layer | Responsibility |
|-------|----------------|
| **Spec** | 판단 자산 (무엇을, 왜) |
| **Governor** | 판단 주체 (LLM 기반) |
| **DACS** | 합의 엔진 (YES/NO/REVISION) |
| **Blueprint** | 판단의 고정 (불변 실행 계약) |
| **Gate** | 통제 (ALLOW/DENY) |
| **Executor** | 실행 (판단 없이) |

---

## Project Structure

```
wiiiv/
├── docs/           # Canonical specifications
├── wiiiv-core/     # Core execution layer
└── ...
```

---

## Version

- **v2.0**: LLM Governor 기반 (현재)
- **v1.0**: 오토마타 기반 (wiiiv-automata 저장소)

---

## License

TBD

---

*wiiiv / 하늘나무 / SKYTREE*
