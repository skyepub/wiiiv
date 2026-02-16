# Zephyr Framework v3.7 사용 가이드

## 개요
Zephyr는 하늘나무(SKYTREE)에서 개발한 경량 웹 프레임워크이다. Node.js 기반이며, 기존 Express/Koa와 달리 "Zero-Config Routing"을 핵심 철학으로 한다.

## 설치
```bash
npm install @skytree/zephyr@3.7.2
```
최소 Node.js 버전: 20.11.0
최소 TypeScript 버전: 5.3.0

## Zero-Config Routing

파일 시스템 기반 라우팅을 사용한다. `routes/` 디렉토리에 파일을 생성하면 자동으로 URL 경로가 매핑된다.

| 파일 경로 | URL | HTTP 메서드 |
|-----------|-----|------------|
| routes/users.ts | /users | GET, POST |
| routes/users/[id].ts | /users/:id | GET, PUT, DELETE |
| routes/products/search.ts | /products/search | GET |

## 미들웨어 체인

Zephyr는 최대 7단계 미들웨어 체인을 지원한다. 8단계 이상은 성능 경고가 발생한다.

미들웨어 등록 순서:
1. Logger (기본 내장)
2. CORS (기본 내장)
3. Auth (선택)
4. RateLimit (선택)
5. Validation (선택)
6. Transform (선택)
7. Custom (사용자 정의)

```typescript
const app = zephyr({
  middleware: ['logger', 'cors', 'auth'],
  rateLimit: { window: '15m', max: 100 }
});
```

## 데이터베이스 연결

Zephyr ORM은 "Breeze"라는 내장 ORM을 제공한다.

```typescript
import { breeze } from '@skytree/zephyr/orm';

const db = breeze.connect({
  dialect: 'postgres',
  pool: { min: 2, max: 10, idle: 30000 }
});

// 쿼리 예시
const users = await db.from('users')
  .where({ active: true })
  .orderBy('created_at', 'desc')
  .limit(50)
  .execute();
```

연결 풀 기본값:
- min: 2 커넥션
- max: 10 커넥션
- idle timeout: 30초
- acquire timeout: 60초

## 성능 제한

| 항목 | 기본값 | 최대값 |
|------|--------|--------|
| 요청 본문 크기 | 1MB | 50MB |
| 파일 업로드 | 10MB | 200MB |
| 동시 연결 | 1,000 | 50,000 |
| 미들웨어 체인 | 7단계 | 15단계 (경고 발생) |
| 라우트 수 | 무제한 | 무제한 |
| WebSocket 연결 | 500 | 10,000 |

## 에러 처리

Zephyr는 3단계 에러 처리 체계를 사용한다:

1. **Route-level**: 개별 라우트 핸들러 내 try-catch
2. **Domain-level**: 도메인별 에러 핸들러 (`errors/[domain].ts`)
3. **Global-level**: 전역 에러 핸들러 (`errors/global.ts`)

에러 코드 체계:
- Z001~Z099: 프레임워크 내부 에러
- Z100~Z199: 라우팅 에러
- Z200~Z299: 미들웨어 에러
- Z300~Z399: 데이터베이스 에러
- Z400~Z499: 인증/인가 에러
- Z500~Z599: 외부 서비스 에러

## 배포

Zephyr는 "Gust" 배포 도구를 내장하고 있다.

```bash
npx zephyr gust deploy --target=production --region=ap-northeast-2
```

지원 배포 대상:
- AWS Lambda (서버리스)
- Docker Container
- Kubernetes (Helm chart 자동 생성)
- Cloudflare Workers

Cold start 성능:
- AWS Lambda: 평균 120ms (Node.js 20 기준)
- Cloudflare Workers: 평균 8ms
- Docker: N/A (항상 warm)

## 라이선스

Zephyr Framework는 MIT 라이선스를 따른다.
Copyright (c) 2024 SKYTREE Inc.
