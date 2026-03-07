package com.redislab;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Step 5 — 1만 TPS 부하 테스트
 *
 * 목표:
 *   1. 동시 요청 수를 단계적으로 늘려가며 TPS 측정
 *   2. Virtual Thread vs 플랫폼 스레드 성능 비교
 *   3. 쿠폰 소진 시나리오에서 초과 발급 0건 검증
 *
 * 사전 조건:
 *   step1-setup/ 에서 docker compose up -d
 */
public class LoadTest {

    private static final String REDIS_HOST = "localhost";
    private static final int    REDIS_PORT = 6379;

    static final String COUPON_SCRIPT = """
            local count = redis.call('GET', KEYS[1])
            if count == false then
                return 0
            end
            if tonumber(count) > 0 then
                redis.call('DECR', KEYS[1])
                redis.call('SADD', KEYS[2], ARGV[1])
                return 1
            else
                return 0
            end
            """;

    // =============================================
    // 실습 1: 단계별 TPS 측정 (Virtual Thread)
    //   100 → 500 → 1,000 → 5,000 → 10,000 동시 요청
    // =============================================
    static void lab1_stepwiseTps(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 1: 단계별 TPS 측정 (Virtual Thread)");

        int[] threadCounts = {100, 500, 1000, 5000, 10000};
        int couponCount = 100_000;

        System.out.printf("%-12s  %-10s  %-14s  %s%n", "동시 요청 수", "소요 시간", "TPS", "발급 건수");
        System.out.println("-".repeat(55));

        for (int threadCount : threadCounts) {
            commands.set("coupon:load:count", String.valueOf(couponCount));
            commands.del("coupon:load:users");

            AtomicInteger issued = new AtomicInteger(0);
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch latch = new CountDownLatch(threadCount);

            long start = System.currentTimeMillis();

            for (int i = 0; i < threadCount; i++) {
                final String userId = "user:" + i;
                executor.submit(() -> {
                    try {
                        Long result = commands.eval(
                                COUPON_SCRIPT, ScriptOutputType.INTEGER,
                                new String[]{"coupon:load:count", "coupon:load:users"}, userId);
                        if (result != null && result == 1L) issued.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            long elapsed = System.currentTimeMillis() - start;
            executor.shutdown();

            System.out.printf("%-12d  %-10dms  %-14.1f  %,d건%n",
                    threadCount, elapsed, threadCount / (elapsed / 1000.0), issued.get());
        }
    }

    // =============================================
    // 실습 2: Virtual Thread vs 플랫폼 스레드 비교
    // =============================================
    static void lab2_virtualVsPlatform(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 2: Virtual Thread vs 플랫폼 스레드 비교");

        int threadCount = 5000;
        int couponCount = 100_000;

        System.out.printf("동시 요청 수: %,d개%n%n", threadCount);
        System.out.printf("%-28s  %-10s  %s%n", "방식", "소요 시간", "TPS");
        System.out.println("-".repeat(52));

        // ── Virtual Thread ────────────────────────────────
        commands.set("coupon:vt:count", String.valueOf(couponCount));
        commands.del("coupon:vt:users");

        CountDownLatch l1 = new CountDownLatch(threadCount);
        ExecutorService vt = Executors.newVirtualThreadPerTaskExecutor();
        long s1 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String uid = "user:" + i;
            vt.submit(() -> {
                try {
                    commands.eval(COUPON_SCRIPT, ScriptOutputType.INTEGER,
                            new String[]{"coupon:vt:count", "coupon:vt:users"}, uid);
                } finally { l1.countDown(); }
            });
        }
        l1.await();
        long e1 = System.currentTimeMillis() - s1;
        vt.shutdown();

        System.out.printf("%-28s  %-10dms  %.1f%n",
                "Virtual Thread", e1, threadCount / (e1 / 1000.0));

        // ── 플랫폼 스레드 (200 pool) ─────────────────────
        commands.set("coupon:pt:count", String.valueOf(couponCount));
        commands.del("coupon:pt:users");

        CountDownLatch l2 = new CountDownLatch(threadCount);
        ExecutorService pt = Executors.newFixedThreadPool(200);
        long s2 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String uid = "user:" + i;
            pt.submit(() -> {
                try {
                    commands.eval(COUPON_SCRIPT, ScriptOutputType.INTEGER,
                            new String[]{"coupon:pt:count", "coupon:pt:users"}, uid);
                } finally { l2.countDown(); }
            });
        }
        l2.await();
        long e2 = System.currentTimeMillis() - s2;
        pt.shutdown();

        System.out.printf("%-28s  %-10dms  %.1f%n",
                "플랫폼 스레드 (200 pool)", e2, threadCount / (e2 / 1000.0));

        System.out.println();
        System.out.println("→ Virtual Thread는 Redis 응답 대기 중 OS 스레드를 반환해");
        System.out.println("  더 적은 자원으로 더 많은 동시 요청을 처리합니다.");
    }

    // =============================================
    // 실습 3: 쿠폰 소진 시나리오
    //   쿠폰 1,000개 / 요청 10,000개 → 정확히 1,000건만 발급
    // =============================================
    static void lab3_couponExhaustion(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 3: 쿠폰 소진 시나리오 (고부하)");

        int couponCount = 1_000;
        int threadCount = 10_000;

        commands.set("coupon:exhaust:count", String.valueOf(couponCount));
        commands.del("coupon:exhaust:users");

        System.out.printf("초기 쿠폰 수: %,d개%n", couponCount);
        System.out.printf("동시 요청 수: %,d개%n", threadCount);
        System.out.println();

        AtomicInteger issued   = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);

        long start = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String userId = "user:" + i;
            executor.submit(() -> {
                try {
                    Long result = commands.eval(
                            COUPON_SCRIPT, ScriptOutputType.INTEGER,
                            new String[]{"coupon:exhaust:count", "coupon:exhaust:users"}, userId);
                    if (result != null && result == 1L) issued.incrementAndGet();
                    else rejected.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();

        String remaining = commands.get("coupon:exhaust:count");
        long actualIssued = commands.scard("coupon:exhaust:users");

        System.out.println("결과:");
        System.out.printf("  소요 시간:             %dms%n", elapsed);
        System.out.printf("  남은 쿠폰:             %s개%n", remaining);
        System.out.printf("  발급 성공:             %,d건%n", issued.get());
        System.out.printf("  발급 거절 (재고 없음): %,d건%n", rejected.get());
        System.out.printf("  실제 발급 (Set):       %,d건%n", actualIssued);
        System.out.printf("  초과 발급:             %,d건  %s%n",
                Math.max(0, actualIssued - couponCount),
                actualIssued <= couponCount ? "<- 정확히 제한됨!" : "문제 발생");
        System.out.printf("  TPS:                   %.1f%n", threadCount / (elapsed / 1000.0));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Step 5 — 1만 TPS 부하 테스트");
        System.out.println("=".repeat(60));
        System.out.println("Redis  : " + REDIS_HOST + ":" + REDIS_PORT);
        System.out.println("Docker : step1-setup/ 에서 docker compose up -d");
        System.out.println("=".repeat(60));

        RedisClient client = RedisClient.create(RedisURI.create(REDIS_HOST, REDIS_PORT));

        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> commands = conn.sync();

            lab1_stepwiseTps(commands);
            lab2_virtualVsPlatform(commands);
            lab3_couponExhaustion(commands);

            printHeader("실습 완료");
            System.out.println("핵심:");
            System.out.println("  1. Redis Single-thread지만 ~10만 ops/sec 처리 가능");
            System.out.println("  2. 병목은 Redis가 아닌 클라이언트 스레드 수");
            System.out.println("  3. Virtual Thread로 I/O 대기 시간 효율화");
            System.out.println("  4. 고부하에서도 Lua Script로 초과 발급 0건 보장");

        } finally {
            client.shutdown();
        }
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println(title);
        System.out.println("=".repeat(60));
    }
}