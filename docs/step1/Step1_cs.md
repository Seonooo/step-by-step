# 1단계 개념 정리 — 기본 CS 개념

---
## 📋 목차

1. [프로세스 vs 스레드 vs 코루틴](#1-프로세스-vs-스레드-vs-코루틴)
2. [컨텍스트 스위칭](#2-컨텍스트-스위칭)
3. [I/O bound vs CPU bound](#3-io-bound-vs-cpu-bound)
4. [버추얼 스레드 (Java 21)](#4-버추얼-스레드-java-21)
5. [RPS 계산 공식](#5-rps-계산-공식)
6. [뮤텍스 vs 세마포어](#6-뮤텍스-vs-세마포어)
7. [데드락](#7-데드락)
8. [synchronized vs ReentrantLock](#8-synchronized-vs-reentrantlock)
9. [벌크헤드 패턴](#9-벌크헤드-패턴)
10. [커넥션 풀 병목](#10-커넥션-풀-병목)
11. [Spring WebFlux vs Resilience4j](#11-spring-webflux-vs-resilience4j)
12. [대기열 시스템](#12-대기열-시스템)
13. [Latency vs Throughput vs Bandwidth](#13-latency-vs-throughput-vs-bandwidth)
14. [TCP vs UDP](#14-tcp-vs-udp)
15. [HTTP 버전 발전](#15-http-버전-발전)
16. [QUIC 순서 보장](#16-quic-순서-보장)
17. [TLS / HTTPS](#17-tls--https)
18. [Certificate Pinning](#18-certificate-pinning)

---

## 1. 프로세스 vs 스레드 vs 코루틴

| | 프로세스 | 스레드 | 코루틴 |
|---|---|---|---|
| 메모리 | 독립 공간 | 힙 공유 | 스택 공유 |
| 생성 비용 | 무거움 (~수 ms) | 중간 (~수십 µs) | 가벼움 (~수 µs) |
| 전환 비용 | 높음 | 중간 | 매우 낮음 |
| 스케줄링 주체 | OS | OS | 애플리케이션 |
| 적합한 상황 | 격리 필요 | CPU bound | I/O bound |

**실무 연결:**
- 배민 API 서버 → Kotlin Coroutine으로 스레드 1개로 수천 개 DB 요청 처리
- 코루틴은 I/O 대기 중 다른 코루틴으로 전환 → 컨텍스트 스위칭 비용 거의 없음

---

## 2. 컨텍스트 스위칭

```
[작업 A 실행 중]
    → 인터럽트 발생
    → A의 Context를 PCB(Process Control Block)에 저장
    → B의 Context를 PCB에서 불러옴
[작업 B 실행 시작]
```

- 소요 시간: 약 **1~10 µs**
- 스레드 수천 개 → 오버헤드 누적 → 성능 저하
- 코루틴이 등장한 이유: 컨텍스트 스위칭 없이 애플리케이션 레벨에서 전환

---

## 3. I/O bound vs CPU bound

```
I/O bound (DB, 네트워크 대기):
→ 스레드가 대기 중 CPU 낭비
→ 코루틴 / 버추얼 스레드로 해결
→ 대기 중 다른 작업으로 전환

CPU bound (암호화, 이미지 처리):
→ 연산 중 양보 타이밍 없음
→ 멀티스레드 (코어 수 기준)
→ 적정 스레드 수 = CPU 코어 수 + 1
→ 초과 시 스케일 아웃
```

| 상황 | 유리한 모델 | 이유 |
|---|---|---|
| I/O bound | 코루틴 / VT | 대기 중 양보 → CPU 낭비 없음 |
| CPU bound | 멀티스레드 | OS 강제 전환, 병렬 처리 가능 |

**핵심 공식:**
```
I/O bound 스레드 풀 크기 = CPU 코어 수 × (1 + 대기시간/처리시간)
CPU bound 스레드 풀 크기 = CPU 코어 수 + 1
```

---

## 4. 버추얼 스레드 (Java 21)

### 플랫폼 스레드 vs 버추얼 스레드 구조

```
플랫폼 스레드:
OS 스레드 1 ──── 플랫폼 스레드 1 (1:1 매핑)

버추얼 스레드:
OS 스레드 1 ──┬── 버추얼 스레드 1
              ├── 버추얼 스레드 2
              └── ... (수만 개)
```

### 코루틴 vs 버추얼 스레드

| | 코루틴 | 버추얼 스레드 |
|---|---|---|
| 도입 주체 | 개발자가 직접 설계 | JVM이 자동 관리 |
| 코드 변경 | suspend, async 등 필요 | 기존 동기 코드 그대로 |
| 언어 | Kotlin, Python 등 | Java 21+ |
| 학습 비용 | 높음 | 낮음 |
| CPU bound | 불리 | 불리 |

### VT 주의사항

```
1. Pinning 문제:
   synchronized 블록 안 I/O 발생
   → OS 스레드에 고정(pin) → VT 이점 사라짐
   → ReentrantLock으로 교체 필요

2. 커넥션 풀 고갈:
   VT 수만 개 생성 → DB 커넥션 풀(10~20개) 쟁탈
   → 세마포어 또는 Resilience4j로 제한

3. CPU bound 시 VT 수 폭발:
   세마포어로 동시 VT 수 제한 필요
```

### 선택 기준

```
Kotlin 기반 서비스 (토스, 배민) → 코루틴
Java 레거시 전환 (네이버, 라인) → 버추얼 스레드
```

---

## 5. RPS 계산 공식

```
최대 RPS = 스레드 수 × (1000ms ÷ 평균 처리 시간)

예시: 스레드 200개, DB 응답 50ms
→ 200 × (1000 ÷ 50) = 4,000 RPS
```

**병목 진단:**
```
스레드 풀 포화 + CPU 여유 → 스레드/VT 문제
CPU 높음 + 네트워크 높음  → Bandwidth 문제
Latency 높음 + RPS 낮음   → 서버 처리 문제
DB 커넥션 대기 급증       → 커넥션 풀 고갈
```

---

## 6. 뮤텍스 vs 세마포어

### 뮤텍스

```java
lock.lock();
try {
    // 임계 구역: 공유 자원 접근
    // if 체크도 반드시 lock 안에!
} finally {
    lock.unlock(); // 예외 발생해도 반드시 해제
}
```

- 한 번에 1개 스레드만 임계 구역 진입
- lock을 건 스레드만 unlock 가능
- **try-finally 패턴 필수** (예외 발생 시 unlock 보장)

### 세마포어

```java
Semaphore semaphore = new Semaphore(3); // 동시 접근 3개 허용

semaphore.acquire(); // 자리 없으면 대기
try {
    // 작업 수행
} finally {
    semaphore.release(); // 다른 스레드도 release 가능
}
```

- N개 스레드 동시 접근 허용
- DB 커넥션 풀 제어에 활용

### 핵심 실수 — Lock 범위

```java
// ❌ 잘못된 코드 (Race Condition 발생)
if (point + amount <= 100000) {  // lock 밖에서 체크
    mutex.lock();
    point += amount;
    mutex.unlock();
}

// ✅ 올바른 코드
mutex.lock();
try {
    if (point + amount <= 100000) {  // lock 안에서 체크
        point += amount;
    }
} finally {
    mutex.unlock();
}
```

---

## 7. 데드락

### 발생 조건 (4가지 모두 충족 시)

```
1. 상호 배제: 자원을 1개 스레드만 점유
2. 점유 대기: 자원 들고 다른 자원 기다림
3. 비선점:   강제로 자원 빼앗기 불가
4. 순환 대기: A→B→A 순환 구조
```

### 해결책

```java
// ❌ 데드락 발생
스레드 A: lock(mutex1) → lock(mutex2)
스레드 B: lock(mutex2) → lock(mutex1)  // 순서 다름!

// ✅ 항상 같은 순서로 lock 획득
스레드 A: lock(mutex1) → lock(mutex2)
스레드 B: lock(mutex1) → lock(mutex2)  // 순서 동일
```

---

## 8. synchronized vs ReentrantLock

| | synchronized | ReentrantLock |
|---|---|---|
| 코드 간결성 | 높음 (키워드) | 낮음 (직접 관리) |
| unlock 보장 | 자동 | finally 필수 |
| 타임아웃 설정 | 불가 | 가능 |
| 공정성 설정 | 불가 | 가능 |
| 버추얼 스레드 | Pinning 발생 ❌ | 안전 ✅ |
| 조건 변수 | 1개 | 여러 개 |

### AtomicInteger (CAS)

```java
// compareAndSet: 기대값과 같을 때만 변경 (원자적 연산)
int current;
do {
    current = couponCount.get();
    if (current <= 0) return;
} while (!couponCount.compareAndSet(current, current - 1));
```

**선택 기준:**
```
경쟁 적을 때 (스레드 10개 이하):
AtomicInteger > ReentrantLock > synchronized

경쟁 많을 때 (스레드 1,000개):
ReentrantLock > synchronized > AtomicInteger
→ CAS 재시도 폭발로 AtomicInteger 역전!
```

---

## 9. 벌크헤드 패턴

**문제:** 특정 API가 커넥션 독점 → 다른 API 굶어 죽음

```java
// API별 세마포어 분리
BulkheadConfig config = BulkheadConfig.custom()
    .maxConcurrentCalls(16)
    .maxWaitDuration(Duration.ofMillis(100))
    .build();

Bulkhead orderBulkhead = Bulkhead.of("orderApi", config);
```

```
주문 API: 16개
검색 API: 8개
유저 API: 8개
→ 한 API 장애가 다른 API에 영향 없음
```

- 실무 구현: **Resilience4j Bulkhead**
- 선박의 격벽(Bulkhead) 구조에서 유래

---

## 10. 커넥션 풀 병목

### 적정 크기 산정

```
기본 공식 (HikariCP 권장):
풀 크기 = DB 코어 수 × 2 ~ 4

예시: DB 서버 코어 8개
→ 적정: 16 ~ 32개
```

### HikariCP 실무 설정

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 32       # DB 코어 수 × 4
      minimum-idle: 10
      connection-timeout: 3000    # 3초 초과 시 에러 반환
      idle-timeout: 600000        # 유휴 커넥션 제거 (10분)
      max-lifetime: 1800000       # 커넥션 최대 수명 (30분)
```

### 무작정 늘리면 안 되는 이유

```
커넥션 1개 = DB 서버 메모리 ~5~10MB
커넥션 1,000개 = 5~10GB → DB 서버 OOM 위험
```

---

## 11. Spring WebFlux vs Resilience4j

| | Resilience4j | Spring WebFlux |
|---|---|---|
| 역할 | 방어막 추가 | 구조 자체를 논블로킹으로 |
| 코드 변경 | 어노테이션만 추가 | 전면 재작성 필요 |
| 블로킹 코드 | 그대로 사용 가능 | 혼용 시 서버 마비 위험 |
| 학습 비용 | 낮음 | 높음 |
| 배압 지원 | 없음 | 있음 |

**선택 기준:**
```
레거시 + 빠른 안정화 필요 → Resilience4j + VT
새 프로젝트 + 대용량 트래픽 → Spring WebFlux
팀 WebFlux 경험 없음 → Resilience4j 먼저
```

**실제 사례:**
```
토스:   WebFlux 적극 도입
배민:   Kotlin Coroutine + WebFlux 혼용
네이버: Resilience4j + VT 전환 중
라인:   Java → Kotlin 전환하며 Coroutine 도입
```

---

## 12. 대기열 시스템

**핵심: WebFlux보다 Redis + Polling 조합**

```
[10만 명 접속]
    ↓
[Nginx] Rate Limiting → 초당 N명만 통과
    ↓
[Redis ZADD] 대기 번호 발급 (타임스탬프 기반)
    ↓
[클라이언트] 3~5초마다 순서 확인 폴링
    ↓
순서 됐을 때만 [실제 서비스] 진입
```

**중요:**
- 대기열 순서 보장 = **Redis가 담당** (HTTP 버전과 무관!)
- 폴링 기반 = 요청/응답 작고 단순 → HOL Blocking 영향 미미

---

## 13. Latency vs Throughput vs Bandwidth

```
Latency:   요청 1개 완료까지 걸리는 시간
           100ms 이하 → 사용자가 즉각 반응으로 느낌
           300ms 이상 → 명확히 느리다 느낌
           1초 이상   → 사용자 이탈 시작

Throughput: 단위 시간당 처리 요청 수 (RPS)

Bandwidth:  네트워크 초당 전송 가능 데이터 양
            일반 서버 NIC: 1 Gbps = 초당 125 MB
            병목 시 → CDN으로 해결
```

**비유:**
```
Bandwidth  = 도로의 차선 수
Throughput = 실제로 지나가는 차량 수
Latency    = 차 1대가 목적지까지 걸리는 시간
```

---

## 14. TCP vs UDP

| | TCP | UDP |
|---|---|---|
| 연결 | 3-Way Handshake | 없음 |
| 순서 보장 | ✅ | ❌ |
| 유실 재전송 | ✅ | ❌ |
| 흐름/혼잡 제어 | ✅ | ❌ |
| 속도 | 느림 | 빠름 |
| 사용 | 결제, 메시지, HTTP | 스트리밍, 게임, DNS |

**UDP가 스트리밍에 유리한 이유:**
```
오래된 데이터는 의미없고 최신 데이터만 중요한 경우
→ 1프레임 유실돼도 다음 프레임으로 진행
→ TCP는 재전송 대기 → 버퍼링 발생
```

---

## 15. HTTP 버전 발전

### HTTP/1.1 문제 — HOL Blocking

```
요청 순차 처리 + 브라우저 병렬 6개 연결
→ 36개 리소스: ~600ms
→ Head Of Line Blocking 심각
```

### HTTP/2 개선

```
멀티플렉싱: 하나의 TCP 연결로 여러 요청 동시 처리
→ 핸드셰이크 6번 → 1번
→ 36개 리소스: ~200ms
→ 페이지 로딩 약 50% 개선

BUT TCP 레벨 HOL Blocking은 여전히 존재:
패킷 유실 → 전체 스트림 대기
```

### HTTP/3 (QUIC) 해결

```
UDP 기반 QUIC으로 TCP 교체
→ 스트림별 독립적 패킷 관리
→ 패킷 유실 시 해당 스트림만 재전송
→ Connection Migration (모바일 강점)
→ 연결 수립 0~1 RTT
→ 36개 리소스: ~100ms
```

### 버전별 비교

| | HTTP/1.1 | HTTP/2 | HTTP/3 |
|---|---|---|---|
| 기반 프로토콜 | TCP | TCP | UDP (QUIC) |
| 멀티플렉싱 | ❌ | ✅ | ✅ |
| HOL Blocking | 심각 | TCP 레벨 존재 | 없음 |
| 연결 수립 | 1 RTT | 1 RTT | 0~1 RTT |
| 모바일 환경 | 취약 | 취약 | 강함 |

---

## 16. QUIC 순서 보장

```
UDP 자체: 순서 보장 ❌
QUIC:     스트림 내부 순서 보장 ✅
          스트림 간에는 독립적 ✅

TCP 패킷 유실:
→ 전체 스트림 대기 (HOL Blocking)

QUIC 패킷 유실:
→ 해당 스트림만 재전송
→ 나머지 스트림 계속 진행

"UDP처럼 빠르면서 TCP처럼 신뢰성 있는 프로토콜"
```

---

## 17. TLS / HTTPS

### TLS Handshake 과정

```
1. Client Hello: 지원 암호화 방식 전송
2. Server Hello + 인증서(공개키 포함) 전송
3. 클라이언트 → CA에서 인증서 검증
4. 세션 키(대칭키) 생성 → 이후 통신에 사용
```

### 대칭키 vs 비대칭키

```
비대칭키 (Handshake 중):
공개키 암호화 → 개인키 복호화
→ 안전하지만 느림 (~1000배)
→ 키 교환 시 사용

대칭키 (실제 데이터 전송):
같은 키로 암호화/복호화
→ 빠름 (~10만 ops/sec)
→ 비대칭키로 안전하게 교환 후 사용
```

### TLS 1.2 vs TLS 1.3

```
TLS 1.2: Handshake = 2 RTT
TLS 1.3: Handshake = 1 RTT
         0-RTT 재연결 지원 (세션 티켓 재사용)
         단, 0-RTT는 Replay Attack 주의
         → 멱등성 있는 요청(GET)에만 사용
```

### 성능 최적화

```
1. TLS Session Resumption: 세션 티켓 재사용 → 0-RTT
2. HTTP/2 Keep-Alive: 하나의 TLS 연결로 여러 요청
3. OCSP Stapling: 인증서 유효성 서버가 미리 확인
```

---

## 18. Certificate Pinning

### 왜 필요한가?

```
일반 HTTPS의 취약점:
CA가 해킹되면 가짜 인증서도 신뢰 → MITM 공격 가능

실제 사례: 2011년 DigiNotar CA 해킹
→ 구글, 카카오 등 가짜 인증서 대량 발급
```

### 동작 방식

```java
// 앱에 서버 공개키 해시값을 미리 저장
String[] pins = {
    "sha256/ABC123...",  // 현재 인증서
    "sha256/XYZ789..."   // 백업 인증서
};

// 연결 시 서버 인증서와 비교
if (serverCertPublicKey != pinnedPublicKey) {
    throw new SSLException("Certificate Pinning 실패");
    // CA 서명 있어도 차단!
}
```

### 장단점

```
장점:
✅ CA 해킹 대응
✅ MITM 공격 원천 차단

단점:
❌ 인증서 갱신 시 앱 업데이트 필요
   → 앱스토어 심사 1~3일 소요
   → 금융앱에서 치명적
❌ 백업 핀 반드시 등록 필요
```

### MITM 방어 전체 레이어

```
레이어 1: TLS Handshake → 기본 암호화
레이어 2: Certificate Pinning → CA 해킹 대응
레이어 3: HSTS → HTTP 차단, HTTPS만 허용
레이어 4: Certificate Transparency → 가짜 인증서 탐지
```

---

## 📊 1단계 핵심 연결고리

```
컨텍스트 스위칭 비용
        ↓
코루틴 / 버추얼 스레드로 해결 (I/O bound)
        ↓
VT 수만 개 생성
        ↓
DB 커넥션 풀 고갈 (새로운 병목)
        ↓
세마포어 + 벌크헤드 패턴으로 해결
        ↓
RPS = 스레드 수 × (1000 ÷ Latency)
        ↓
Latency / Throughput / Bandwidth 구분
        ↓
TCP → HTTP/2 → HTTP/3 발전
        ↓
TLS + Certificate Pinning으로 보안
```

---

## ✅ 1단계 완료 체크리스트

```
✅ 프로세스 / 스레드 / 코루틴
✅ 컨텍스트 스위칭
✅ I/O bound vs CPU bound
✅ 버추얼 스레드 (Java 21)
✅ RPS 계산 공식
✅ 뮤텍스 / 세마포어 / 데드락
✅ synchronized vs ReentrantLock
✅ 벌크헤드 패턴
✅ 커넥션 풀 병목
✅ Spring WebFlux vs Resilience4j
✅ 대기열 시스템
✅ Latency / Throughput / Bandwidth
✅ TCP / UDP
✅ HTTP/1.1 → HTTP/2 → HTTP/3
✅ QUIC 순서 보장
✅ TLS / HTTPS
✅ Certificate Pinning
```

---

## 🔗 다음 단계

> **2단계: Redis 심화**
> - Single-thread 이벤트 루프
> - Master-Replica 복제
> - Sentinel vs Cluster
> - Lua Script 원자성
> - 실습: Docker Redis + 선착순 쿠폰 + 1만 TPS 부하 테스트