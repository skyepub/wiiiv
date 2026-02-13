# wttr.in 날씨 API 스펙

## 개요
wttr.in은 무료 날씨 API다. API 키가 필요 없다. HTTP GET 요청으로 전 세계 도시의 현재 날씨를 조회할 수 있다.

## 기본 엔드포인트

```
GET https://wttr.in/{도시명}?format=j1
```

## 도시명 형식
- 영문 도시명: `Busan`, `Seoul`, `Tokyo`, `London`, `NewYork`
- 한국어 → 영문 변환 필수:
  - 부산 → Busan
  - 서울 → Seoul
  - 대구 → Daegu
  - 인천 → Incheon
  - 광주 → Gwangju
  - 대전 → Daejeon
  - 울산 → Ulsan
  - 제주 → Jeju
  - 수원 → Suwon
  - 성남 → Seongnam
  - 도쿄 → Tokyo
  - 오사카 → Osaka
  - 뉴욕 → New+York
  - 런던 → London
  - 파리 → Paris
- 공백이 있는 도시명은 +로 연결: `New+York`, `San+Francisco`

## 요청 예시

부산 현재 날씨 조회:
```
GET https://wttr.in/Busan?format=j1
```

서울 현재 날씨 조회:
```
GET https://wttr.in/Seoul?format=j1
```

## 요청 헤더
```json
{
  "Accept": "application/json"
}
```

## 응답 형식 (JSON, format=j1)

```json
{
  "current_condition": [
    {
      "temp_C": "15",
      "temp_F": "59",
      "FeelsLikeC": "13",
      "FeelsLikeF": "55",
      "humidity": "62",
      "weatherDesc": [{"value": "Partly cloudy"}],
      "windspeedKmph": "17",
      "winddir16Point": "NW",
      "winddirDegree": "320",
      "precipMM": "0.0",
      "pressure": "1015",
      "cloudcover": "50",
      "uvIndex": "4",
      "visibility": "10",
      "observation_time": "09:00 AM"
    }
  ],
  "nearest_area": [
    {
      "areaName": [{"value": "Busan"}],
      "country": [{"value": "South Korea"}],
      "region": [{"value": "Busan"}],
      "latitude": "35.100",
      "longitude": "129.033"
    }
  ]
}
```

## 주요 응답 필드 설명

### current_condition (현재 날씨)
| 필드 | 설명 | 예시 |
|------|------|------|
| temp_C | 현재 기온 (섭씨) | "15" |
| temp_F | 현재 기온 (화씨) | "59" |
| FeelsLikeC | 체감 온도 (섭씨) | "13" |
| humidity | 습도 (%) | "62" |
| weatherDesc[0].value | 날씨 설명 (영문) | "Partly cloudy" |
| windspeedKmph | 풍속 (km/h) | "17" |
| winddir16Point | 풍향 (16방위) | "NW" |
| precipMM | 강수량 (mm) | "0.0" |
| pressure | 기압 (hPa) | "1015" |
| cloudcover | 구름량 (%) | "50" |
| uvIndex | 자외선 지수 | "4" |
| visibility | 가시거리 (km) | "10" |

### nearest_area (위치 정보)
| 필드 | 설명 |
|------|------|
| areaName[0].value | 지역명 |
| country[0].value | 국가명 |
| latitude | 위도 |
| longitude | 경도 |

## 사용자 응답 가이드

날씨 조회 결과를 사용자에게 보여줄 때:
- 기온은 섭씨(temp_C)로 표시
- 체감온도(FeelsLikeC)도 함께 표시
- 날씨 설명(weatherDesc)은 한국어로 번역
- 풍속과 습도 포함
- 예: "부산 현재 기온 15°C (체감 13°C), 구름 조금, 습도 62%, 북서풍 17km/h"

## 날씨 설명 한국어 번역
- Clear → 맑음
- Sunny → 맑음
- Partly cloudy → 구름 조금
- Cloudy → 흐림
- Overcast → 흐림
- Light rain → 약한 비
- Moderate rain → 비
- Heavy rain → 강한 비
- Light snow → 약한 눈
- Moderate snow → 눈
- Heavy snow → 강한 눈
- Fog → 안개
- Mist → 옅은 안개
- Thunderstorm → 천둥번개

## 주의사항
- API 키 불필요 (무료)
- 도시명은 반드시 영문으로 변환하여 요청
- format=j1 파라미터 필수 (JSON 응답)
- writeIntent: false (조회 전용, 데이터 변경 없음)
- 한 번의 GET 호출로 완료됨 (isComplete: true)
