# 1단계 실습 정리 — 컨텍스트 스위칭 & 동시성

---

## 📋 실습 목록

| 실습 | 주제 | 핵심 결과 |
|---|---|---|
| 실습 1 | 스레드 풀 크기별 RPS 측정 | VT 8.7배 이점 확인 |
| 실습 2 | 커넥션 풀 병목 재현 | 커넥션 풀이 병목 시 VT 이점 사라짐 |
| 실습 3 | 외부 API 응답별 VT 이점 | 외부 API 500ms: VT 53% 빠름 |
| 실습 4 | 싱글 vs 멀티스레드 | I/O bound 싱글 대비 VT 944배 |
| 실습 5 | Race Condition 재현 + 해결 | 쿠폰 11개 초과 발급 재현 후 Lock으로 해결 |

---

## 🛠️ 실습 1 — 스레드 풀 크기별 RPS 측정

### 코드

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolBenchmark {

    static Semaphore connectionPool = new Semaphore(32);

    static void simulateRedis() throws InterruptedException { Thread.sleep(5); }

    static void simulateDB() throws InterruptedException {
        connectionPool.acquire();
        try { Thread.sleep(50); } finally { connectionPool.release(); }
    }

    static void simulateKafka() throws InterruptedException { Thread.sleep(10); }

    static void simulateCPUTask() throws InterruptedException {
        long sum = 0;
        for (long i = 0; i < 10_000_000L; i++) sum += i;
    }

    @FunctionalInterface
    interface IOTask { void run() throws InterruptedException; }

    static void benchmark(String name, ExecutorService executor,
                          int totalRequests, IOTask task) throws Exception {
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        long start = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    task.run();
                    completed.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[%s]%n  완료: %d건 / %dms / RPS: %.1f%n",
            name, completed.get(), elapsed,
            completed.get() / (elapsed / 1000.0));
        executor.shutdown();
    }

    public static void main(String[] args) throws Exception {
        int totalRequests = 10000;
        int cpuRequests = 100;

        System.out.println("=== JVM 워밍업 중... ===");
        benchmark("워밍업", Executors.newVirtualThreadPerTaskExecutor(),
            100, () -> Thread.sleep(50));
        System.out.println("워밍업 완료!\n");

        System.out.println("=== I/O Bound 벤치마크 (커넥션 풀 없음) ===");
        benchmark("플랫폼 스레드 10개",
            Executors.newFixedThreadPool(10), totalRequests, () -> Thread.sleep(50));
        benchmark("플랫폼 스레드 50개",
            Executors.newFixedThreadPool(50), totalRequests, () -> Thread.sleep(50));
        benchmark("플랫폼 스레드 200개",
            Executors.newFixedThreadPool(200), totalRequests, () -> Thread.sleep(50));
        benchmark("버추얼 스레드",
            Executors.newVirtualThreadPerTaskExecutor(), totalRequests, () -> Thread.sleep(50));

        System.out.println("\n=== CPU Bound 벤치마크 ===");
        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("CPU 코어 수: " + cpuCores);
        benchmark("플랫폼 스레드 " + cpuCores + "개 (코어 수)",
            Executors.newFixedThreadPool(cpuCores), cpuRequests, () -> simulateCPUTask());
        benchmark("플랫폼 스레드 " + (cpuCores * 2) + "개 (코어 * 2)",
            Executors.newFixedThreadPool(cpuCores * 2), cpuRequests, () -> simulateCPUTask());
        benchmark("버추얼 스레드 (CPU bound)",
            Executors.newVirtualThreadPerTaskExecutor(), cpuRequests, () -> simulateCPUTask());

        System.out.println("\n=== 커넥션 풀 크기별 벤치마크 ===");
        connectionPool = new Semaphore(10);
        benchmark("플랫폼 스레드 200개 (풀 10개)",
            Executors.newFixedThreadPool(200), totalRequests, () -> simulateDB());
        connectionPool = new Semaphore(10);
        benchmark("버추얼 스레드 (풀 10개)",
            Executors.newVirtualThreadPerTaskExecutor(), totalRequests, () -> simulateDB());
        connectionPool = new Semaphore(32);
        benchmark("플랫폼 스레드 200개 (풀 32개)",
            Executors.newFixedThreadPool(200), totalRequests, () -> simulateDB());
        connectionPool = new Semaphore(32);
        benchmark("버추얼 스레드 (풀 32개)",
            Executors.newVirtualThreadPerTaskExecutor(), totalRequests, () -> simulateDB());

        System.out.println("\n=== 외부 API 응답 속도별 비교 (커넥션 풀 32개) ===");

        connectionPool = new Semaphore(32);
        benchmark("플랫폼 200개 - 외부API 없음 (65ms)",
            Executors.newFixedThreadPool(200), totalRequests,
            () -> { simulateRedis(); simulateDB(); simulateKafka(); });

        connectionPool = new Semaphore(32);
        benchmark("VT - 외부API 없음 (65ms)",
            Executors.newVirtualThreadPerTaskExecutor(), totalRequests,
            () -> { simulateRedis(); simulateDB(); simulateKafka(); });

        connectionPool = new Semaphore(32);
        benchmark("플랫폼 200개 - 외부API 200ms (265ms)",
            Executors.newFixedThreadPool(200), totalRequests,
            () -> { simulateRedis(); simulateDB(); Thread.sleep(200); simulateKafka(); });

        connectionPool = new Semaphore(32);
        benchmark("VT - 외부API 200ms (265ms)",
            Executors.newVirtualThreadPerTaskExecutor(), totalRequests,
            () -> { simulateRedis(); simulateDB(); Thread.sleep(200); simulateKafka(); });

        connectionPool = new Semaphore(32);
        benchmark("플랫폼 200개 - 외부API 500ms (565ms)",
            Executors.newFixedThreadPool(200), totalRequests,
            () -> { simulateRedis(); simulateDB(); Thread.sleep(500); simulateKafka(); });

        connectionPool = new Semaphore(32);
        benchmark("VT - 외부API 500ms (565ms)",
            Executors.newVirtualThreadPerTaskExecutor(), totalRequests,
            () -> { simulateRedis(); simulateDB(); Thread.sleep(500); simulateKafka(); });
    }
}
```

### 실측 결과 (10,000건 기준)

```
=== I/O Bound (커넥션 풀 없음) ===
플랫폼 스레드 10개:   165 RPS
플랫폼 스레드 50개:   828 RPS
플랫폼 스레드 200개: 3,248 RPS
버추얼 스레드:       28,248 RPS  ← 8.7배 차이!

=== CPU Bound ===
플랫폼 스레드 8개:  1,886 RPS
플랫폼 스레드 16개: 2,439 RPS
버추얼 스레드:      2,173 RPS  ← 코어*2보다 느림

=== 커넥션 풀 병목 ===
풀 10개 플랫폼: 166 RPS
풀 10개 VT:    164 RPS  ← 거의 동일!
풀 32개 플랫폼: 543 RPS
풀 32개 VT:    526 RPS  ← 거의 동일!

=== 외부 API 속도별 (커넥션 풀 32개) ===
외부API 없음  플랫폼: 528 RPS  / VT: 523 RPS  (동일)
외부API 200ms 플랫폼: 459 RPS  / VT: 454 RPS  (동일)
외부API 500ms 플랫폼: 339 RPS  / VT: 521 RPS  ← VT 53% 빠름!
```

### 핵심 교훈

| 상황 | 결론 |
|---|---|
| I/O bound (커넥션 풀 없음) | VT 8.7배 압도적 |
| 커넥션 풀이 병목 | VT ≈ 플랫폼 스레드 |
| 외부 API 길수록 | VT 이점 증가 (총 처리시간 312ms 초과 시 전환점) |
| CPU bound | 코어 수 기반 플랫폼 스레드가 유리 |

---

## 🛠️ 실습 2 — 싱글 vs 멀티스레드 벤치마크

### 코드

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SingleVsMultiBenchmark {

    static void ioTask() throws InterruptedException { Thread.sleep(50); }

    static long cpuTask() {
        long sum = 0;
        for (long i = 0; i < 10_000_000L; i++) sum += i;
        return sum;
    }

    static void singleThread(String name, int totalRequests,
                              boolean isIO) throws Exception {
        long start = System.currentTimeMillis();
        for (int i = 0; i < totalRequests; i++) {
            if (isIO) ioTask(); else cpuTask();
        }
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[싱글스레드 - %s]%n  완료: %d건 / %dms / RPS: %.1f%n",
            name, totalRequests, elapsed, totalRequests / (elapsed / 1000.0));
    }

    static void multiThread(String name, int threadCount,
                             int totalRequests, boolean isIO) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(totalRequests);
        AtomicInteger completed = new AtomicInteger(0);
        long start = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    if (isIO) ioTask(); else cpuTask();
                    completed.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        System.out.printf("[멀티스레드 %d개 - %s]%n  완료: %d건 / %dms / RPS: %.1f%n",
            threadCount, name, completed.get(), elapsed,
            completed.get() / (elapsed / 1000.0));
        executor.shutdown();
    }

    public static void main(String[] args) throws Exception {
        int cpuCores = Runtime.getRuntime().availableProcessors();
        System.out.println("CPU 코어 수: " + cpuCores);

        System.out.println("\n=== I/O Bound: 싱글 vs 멀티스레드 ===");
        singleThread("I/O bound", 100, true);
        multiThread("I/O bound", 10, 1000, true);
        multiThread("I/O bound", cpuCores, 1000, true);
        multiThread("I/O bound", 200, 1000, true);

        // 버추얼 스레드
        ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(1000);
        AtomicInteger vtCompleted = new AtomicInteger(0);
        long vtStart = System.currentTimeMillis();
        for (int i = 0; i < 1000; i++) {
            vtExecutor.submit(() -> {
                try { ioTask(); vtCompleted.incrementAndGet(); }
                catch (Exception e) { e.printStackTrace(); }
                finally { latch.countDown(); }
            });
        }
        latch.await();
        long vtElapsed = System.currentTimeMillis() - vtStart;
        System.out.printf("[버추얼 스레드 - I/O bound]%n  완료: %d건 / %dms / RPS: %.1f%n",
            vtCompleted.get(), vtElapsed, vtCompleted.get() / (vtElapsed / 1000.0));
        vtExecutor.shutdown();

        System.out.println("\n=== CPU Bound: 싱글 vs 멀티스레드 ===");
        singleThread("CPU bound", 100, false);
        multiThread("CPU bound", cpuCores, 100, false);
        multiThread("CPU bound", cpuCores * 2, 100, false);
        multiThread("CPU bound", cpuCores * 10, 100, false);
    }
}
```

### 실측 결과

```
=== I/O Bound ===
싱글스레드 100건:     5,970ms / 16.8 RPS
멀티 8개 1000건:      7,215ms / 138.6 RPS
멀티 10개 1000건:     6,111ms / 163.6 RPS
멀티 200개 1000건:      319ms / 3,134 RPS
버추얼 스레드 1000건:    63ms / 15,873 RPS  ← 싱글 대비 944배!

=== CPU Bound ===
싱글스레드 100건:  258ms / 387 RPS  (기준)
멀티 8개:           43ms / 2,325 RPS  ← 6배 향상
멀티 16개:          44ms / 2,272 RPS  ← 거의 동일
멀티 80개:          58ms / 1,724 RPS  ← 오히려 24% 느려짐!
```

### 핵심 교훈

```
I/O bound:
→ 스레드 많을수록 유리 (코어 수와 무관)
→ VT = 싱글스레드 대비 944배

CPU bound:
→ 코어 수에서 최고 성능 (6배 향상)
→ 코어 * 2: 이점 없음
→ 코어 * 10: 오히려 24% 느려짐 (컨텍스트 스위칭 오버헤드)
```

---

## 🛠️ 실습 3 — Race Condition 재현 + Lock으로 해결

### 코드

```java
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class RaceConditionDemo {

    // 1. Race Condition 발생 버전
    static int couponCount = 100;
    static int issuedCount = 0;

    static void issueCouponUnsafe() throws InterruptedException {
        if (couponCount > 0) {
            Thread.sleep(1); // 강제로 컨텍스트 스위칭 유발
            couponCount--;
            issuedCount++;
        }
    }

    // 2. synchronized 해결
    static int couponCountSync = 100;
    static int issuedCountSync = 0;

    static synchronized void issueCouponSync() {
        if (couponCountSync > 0) {
            couponCountSync--;
            issuedCountSync++;
        }
    }

    // 3. ReentrantLock 해결
    static int couponCountLock = 100;
    static int issuedCountLock = 0;
    static ReentrantLock lock = new ReentrantLock();

    static void issueCouponLock() {
        lock.lock();
        try {
            if (couponCountLock > 0) {
                couponCountLock--;
                issuedCountLock++;
            }
        } finally {
            lock.unlock(); // 반드시 finally로 보장!
        }
    }

    // 4. AtomicInteger 해결 (CAS)
    static AtomicInteger couponCountAtomic = new AtomicInteger(100);
    static AtomicInteger issuedCountAtomic = new AtomicInteger(0);

    static void issueCouponAtomic() {
        int current;
        do {
            current = couponCountAtomic.get();
            if (current <= 0) return;
        } while (!couponCountAtomic.compareAndSet(current, current - 1));
        issuedCountAtomic.incrementAndGet();
    }

    static long runBenchmark(String name, int threadCount,
                              Runnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        long start = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try { task.run(); } finally { latch.countDown(); }
            });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();
        return elapsed;
    }

    public static void main(String[] args) throws Exception {
        int threadCount = 1000;

        // Race Condition 재현
        System.out.println("=== Race Condition 재현 ===");
        System.out.println("초기 쿠폰 수: " + couponCount);

        ExecutorService raceExecutor = Executors.newFixedThreadPool(1000);
        CountDownLatch raceLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            raceExecutor.submit(() -> {
                try { issueCouponUnsafe(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { raceLatch.countDown(); }
            });
        }
        raceLatch.await();
        raceExecutor.shutdown();

        System.out.println("남은 쿠폰 수: " + couponCount);
        System.out.println("발급된 쿠폰 수: " + issuedCount);
        System.out.println("초과 발급: " + Math.max(0, issuedCount - 100) + "개");

        // synchronized
        System.out.println("\n=== synchronized 해결 ===");
        long syncTime = runBenchmark("synchronized", threadCount, () -> issueCouponSync());
        System.out.println("발급된 쿠폰 수: " + issuedCountSync + " / 소요: " + syncTime + "ms");

        // ReentrantLock
        System.out.println("\n=== ReentrantLock 해결 ===");
        long lockTime = runBenchmark("ReentrantLock", threadCount, () -> issueCouponLock());
        System.out.println("발급된 쿠폰 수: " + issuedCountLock + " / 소요: " + lockTime + "ms");

        // AtomicInteger
        System.out.println("\n=== AtomicInteger 해결 ===");
        long atomicTime = runBenchmark("AtomicInteger", threadCount, () -> issueCouponAtomic());
        System.out.println("발급된 쿠폰 수: " + issuedCountAtomic.get() + " / 소요: " + atomicTime + "ms");

        System.out.println("\n=== 성능 비교 ===");
        System.out.printf("synchronized:  %dms%n", syncTime);
        System.out.printf("ReentrantLock: %dms%n", lockTime);
        System.out.printf("AtomicInteger: %dms%n", atomicTime);
    }
}
```

### 실측 결과

```
=== Race Condition 재현 ===
초기 쿠폰 수: 100
남은 쿠폰 수: -9     ← 음수!
발급된 쿠폰 수: 111  ← 11개 초과 발급!
초과 발급: 11개

=== 성능 비교 ===
synchronized:  166ms
ReentrantLock: 119ms  ← 가장 빠름 (경쟁 심할 때)
AtomicInteger: 192ms  ← 가장 느림 (재시도 폭발)
```

### 핵심 교훈

```
Race Condition 발생 원인:
if 체크(lock 밖) → sleep(1ms) → couponCount--
→ 1,000개 스레드 전부 if 통과 후 동시에 감소
→ 쿠폰 11개 초과 발급!

해결책 선택 기준:
경쟁 적을 때 (스레드 10개 이하):
  AtomicInteger > ReentrantLock > synchronized

경쟁 많을 때 (스레드 1,000개):
  ReentrantLock > synchronized > AtomicInteger
  → CAS 재시도 폭발로 AtomicInteger 역전!

실무 (멀티 서버 환경):
  → Java Lock 무의미
  → Redis 분산락 필요 (2단계에서 구현 예정)
```

---

## 📊 1단계 실습 전체 요약

```
실습 1 - 스레드 풀 RPS:
최대 RPS = 스레드 수 × (1000ms ÷ 처리시간)
VT는 I/O bound에서 8.7배 압도적

실습 2 - 커넥션 풀 병목:
커넥션 풀이 병목 → VT ≈ 플랫폼 스레드
외부 API 500ms → VT 53% 빠름
병목 전환점 = 총 처리시간 312ms 초과 시

실습 3 - 싱글 vs 멀티:
I/O bound 싱글 vs VT = 944배 차이
CPU bound 코어 수 초과 스레드 = 역효과

실습 4 - Race Condition:
lock 범위 밖에서 if 체크 = Race Condition
경쟁 심할 때 ReentrantLock이 AtomicInteger보다 빠름
멀티 서버 = Redis 분산락 필요
```

---

## 🔗 다음 단계

> **2단계: Redis 심화**
> - Single-thread 이벤트 루프
> - Master-Replica 복제
> - Sentinel vs Cluster
> - Lua Script 원자성
> - 실습: Docker Redis + 선착순 쿠폰 + 1만 TPS 부하 테스트