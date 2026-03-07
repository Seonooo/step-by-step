# 복습을 위한 repo
- 추가 업데이트 예정

---

## 📊 전체 로드맵 한눈에 보기

| 단계 | 주제 | 기간 | 핵심 산출물 |
| --- | --- | --- | --- |
| 1단계 | 기본 CS 개념 | 1~2주 | 멀티스레드 벤치마크 코드 |
| 2단계 | Redis 심화 | 2~3주 | 선착순 쿠폰 시스템 구현 |
| 3단계 | 대용량 설계 | 3~4주 | 사례 분석 문서 3개 |
| 4단계 | 모니터링 & 장애 | 2주 | Grafana 대시보드 + Alert |

---

## 1단계: 기본 CS 개념 정리

### ✅ 컨텍스트 스위칭 & 동시성

**학습 자료**

- 📖 *Operating System Concepts* — 챕터 5~6

**목표**

- [x]  프로세스 / 스레드 / 코루틴 차이를 그림으로 설명할 수 있다
- [x]  뮤텍스, 세마포어, 모니터의 차이를 말할 수 있다
- [x]  동기화 메커니즘이 왜 필요한지 면접에서 설명할 수 있다

**실습**

- [x]  Python/Java에서 멀티스레드 vs 싱글스레드 벤치마크 작성
- [x]  공유 자원 접근 시 Race Condition 재현 후 Lock으로 해결

---

### ✅ 네트워크 기초

**학습 자료**

- 📖 *Computer Networking: A Top-Down Approach* — 챕터 1~2

**목표**

- [x]  Latency / Throughput / Bandwidth 차이를 숫자로 설명할 수 있다
- [x]  TCP vs UDP 선택 기준을 트레이드오프로 설명할 수 있다
- [x]  RTT, 패킷 손실이 성능에 미치는 영향을 이해한다

**실습**

- [ ]  `ping` 으로 Latency 측정 및 결과 해석
- [ ]  `iperf` 로 Throughput 측정 (TCP / UDP 비교)

---

## 2단계: Redis 깊이 있게

### ✅ Redis 아키텍처

**학습 자료**

- 📖 [Redis 공식 문서](https://redis.io/docs/management/)

**목표**

- [ ]  Single-thread 이벤트 루프 동작 원리를 설명할 수 있다
- [ ]  Master-Replica 동기/비동기 복제 차이를 말할 수 있다
- [ ]  Sentinel vs Cluster를 어떤 상황에 선택하는지 말할 수 있다
- [ ]  Lua Script가 원자성을 보장하는 이유를 설명할 수 있다

---

### ✅ 실습 프로젝트: 선착순 쿠폰 시스템

> 이 프로젝트 하나를 완성하면 면접 답변 소재가 생깁니다.
>

**단계별 구현**

```
1. Docker로 Redis Master-Replica 구성
2. Lua Script로 원자적 카운팅 구현
3. JMeter / Locust로 부하 테스트 (목표: 1만 TPS)
4. 모니터링: redis-cli --stat, INFO 명령어로 상태 확인
```

- [ ]  Docker Compose 파일로 Master 1 + Replica 2 구성
- [ ]  `INCR` 단순 구현 → Race Condition 재현
- [ ]  Lua Script로 원자적 카운팅으로 교체 → 문제 해결 확인
- [ ]  Locust 시나리오 작성 후 1만 TPS 달성 여부 확인
- [ ]  부하 테스트 전후 Redis `INFO stats` 비교 기록

---

### ✅ 벤치마크 연습

- [ ]  `redis-benchmark` 기본 사용법 익히기
- [ ]  SET / GET / INCR / ZADD 명령어별 처리량 측정 및 비교
- [ ]  Lua Script vs 개별 명령어 성능 차이 측정 후 수치로 기록

> 💡 **면접 팁:** "Lua Script를 쓰면 X% 성능 향상이 있었습니다" 처럼 본인이 측정한 수치를 말하면 강력합니다.
>

---

## 3단계: 대용량 시스템 설계

### ✅ 용량 산정 (Capacity Planning)

**학습 자료**

- 📖 *System Design Interview* (Alex Xu) — 챕터 1~3

**연습 목표**

| 계산 항목 | 공식 | 연습 문제 |
| --- | --- | --- |
| RPS 추정 | `DAU × 평균 요청수 / 86,400` | DAU 500만 → Peak RPS? |
| 스토리지 | `RPS × 레코드 크기 × 86400 × 365 × 5` | 메시지 1KB, 1만 RPS → 5년 용량? |
| 대역폭 | `Peak RPS × 평균 응답 크기` | 응답 50KB → 필요 대역폭? |
| 피크 배율 | 평상시 × 3~10배 | 이커머스 세일 시 배율은? |
- [ ]  위 계산을 5가지 서로 다른 시스템에 직접 적용해보기
- [ ]  계산 결과를 화이트보드에 표로 정리하는 연습

---

### ✅ 실제 사례 분석 (블로그 3개 이상)

**읽을 자료**

| 회사 | 링크 | 분석 포인트 |
| --- | --- | --- |
| 우아한형제들 | [배민 기술블로그](https://techblog.woowahan.com/) | 선착순 쿠폰 동시성 처리 |
| LINE | [LINE Engineering](https://engineering.linecorp.com/) | 이벤트 시스템 아키텍처 |
| 카카오 | [카카오 기술블로그](https://tech.kakao.com/) | 대규모 트래픽 처리 |

**각 블로그 분석 시 반드시 답해야 할 질문**

- [ ]  어떤 수치를 제시했는가? (RPS, DAU, 응답시간 등)
- [ ]  왜 그 기술을 선택했는가? (트레이드오프 포함)
- [ ]  어떤 장애를 겪었고, 어떻게 해결했는가?
- [ ]  나라면 어떻게 다르게 설계했을까?

> 💡 분석 내용을 A4 1장으로 정리해두면 면접 전 빠르게 복습할 수 있습니다.
>

---

## 4단계: 모니터링 & 장애 대응

### ✅ 메트릭 개념 이해

**핵심 메트릭 프레임워크**

| 프레임워크 | 항목 | 설명 |
| --- | --- | --- |
| **RED** | Rate | 초당 요청 수 |
|  | Errors | 오류율 (%) |
|  | Duration | 응답 시간 (p50 / p95 / p99) |
| **USE** | Utilization | 자원 사용률 |
|  | Saturation | 대기 중인 작업 수 |
|  | Errors | 오류 횟수 |

**목표**

- [ ]  SLI / SLO / SLA 차이를 예시로 설명할 수 있다
- [ ]  p95 / p99 Latency가 무엇인지, 왜 평균보다 중요한지 설명할 수 있다
- [ ]  RED와 USE 중 어떤 상황에 어떤 걸 쓰는지 말할 수 있다

---

### ✅ 실습: 모니터링 시스템 구축

**사용 도구**

- Prometheus + Grafana
- Redis Exporter

**구현 순서**

- [ ]  Docker Compose로 Prometheus + Grafana 띄우기
- [ ]  Redis Exporter 연결 후 메트릭 수집 확인
- [ ]  Grafana 대시보드에 아래 패널 추가
    - RPS (초당 요청수)
    - Redis Hit Rate (캐시 적중률)
    - Memory Usage
    - Replication Lag

**Alert 규칙 작성**

- [ ]  CPU 사용률 80% 이상 → Slack 알림
- [ ]  Latency p99가 10ms 초과 → PagerDuty 알림
- [ ]  Replication Lag 1초 이상 → 즉시 알림

---

## 📝 실행 체크리스트

### 1단계

- [ ]  OS Concepts 챕터 5~6 정독
- [ ]  멀티스레드 벤치마크 코드 작성 & 결과 기록
- [ ]  네트워크 실습 (ping / iperf) 결과 문서화

### 2단계

- [ ]  Redis 공식 문서 통독
- [ ]  Docker Master-Replica 구성 완료
- [ ]  Lua Script 원자 카운팅 구현
- [ ]  1만 TPS 부하 테스트 통과
- [ ]  벤치마크 수치 기록

### 3단계

- [ ]  Alex Xu 책 챕터 1~3 완독
- [ ]  용량 계산 연습 5회 이상
- [ ]  기술 블로그 3개 이상 분석 & 정리

### 4단계

- [ ]  RED / USE / SLI/SLO 개념 정리
- [ ]  Prometheus + Grafana 스택 구축
- [ ]  Alert 규칙 3개 작성 및 테스트

---