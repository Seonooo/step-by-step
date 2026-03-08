# 2단계 실습 정리 — Redis 심화

---

## 📋 실습 목록

| 실습 | 주제 | 핵심 결과 |
|---|---|---|
| Step 1 | Docker Redis 환경 구성 | Master-Replica-Sentinel 정상 동작 확인 |
| Step 2 | 복제 지연 재현 | Docker Pause로 강제 재현, 4ms 만에 Partial Sync |
| Step 3 | Race Condition 재현 | 쿠폰 18건 초과 발급 😱 |
| Step 4 | Lua Script 원자성 | 초과 발급 0건 + EVALSHA 3.6배 향상 |
| Step 5 | 1만 TPS 부하 테스트 | VT + Lua Script = 23,094 TPS |

---

## 🛠️ Step 1 — Docker Redis 환경 구성

### 구성

```yaml
# docker-compose.yml
services:
  redis-master:    port 6379  # 쓰기 전담, AOF 활성화
  redis-replica-1: port 6380  # 읽기 전담
  redis-replica-2: port 6381  # 읽기 + 백업
  redis-sentinel-1: port 26379  # Failover 자동화
  redis-sentinel-2: port 26380
  redis-sentinel-3: port 26381
```

### 복제 상태 확인 (INFO replication)

```
[Master]
role: master
connected_slaves: 2
slave0: ip=172.18.0.3, state=online, offset=323539, lag=0
slave1: ip=172.18.0.4, state=online, offset=323539, lag=0
master_repl_offset: 323576

[Replica]
role: slave
master_link_status: up
slave_repl_offset: 323576  ← Master와 동일 = 완전 동기화!
slave_read_only: 1          ← 쓰기 시도 시 에러
```

### 핵심 교훈

```
slave_repl_offset = master_repl_offset → 완전 동기화
lag: 0 → 복제 지연 없음
slave_read_only: 1 → Replica는 읽기 전용
```

---

## 🛠️ Step 2 — 복제 지연 (Replication Lag)

### 실습 1~6 결과 — 로컬 환경

```
예상: 복제 지연으로 이전 값 반환
실제: 즉시 최신 값 반환 (복제 < 1ms)

이유:
로컬 Docker 환경 → 같은 머신, 가상 네트워크
→ 네트워크 지연 거의 없음
→ 복제 전파 < 1ms → 재현 불가

실무 환경:
물리 네트워크 지연 수 ms ~ 수십 ms
트래픽 폭주 시 수백 ms 가능
```

### 실습 7 — Docker Pause로 강제 재현

```
[1단계] Replica Pause (네트워크 단절 시뮬레이션)

[2단계] Pause 중 Master 20,000건 쓰기
  소요: 158ms
  Master offset: 50,147,681
  → 20,000 / 0.158 = 126,582 ops/sec (파이프라인 효과)

[3단계] Unpause → Partial Sync 시작
  경과(ms)  Master offset  Replica offset  Gap
  ────────────────────────────────────────────
  4ms      50,147,681     50,147,681      0  ← 완전 동기화!

→ 4ms 만에 Catch-up 완료!
```

### Partial Sync 동작 원리

```
Replication Backlog 기본 크기: 1MB
20,000건 데이터 크기 < 1MB
→ Backlog에 전부 보존
→ Unpause 후 offset부터 이어서 전송
→ Full Sync 없이 4ms 만에 복구!

만약 데이터 > 1MB였다면:
→ Backlog 오버플로우
→ Full Sync 발생 (RDB 전체 재전송)
→ 수십 초 ~ 수분 소요!
```

### WAIT 명령어 검증

```
WAIT 1 100 결과: 1개 Replica 동기화 완료 (4ms 소요)
→ 동기 복제 보장 확인 ✅

Pause 중 WAIT → 타임아웃 발생
→ "지금 복제 불가" 신호로 활용 가능!
```

### 핵심 교훈

```
로컬 환경: 복제 지연 < 1ms → 재현 어려움
실무 환경: 네트워크 단절 시 Partial Sync로 빠른 복구

금융 데이터 → Master 읽기 (잔액, 결제 내역)
일반 캐시   → Replica 읽기 (메뉴, 리뷰)

면접 답변:
"Docker Pause로 158ms 단절 시뮬레이션 후
Unpause 시 4ms 만에 Partial Sync로
복구되는 것을 직접 확인했습니다."
```

---

## 🛠️ Step 3 — Race Condition 재현

### 코드

```java
// ❌ 원자성 없는 방식
String count = commands.get("coupon:count");
if (count != null && Integer.parseInt(count) > 0) {
    // 여기서 다른 스레드가 끼어들 수 있음!
    commands.decr("coupon:count");
    commands.sadd("coupon:users", userId);
}
```

### 실습 1 결과

```
초기 쿠폰: 1,000개
동시 요청: 10,000개 (Virtual Thread)

소요 시간:  687ms
남은 쿠폰:  -18개  ← 음수!
발급 건수:  1,018건
초과 발급:  18건   😱
TPS:        14,556
```

### 실습 2 — 시각화

```
쿠폰 5개 / 요청 20개:

Thread-0  ~ Thread-19: 전부 GET=5 읽음
→ 20개 스레드 전부 if(count > 0) 통과!
→ 20개 전부 DECR 실행
→ 남은 쿠폰: -15개 / 초과 발급: 15건

발생 원인:
GET 시점에 모두 5를 봤기 때문에
if 조건을 전부 통과한 것!
→ GET과 DECR 사이에 다른 스레드가 끼어든 것
```

### 핵심 교훈

```
명령어 2개 (GET + DECR) = 원자성 미보장
→ 사이에 다른 스레드 끼어들기 가능
→ 쿠폰 초과 발급 발생

실무였다면:
배민 쿠폰 이벤트 → 18건 금전적 손실!
```

---

## 🛠️ Step 4 — Lua Script 원자성

### 코드

```lua
-- 선착순 쿠폰 발급 Lua Script
local count = redis.call('GET', KEYS[1])
if count == false then return 0 end
if tonumber(count) > 0 then
    redis.call('DECR', KEYS[1])
    redis.call('SADD', KEYS[2], ARGV[1])
    return 1  -- 발급 성공
else
    return 0  -- 쿠폰 소진
end
```

### 실습 1 결과

```
초기 쿠폰: 1,000개
동시 요청: 10,000개

소요 시간:  1,633ms
남은 쿠폰:  0개  ✅
발급 건수:  1,000건
초과 발급:  0건   ✅
TPS:        6,123
```

### 실습 2 — Race Condition vs Lua Script 비교

```
방식              실제 발급   초과 발급   소요 시간
──────────────────────────────────────────────
GET+DECR (Step3)   553건     53건      453ms  ❌
Lua Script (Step4)  500건      0건      250ms  ✅

Lua Script가 더 빠르고 정확!
이유: 경쟁이 적을 때 Lua Script 큐 대기 < GET+DECR 재시도
```

### 실습 3 — EVALSHA 스크립트 캐싱

```
EVAL:     스크립트 전체 텍스트 전송 (~200바이트)
EVALSHA:  SHA1 해시값만 전송 (40바이트)

결과:
EVAL TPS:    6,123
EVALSHA TPS: 22,222  ← 3.6배 향상!

이유:
네트워크 전송량 80% 감소
→ 10만 TPS 환경에서 체감 큰 차이!

실무 적용:
서버 시작 시 SCRIPT LOAD로 사전 등록
→ 이후 EVALSHA만 사용
```

### 핵심 교훈

```
Lua Script = 여러 명령어를 Redis가 단일 명령어로 처리
→ 실행 중 다른 클라이언트 끼어들기 불가
→ 멀티 서버 환경에서도 원자성 보장

EVALSHA = Lua Script + 네트워크 최적화
→ 실무 최적 조합!
```

---

## 🛠️ Step 5 — 1만 TPS 부하 테스트

### 실습 1 — 단계별 TPS 측정

```
동시 요청    소요 시간    TPS        발급 건수
──────────────────────────────────────────
100         44ms      2,272      100건
500        102ms      4,902      500건
1,000      107ms      9,345    1,000건
5,000      314ms     15,923    5,000건
10,000     752ms     13,297   10,000건  ← TPS 감소!

5,000개에서 정점 → 이후 TPS 하락!
```

**왜 10,000개에서 TPS가 떨어졌나?**
```
5,000개까지:
→ 요청 증가 = TPS 증가 (선형)

10,000개에서:
→ Lettuce 커넥션 풀 경쟁 심화
→ Virtual Thread들이 커넥션 대기
→ 대기 시간 > 처리 시간 → TPS 역전!

1단계 실습에서 배운 것:
"커넥션 풀이 병목이면 VT 이점 사라짐"
→ 지금 그 현상이 그대로 재현!

해결책:
커넥션 풀 크기 증가
또는 Redis Cluster로 노드 분산
```

### 실습 2 — Virtual Thread vs 플랫폼 스레드

```
동시 요청 5,000개:

방식                  소요 시간    TPS
─────────────────────────────────────
Virtual Thread        173ms    28,901  ← 40% 빠름!
플랫폼 스레드 (200개)  243ms    20,576

VT가 40% 빠른 이유:
Redis 응답 대기 중 (I/O bound):
플랫폼 스레드 → 200개 제한 → 나머지 4,800개 대기
Virtual Thread → 대기 중 OS 스레드 반환 → 5,000개 동시 처리

1단계 실습 비교:
1단계 VT 이점: 8.7배 (커넥션 풀 없음)
2단계 VT 이점: 1.4배 (커넥션 풀 병목)
→ 커넥션 풀이 병목일수록 VT 이점 감소 확인!
```

### 실습 3 — 고부하 정확성 검증

```
초기 쿠폰: 1,000개
동시 요청: 10,000개

소요 시간:  433ms
남은 쿠폰:  0개
발급 성공:  1,000건 ✅
발급 거절:  9,000건 (재고 없음)
초과 발급:  0건     ✅
TPS:        23,094

10,000개 동시 요청에서도
초과 발급 0건 완벽 보장!
```

---

## 📊 2단계 실습 전체 수치 총정리

```
방식                  TPS        초과 발급   정확성
─────────────────────────────────────────────────
GET+DECR            14,556       18건      ❌
Lua Script (EVAL)    6,123        0건      ✅
Lua Script (EVALSHA) 22,222       0건      ✅
VT + EVALSHA        23,094        0건      ✅  ← 최고!
플랫폼 스레드 + Lua  20,576        0건      ✅

최종 결론:
EVALSHA + Virtual Thread
= 가장 빠르고 가장 안전한 조합
```

---

## 📊 1단계 실습과 2단계 실습 연결고리

```
1단계에서 배운 것 → 2단계 실습에서 검증:

커넥션 풀 병목 → VT 이점 감소
  1단계: 이론 학습
  2단계: 10,000개에서 TPS 역전으로 직접 확인 ✅

I/O bound에서 VT 이점
  1단계: 8.7배 (커넥션 풀 없음)
  2단계: 1.4배 (커넥션 풀 병목 조건)
  → 조건에 따라 이점 달라짐 확인 ✅

Race Condition
  1단계: Java Lock으로 단일 서버 해결
  2단계: Redis Lua Script로 분산 환경 해결 ✅

단일 서버 한계 ~10만 ops/sec
  2단계: 실제로 2~3만 TPS 측정
  → 커넥션 풀, 네트워크 오버헤드로
    이론값보다 낮은 것 확인 ✅
```

---

## 🎤 면접 최종 답변

```
"선착순 쿠폰 시스템을 직접 구현해봤습니다.

GET+DECR 방식은 14,556 TPS로 빠르지만
10,000 요청에서 18건 초과 발급이 발생했습니다.

Lua Script 적용 후 초과 발급 0건을 보장했고
EVALSHA로 스크립트를 캐싱하니 22,222 TPS로
3.6배 향상됐습니다.

Virtual Thread를 함께 적용하니
플랫폼 스레드보다 40% 높은 23,094 TPS를 달성했습니다.

단, 동시 요청 10,000개 초과 시
커넥션 풀이 병목이 되어 TPS가 역전되는 것도 확인했습니다.
실무에서는 커넥션 풀 크기 조정이나
Redis Cluster로 해결하겠습니다."
```

---

## ✅ 2단계 실습 완료 체크리스트

```
✅ Step 1: Docker Redis Master-Replica-Sentinel 구성
✅ Step 2: 복제 지연 (Docker Pause로 강제 재현)
           → 158ms 단절 후 4ms 만에 Partial Sync 복구
✅ Step 3: Race Condition 18건 초과 발급 재현
           → GET+DECR 두 명령어 사이 끼어들기 확인
✅ Step 4: Lua Script 초과 발급 0건
           → EVALSHA 3.6배 TPS 향상
✅ Step 5: VT + Lua Script = 23,094 TPS
           → 커넥션 풀 병목 시 TPS 역전 확인

---

## 🔗 다음 단계

> **3단계: 대용량 시스템 설계**
> - DB 샤딩 / 파티셔닝
> - CQRS 패턴
> - 이벤트 드리븐 아키텍처
> - Kafka 심화