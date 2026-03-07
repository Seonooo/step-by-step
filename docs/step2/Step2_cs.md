# 2단계 개념 정리 — Redis 심화

---

## 📋 목차

1. [Redis가 빠른 이유](#1-redis가-빠른-이유)
2. [Single-thread 이벤트 루프](#2-single-thread-이벤트-루프)
3. [epoll 동작 원리](#3-epoll-동작-원리)
4. [Redis 6.0 I/O 멀티스레딩](#4-redis-60-io-멀티스레딩)
5. [Lua Script 원자성](#5-lua-script-원자성)
6. [MULTI/EXEC vs Lua Script](#6-multiexec-vs-lua-script)
7. [Redis 메모리 구조](#7-redis-메모리-구조)
8. [자료구조 내부 인코딩](#8-자료구조-내부-인코딩)
9. [메모리 파편화 진단 및 해결](#9-메모리-파편화-진단-및-해결)
10. [O(N) 명령어 최적화](#10-on-명령어-최적화)
11. [Master-Replica 복제](#11-master-replica-복제)
12. [Sentinel 자동 Failover](#12-sentinel-자동-failover)
13. [Cluster 해시슬롯 샤딩](#13-cluster-해시슬롯-샤딩)
14. [해시태그와 원자성](#14-해시태그와-원자성)
15. [Hot Key 문제](#15-hot-key-문제)
16. [Sentinel vs Cluster 선택 기준](#16-sentinel-vs-cluster-선택-기준)

---

## 1. Redis가 빠른 이유

```
1. 인메모리 (In-Memory):
   디스크 접근: ~10ms
   메모리 접근: ~100ns
   → 10만 배 빠름!

2. Single-thread 이벤트 루프:
   컨텍스트 스위칭 없음
   락 없음 → 동기화 오버헤드 없음

3. I/O Multiplexing (epoll):
   수천 개 연결을 스레드 1개로 처리
   O(1) 이벤트 감지

4. 단순한 자료구조:
   String, List, Hash, Set, ZSet
   → 복잡한 연산 없음

결과: ~10만 ops/sec 달성
```

---

## 2. Single-thread 이벤트 루프

### 동작 방식

```
[클라이언트 1,000개 동시 연결]
        ↓
   [Event Loop]  ← 스레드 1개
        ↓
┌─────────────────────────────┐
│ 1. I/O 이벤트 감지 (epoll)  │
│ 2. 요청 큐에서 꺼냄          │
│ 3. 명령어 실행               │
│ 4. 응답 전송                 │
│ 5. 다음 이벤트로             │
└─────────────────────────────┘
```

### 원자성 보장 원리

```
서버 1, 서버 2가 동시에 Redis에 요청:

Redis 내부 큐:
[서버1 요청] → [서버2 요청] (순서대로)
        ↓
서버1 요청 처리 완료 → 서버2 요청 처리
→ 동시에 처리되는 일 없음 = 원자성 보장!
```

### Single-thread 단점

```
O(N) 명령어가 전체를 블로킹:
KEYS *        → 전체 키 스캔 → 서버 마비!
SMEMBERS 대형Set → 수백만 개 반환

→ 실무에서 KEYS * 절대 금지!
→ SCAN 명령어로 대체
```

---

## 3. epoll 동작 원리

### select vs epoll

```
select (구식):
연결 10,000개를 매번 전부 순회
"1번 데이터 왔어?" ... "10,000번?"
→ O(N) 탐색
→ 초당 1,000개 요청 × 10,000번 탐색
= 초당 1,000만 번 탐색 발생!

epoll (현대):
데이터 온 연결만 이벤트로 반환
→ O(1) 이벤트 감지
→ 나머지 연결은 CPU 사용 없음
```

### epoll 동작 과정

```
1단계: epoll 인스턴스 생성
epfd = epoll_create()

2단계: 감시할 연결 등록
epoll_ctl(epfd, EPOLL_CTL_ADD, client_fd, event)

3단계: 이벤트 대기
events = epoll_wait(epfd)
→ 이벤트 발생할 때까지 블로킹
→ 이벤트 발생 시 해당 연결만 반환!

4단계: 이벤트 처리
for event in events:
    handle(event)
```

### 레벨 트리거 vs 엣지 트리거

```
레벨 트리거 (LT):
데이터가 남아있는 동안 계속 이벤트 발생
→ Redis 기본 모드

엣지 트리거 (ET):
데이터가 새로 도착할 때만 이벤트 발생
→ 더 효율적이지만 구현 복잡
→ Nginx가 사용
```

---

## 4. Redis 6.0 I/O 멀티스레딩

```
Redis 6.0 이전:
네트워크 I/O + 명령어 실행 = 모두 싱글스레드

Redis 6.0 이후:
네트워크 I/O    → 멀티스레드 (읽기/쓰기)
명령어 실행     → 여전히 싱글스레드!

이유:
→ 명령어 실행 싱글스레드 유지 = 원자성 보장 유지
→ 네트워크 I/O만 멀티스레드 = 처리량 향상

구조:
[I/O 스레드 1] ─┐
[I/O 스레드 2] ─┤→ [명령어 큐] → [실행 스레드 1개]
[I/O 스레드 3] ─┘
```

---

## 5. Lua Script 원자성

### 왜 필요한가?

```
GET + DECR 두 명령어:
서버 1: GET coupon:count → 1 반환
서버 2: GET coupon:count → 1 반환
서버 1: DECR coupon:count → 0
서버 2: DECR coupon:count → -1  ← 초과 발급!

→ 명령어 2개 사이에 다른 서버 끼어들기 가능
→ 원자성 깨짐!
```

### Lua Script 구현

```lua
-- 선착순 쿠폰 발급
local count = redis.call('GET', KEYS[1])

if tonumber(count) > 0 then
    redis.call('DECR', KEYS[1])
    redis.call('SADD', KEYS[2], ARGV[1])
    return 1  -- 발급 성공
else
    return 0  -- 쿠폰 소진
end
```

```java
// Spring에서 실행
String script = "...";
RedisScript<Long> redisScript = RedisScript.of(script, Long.class);

Long result = redisTemplate.execute(
    redisScript,
    Arrays.asList("coupon:count", "coupon:users"),
    userId
);
```

### 원자성 보장 원리

```
Lua Script = 여러 명령어를 하나로 묶음
→ Redis 이벤트 루프가 스크립트 전체를
  하나의 명령어로 처리
→ 중간에 끼어들기 불가 ✅
→ 멀티 서버 환경에서도 원자성 보장
```

---

## 6. MULTI/EXEC vs Lua Script

| | Lua Script | MULTI/EXEC |
|---|---|---|
| 원자성 | 완전 보장 ✅ | 실행 순서만 보장 |
| 조건 체크 | 가능 ✅ | 불가 ❌ |
| 중간 끼어들기 | 불가 ✅ | 가능 ❌ |
| 사용 상황 | 선착순, 재고 감소 | 단순 묶음 실행 |

```
MULTI/EXEC 한계:
MULTI
GET coupon:count    -- 결과를 조건에 활용 불가!
-- if count > 0?    ← 이게 안 됨!
DECR coupon:count
EXEC

MULTI/EXEC 적합한 경우:
MULTI
INCRBY user:1:point 100   # 포인트 추가
LPUSH user:1:log "적립"   # 로그 기록
EXEC
→ 조건 없이 두 작업을 함께 실행
```

---

## 7. Redis 메모리 구조

### jemalloc

```
일반 malloc:
메모리 파편화 심함
→ 할당/해제 반복 시 빈 공간 생김

jemalloc (Redis 기본 할당자):
크기별 버킷으로 관리
8B, 16B, 32B, 64B ... 4KB, 8KB ...

10MB 데이터 삭제 → 10MB 버킷에 반환
10MB 크기 요청  → 같은 버킷에서 재사용
→ 파편화 최소화!
```

### 메모리 사용량 확인

```bash
INFO memory

used_memory:              # 실제 사용 중인 메모리
used_memory_rss:          # OS가 할당한 메모리
mem_fragmentation_ratio:  # 파편화율 (RSS / used_memory)

파편화율 해석:
1.0 ~ 1.5 → 정상
1.5 ~ 2.0 → 주의
2.0 이상  → 위험 (메모리 낭비 심각)
0.5 미만  → 스왑 발생 중! (더 위험)
```

---

## 8. 자료구조 내부 인코딩

### 인코딩 자동 전환 기준

```
String:
정수값       → int 인코딩 (8바이트)
44바이트 이하 → embstr (연속 메모리)
44바이트 초과 → raw (별도 할당)

List:
요소 128개 이하 + 각 64바이트 이하 → listpack
초과 시 → quicklist

Hash:
필드 128개 이하 + 각 64바이트 이하 → listpack
초과 시 → hashtable (메모리 3배 이상 증가!)

ZSet:
요소 128개 이하 + 각 64바이트 이하 → listpack
초과 시 → skiplist + hashtable
```

### listpack 구조

```
연속된 메모리 블록:
[헤더][entry1][entry2]...[끝]

장점:
→ 포인터 없음 → 메모리 절약
→ 연속 메모리 → CPU 캐시 효율적

단점:
→ 중간 삽입/삭제 시 전체 재구성
→ 대규모 데이터에서 O(N)
```

### skiplist 구조

```
레벨 3: 1 ──────────────→ 5 ──────────→ 8
레벨 2: 1 ──────→ 3 ──→ 5 ──────→ 7 → 8
레벨 1: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8

→ O(log N) 탐색

ZSet이 skiplist + hashtable 동시 유지:
ZRANGE  → skiplist O(log N) ✅
ZSCORE  → hashtable O(1) ✅

유저 1명당 메모리: ~72바이트
1억 명 ZSet: 72 × 1억 = 7.2GB!
```

### 인코딩 임계값 튜닝

```bash
CONFIG SET hash-max-listpack-entries 128
CONFIG SET hash-max-listpack-value 64
CONFIG SET zset-max-listpack-entries 128
CONFIG SET zset-max-listpack-value 64

# 임계값 높이면 메모리 절약
# 단, 너무 높으면 O(N) 연산 증가 주의
```

---

## 9. 메모리 파편화 진단 및 해결

### 진단

```bash
INFO memory → mem_fragmentation_ratio 확인
2.0 이상 → 파편화 문제!

예시:
used_memory:     2GB  (실제 데이터)
used_memory_rss: 6GB  (OS 할당)
ratio:           3.0  → 4GB 낭비!
```

### 해결책

```bash
# 1. activedefrag (권장 - 서비스 중단 없음)
CONFIG SET activedefrag yes
CONFIG SET active-defrag-ignore-bytes 100mb
CONFIG SET active-defrag-threshold-lower 10
CONFIG SET active-defrag-cycle-min 5
CONFIG SET active-defrag-cycle-max 25

# 2. MEMORY PURGE (즉시 정리)
MEMORY PURGE
→ jemalloc에게 빈 메모리 OS 반환 요청

# 3. Redis 재시작 (최후의 수단)
→ 파편화 완전 초기화
→ 서비스 중단 발생
```

### 근본 원인 제거

```bash
# 잦은 DEL → TTL 기반으로 교체
❌ DEL coupon:user:1
✅ EXPIRE coupon:user:1 3600
→ Redis가 내부적으로 효율적으로 정리
```

---

## 10. O(N) 명령어 최적화

### 문제 명령어와 대체

| 문제 명령어 | 교체 명령어 | 이유 |
|---|---|---|
| KEYS * | SCAN | 커서 기반 페이지네이션 |
| SMEMBERS | SSCAN / ZRANGE | 부분 조회 가능 |
| HGETALL | HMGET / HSCAN | 필요한 필드만 조회 |

```bash
# SCAN 사용법
cursor = 0
loop:
    cursor, keys = SCAN cursor MATCH * COUNT 100
    처리(keys)
    if cursor == 0: break

# HMGET (필요한 필드만)
HMGET user:1:activity field1 field2 field3
```

### SLOWLOG로 진단

```bash
CONFIG SET slowlog-log-slower-than 10000  # 10ms 이상 기록
SLOWLOG GET 10  # 느린 명령어 상위 10개 조회
→ 실무 Redis 병목 진단 첫 번째 단계!
```

---

## 11. Master-Replica 복제

### 구조

```
Master (쓰기 전담)
    ↓ 비동기 복제
Replica 1 (읽기 전담)
Replica 2 (읽기 + 백업)
```

### 최초 복제 (Full Sync)

```
1. Replica → Master: PSYNC 전송
2. Master → RDB 스냅샷 생성
3. Master → RDB 파일 전송
4. 전송 중 변경사항 → Replication Buffer 저장
5. RDB 로드 완료 → 버퍼 변경사항 적용
```

### 복제 지연 (Replication Lag)

```
Master: SET balance 30000 완료
           ↓ 네트워크 전파 (수 ms)
Replica: 아직 50000 유지 중...

→ Replica에서 읽으면 이전 값 반환!
→ 최종 일관성 (Eventual Consistency)

해결:
중요한 읽기 (잔액, 결제) → Master 읽기
일반 캐시                  → Replica 읽기
```

### 비동기 vs 동기 복제

```bash
# 기본: 비동기 복제
→ 빠르지만 Master 다운 시 데이터 소실 가능

# WAIT: 동기 복제 보장
WAIT 1 100  # Replica 1개 확인, 100ms 타임아웃
→ 데이터 손실 방지
→ 금융 데이터에 적합
→ Latency 증가 감수
```

---

## 12. Sentinel 자동 Failover

### 역할

```
1. 모니터링: Master/Replica 상태 주기적 확인
2. 장애 감지: 과반수 동의 시 객관적 다운 선언
3. 자동 Failover: 새 Master 선출 + 재구성
4. 알림: 장애 발생 시 통보
```

### Failover 과정

```
T+0초:  Master 다운
        Sentinel 1: 주관적 다운 선언

T+5초:  과반수 Sentinel 동의
        → 객관적 다운 (Objective Down)

T+10초: Leader Sentinel 선출
        새 Master 선정 기준:
        1. 복제 Offset 가장 큰 Replica
        2. replica-priority 설정값
        3. Run ID (마지막 기준)

T+15초: Replica A → 새 Master 승격
        Replica B → 새 Master 바라보도록 변경
        애플리케이션에 새 Master 주소 통보

총 다운타임: 약 15~30초
```

### Spring 설정

```yaml
spring:
  redis:
    sentinel:
      master: mymaster
      nodes:
        - sentinel1:26379
        - sentinel2:26379
        - sentinel3:26379
→ Sentinel 주소만 설정하면
  자동으로 새 Master 연결!
```

### Sentinel 한계

```
단일 Master 구조 유지:
→ 쓰기는 여전히 1대만
→ 메모리 한계: 단일 서버 메모리
→ 적합한 규모: 데이터 수십 GB 이하
```

---

## 13. Cluster 해시슬롯 샤딩

### 구조

```
해시슬롯 16384개를 노드별 분산:

Master 1: 슬롯 0    ~ 5461
Master 2: 슬롯 5462 ~ 10922
Master 3: 슬롯 10923 ~ 16383

슬롯 계산:
슬롯 번호 = CRC16(key) % 16384
```

### MOVED 리다이렉션

```
클라이언트 → Master 1: "GET user:1"
Master 1:   슬롯 7638은 Master 2에 있음
            → MOVED 7638 Master2:6379 반환
클라이언트 → Master 2: 재요청 → 값 반환

Smart Client (Lettuce):
→ 슬롯 맵 캐싱
→ 처음부터 올바른 노드에 직접 요청
```

### Cluster 한계

```
1. 멀티키 명령어 제한:
   MGET user:1 user:2 → 다른 슬롯이면 에러!

2. 트랜잭션 제한:
   MULTI/EXEC도 같은 슬롯 키만 가능

3. DB 선택 불가:
   DB 0만 사용 가능
```

---

## 14. 해시태그와 원자성

### 해시태그 동작

```bash
# 해시태그 없음 (다른 슬롯 가능):
coupon:count → 슬롯 3000
coupon:users → 슬롯 8000
→ Lua Script 실행 불가!

# 해시태그 적용 (같은 슬롯 보장):
{coupon}:count → CRC16("coupon") % 16384
{coupon}:users → CRC16("coupon") % 16384
→ 같은 슬롯 보장 → Lua Script 원자성 ✅
```

### 주의사항

```bash
# ❌ 해시태그 남용 (Hot Key 발생)
{app}:user:1
{app}:order:1
{app}:ranking:1
→ 전부 같은 슬롯 → Cluster 의미 없음

# ✅ 원자적으로 처리할 키끼리만 묶기
{coupon}:count   ← 쿠폰끼리만
{ranking}:score  ← 랭킹끼리만
{order:1}:status ← 주문별로
```

---

## 15. Hot Key 문제

### 정의

```
특정 키에 트래픽 집중:
coupon:count에 20만 RPS 집중
→ 해당 슬롯의 Master 1대가 20만 RPS 처리
→ Redis 단일 서버 한계 ~10만 ops/sec
→ Cluster 써도 해당 노드만 병목!
```

### 해결책

```
1. Nginx Rate Limiting (권장):
   [20만 RPS] → [Nginx: 1만명만 통과]
   → 트래픽 자체를 줄임
   → 단일 카운터 유지 가능

2. Counter Sharding:
   coupon:count:0 ~ coupon:count:9
   → 각 키에 2만 RPS로 분산
   단점: 순서 보장 어려움, 전체 수량 파악 복잡

3. 대기열 + 단일 카운터:
   [20만 RPS] → [Redis 대기열] → [순서대로 처리]
   → 카운터는 단일 키 유지

4. 로컬 캐시 (Caffeine):
   자주 읽히는 키 → 앱 서버 메모리에 캐싱
   → Redis 요청 자체를 줄임
```

---

## 16. Sentinel vs Cluster 선택 기준

| | Sentinel | Cluster |
|---|---|---|
| 목적 | 고가용성 (HA) | 수평 확장 + HA |
| Master 수 | 1개 | 여러 개 (최소 3개) |
| 데이터 분산 | ❌ | ✅ 자동 샤딩 |
| 쓰기 확장 | ❌ | ✅ |
| 멀티키 명령어 | ✅ 자유롭게 | 제한적 |
| 운영 복잡도 | 낮음 | 높음 |
| 적합한 규모 | ~수십 GB | 수백 GB ~ TB |

### 선택 기준

```
Sentinel 선택:
→ 데이터 단일 서버로 충분 (수십 GB 이하)
→ MGET 등 멀티키 명령어 많이 사용
→ 운영 경험 적음 / 스타트업 초기

Cluster 선택:
→ 데이터가 단일 서버 메모리 초과
→ 쓰기 트래픽 ~10만 ops/sec 초과
→ 글로벌 대규모 서비스

실제 사례:
당근마켓 초기: Sentinel → 성장 후 Cluster 전환
토스:          Sentinel + 용도별 Redis 분리
라인:          Cluster (수백억 건 메시지)
```

---

## 📊 2단계 핵심 연결고리

```
Redis 빠른 이유
        ↓
Single-thread + epoll → 원자성 보장
        ↓
명령어 1개 = 원자적 / 명령어 2개 = 원자성 깨짐
        ↓
Lua Script로 여러 명령어를 하나로 묶음
        ↓
Cluster에서 다른 슬롯 → Lua Script 불가
        ↓
해시태그로 같은 슬롯 보장
        ↓
단일 서버 한계 → Master-Replica
        ↓
Master 장애 → Sentinel 자동 Failover
        ↓
데이터 TB 규모 → Cluster 수평 확장
        ↓
특정 키 집중 → Hot Key → Rate Limiting
```

---

## ✅ 2단계 완료 체크리스트

```
✅ Redis가 빠른 이유 (4가지)
✅ Single-thread 이벤트 루프
✅ epoll 동작 원리 (select vs epoll)
✅ Redis 6.0 I/O 멀티스레딩
✅ Lua Script 원자성
✅ MULTI/EXEC vs Lua Script
✅ jemalloc 파편화 최소화
✅ 자료구조 인코딩 전환 기준
✅ listpack / skiplist 내부 구조
✅ 메모리 파편화 진단 (INFO memory)
✅ activedefrag 설정
✅ O(N) 명령어 → SCAN 교체
✅ SLOWLOG 실무 활용
✅ Master-Replica 복제 동작
✅ 복제 지연 (Replication Lag)
✅ WAIT 동기 복제
✅ Sentinel 자동 Failover 과정
✅ Cluster 해시슬롯 16384개
✅ MOVED 리다이렉션
✅ 해시태그 슬롯 보장
✅ Hot Key 문제 및 해결
✅ Counter Sharding 패턴
✅ Sentinel vs Cluster 선택 기준
```

---