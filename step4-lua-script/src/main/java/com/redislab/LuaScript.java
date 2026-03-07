package com.redislab;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Step 4 — Lua Script로 Race Condition 해결
 *
 * 목표:
 *   Step 3에서 재현한 Race Condition을 Lua Script로 해결합니다.
 *   GET + DECR + SADD 세 명령어를 하나의 스크립트로 묶어
 *   Redis 이벤트 루프가 원자적으로 처리하게 합니다.
 *
 * 사전 조건:
 *   step1-setup/ 에서 docker compose up -d
 */
public class LuaScript {

    private static final String REDIS_HOST = "localhost";
    private static final int    REDIS_PORT = 6379;

    // =============================================
    // Lua Script: 원자적 쿠폰 발급
    //   KEYS[1] = 쿠폰 재고 키
    //   KEYS[2] = 발급된 유저 Set 키
    //   ARGV[1] = 유저 ID
    //   반환: 1(발급 성공), 0(재고 없음)
    // =============================================
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
    // 실습 1: Lua Script 원자적 발급
    //   - 10,000 스레드 동시 요청
    //   - 초과 발급 0건 확인
    // =============================================
    static void lab1_luaScript(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 1: Lua Script 원자적 발급");

        int couponCount = 1000;
        int threadCount = 10000;

        commands.set("coupon:safe:count", String.valueOf(couponCount));
        commands.del("coupon:safe:users");

        System.out.printf("초기 쿠폰 수: %,d개%n", couponCount);
        System.out.printf("동시 요청 수: %,d개%n", threadCount);
        System.out.println();

        AtomicInteger issued = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);

        long start = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String userId = "user:" + i;
            executor.submit(() -> {
                try {
                    // ✅ 원자적: Redis가 스크립트 전체를 단일 명령어로 처리
                    Long result = commands.eval(
                            COUPON_SCRIPT,
                            ScriptOutputType.INTEGER,
                            new String[]{"coupon:safe:count", "coupon:safe:users"},
                            userId
                    );
                    if (result != null && result == 1L) {
                        issued.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();

        String remaining = commands.get("coupon:safe:count");
        long actualIssued = commands.scard("coupon:safe:users");

        System.out.println("결과:");
        System.out.printf("  소요 시간:           %dms%n", elapsed);
        System.out.printf("  남은 쿠폰:           %s개%n", remaining);
        System.out.printf("  발급 성공 (카운터):  %,d건%n", issued.get());
        System.out.printf("  실제 발급 (Set):     %,d건%n", actualIssued);
        System.out.printf("  초과 발급:           %,d건  %s%n",
                Math.max(0, actualIssued - couponCount),
                actualIssued <= couponCount ? "<- 완벽한 원자성 보장!" : "문제 발생");
        System.out.printf("  TPS:                 %.1f%n", threadCount / (elapsed / 1000.0));
    }

    // =============================================
    // 실습 2: Step 3 (unsafe) vs Step 4 (safe) 직접 비교
    // =============================================
    static void lab2_comparison(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 2: Race Condition vs Lua Script 비교");

        int couponCount = 500;
        int threadCount = 5000;

        // ── Unsafe (GET + DECR) ──────────────────────────
        commands.set("coupon:cmp:unsafe:count", String.valueOf(couponCount));
        commands.del("coupon:cmp:unsafe:users");

        ExecutorService ex1 = Executors.newFixedThreadPool(200);
        CountDownLatch l1 = new CountDownLatch(threadCount);
        long s1 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String uid = "user:" + i;
            ex1.submit(() -> {
                try {
                    String c = commands.get("coupon:cmp:unsafe:count");
                    if (c != null && Integer.parseInt(c) > 0) {
                        commands.decr("coupon:cmp:unsafe:count");
                        commands.sadd("coupon:cmp:unsafe:users", uid);
                    }
                } finally { l1.countDown(); }
            });
        }
        l1.await();
        long e1 = System.currentTimeMillis() - s1;
        ex1.shutdown();
        long unsafeIssued = commands.scard("coupon:cmp:unsafe:users");

        // ── Safe (Lua Script) ────────────────────────────
        commands.set("coupon:cmp:safe:count", String.valueOf(couponCount));
        commands.del("coupon:cmp:safe:users");

        ExecutorService ex2 = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch l2 = new CountDownLatch(threadCount);
        long s2 = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String uid = "user:" + i;
            ex2.submit(() -> {
                try {
                    commands.eval(COUPON_SCRIPT, ScriptOutputType.INTEGER,
                            new String[]{"coupon:cmp:safe:count", "coupon:cmp:safe:users"}, uid);
                } finally { l2.countDown(); }
            });
        }
        l2.await();
        long e2 = System.currentTimeMillis() - s2;
        ex2.shutdown();
        long safeIssued = commands.scard("coupon:cmp:safe:users");

        System.out.printf("%-22s  %-10s  %-10s  %s%n", "방식", "실제 발급", "초과 발급", "소요 시간");
        System.out.println("-".repeat(58));
        System.out.printf("%-22s  %-10d  %-10d  %dms%n",
                "GET+DECR (Step3)", unsafeIssued, Math.max(0, unsafeIssued - couponCount), e1);
        System.out.printf("%-22s  %-10d  %-10d  %dms%n",
                "Lua Script (Step4)", safeIssued, Math.max(0, safeIssued - couponCount), e2);

        System.out.println();
        System.out.println("→ Lua Script는 초과 발급 0건을 보장합니다.");
    }

    // =============================================
    // 실습 3: EVALSHA로 스크립트 캐싱 (성능 최적화)
    //   - EVAL: 매번 스크립트 전체를 전송
    //   - EVALSHA: SHA1 해시만 전송 → 네트워크 절약
    // =============================================
    static void lab3_evalsha(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 3: EVALSHA로 스크립트 캐싱");

        // 스크립트를 Redis 서버에 미리 로드
        String sha = commands.scriptLoad(COUPON_SCRIPT);
        System.out.println("스크립트 SHA1: " + sha);
        System.out.println("→ EVAL 대신 EVALSHA + SHA1만 전송 → 네트워크 절약");
        System.out.println();

        int couponCount = 100;
        int threadCount = 1000;

        commands.set("coupon:sha:count", String.valueOf(couponCount));
        commands.del("coupon:sha:users");

        AtomicInteger issued = new AtomicInteger(0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);

        long start = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String userId = "user:" + i;
            executor.submit(() -> {
                try {
                    Long result = commands.evalsha(
                            sha,
                            ScriptOutputType.INTEGER,
                            new String[]{"coupon:sha:count", "coupon:sha:users"},
                            userId
                    );
                    if (result != null && result == 1L) issued.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();

        System.out.printf("EVALSHA 결과: %,d건 발급 / %dms / TPS: %.1f%n",
                issued.get(), elapsed, threadCount / (elapsed / 1000.0));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Step 4 — Lua Script 원자성 실습");
        System.out.println("=".repeat(60));
        System.out.println("Redis  : " + REDIS_HOST + ":" + REDIS_PORT);
        System.out.println("Docker : step1-setup/ 에서 docker compose up -d");
        System.out.println("=".repeat(60));

        RedisClient client = RedisClient.create(RedisURI.create(REDIS_HOST, REDIS_PORT));

        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> commands = conn.sync();

            lab1_luaScript(commands);
            lab2_comparison(commands);
            lab3_evalsha(commands);

            printHeader("실습 완료");
            System.out.println("핵심:");
            System.out.println("  1. Lua Script = 여러 명령어를 Redis가 단일 명령어로 처리");
            System.out.println("  2. 스크립트 실행 중 다른 클라이언트 끼어들기 불가");
            System.out.println("  3. EVALSHA로 스크립트 캐싱 → 네트워크 트래픽 절약");
            System.out.println("  4. MULTI/EXEC는 조건 분기 불가 → 선착순에 부적합");

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