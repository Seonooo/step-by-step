package com.redislab;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Step 3 — Race Condition 재현
 *
 * 목표:
 *   GET + DECR 두 명령어 사이에 다른 스레드가 끼어들어
 *   쿠폰이 초과 발급되는 Race Condition을 직접 재현합니다.
 *
 *   해결은 Step 4 (LuaScript.java) 에서 다룹니다.
 *
 * 사전 조건:
 *   step1-setup/ 에서 docker compose up -d
 */
public class RaceCondition {

    private static final String REDIS_HOST = "localhost";
    private static final int    REDIS_PORT = 6379;

    // =============================================
    // 실습 1: Race Condition 재현
    //   - 10,000 스레드가 동시에 GET → DECR 수행
    //   - GET과 DECR 사이에 다른 스레드가 끼어들어 초과 발급 발생
    // =============================================
    static void lab1_raceCondition(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 1: Race Condition 재현 (GET + DECR)");

        int couponCount = 1000;
        int threadCount = 10000;

        commands.set("coupon:unsafe:count", String.valueOf(couponCount));
        commands.del("coupon:unsafe:users");

        System.out.printf("초기 쿠폰 수: %,d개%n", couponCount);
        System.out.printf("동시 요청 수: %,d개%n", threadCount);
        System.out.println();

        AtomicInteger issued = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(200);
        CountDownLatch latch = new CountDownLatch(threadCount);

        long start = System.currentTimeMillis();

        for (int i = 0; i < threadCount; i++) {
            final String userId = "user:" + i;
            executor.submit(() -> {
                try {
                    // ❌ 비원자적: GET과 DECR 사이 구간이 임계 구역
                    String count = commands.get("coupon:unsafe:count");
                    if (count != null && Integer.parseInt(count) > 0) {
                        // ← 여기서 다른 스레드가 DECR을 먼저 실행할 수 있음
                        commands.decr("coupon:unsafe:count");
                        commands.sadd("coupon:unsafe:users", userId);
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

        String remaining = commands.get("coupon:unsafe:count");
        long actualIssued = commands.scard("coupon:unsafe:users");
        long overIssued = Math.max(0, actualIssued - couponCount);

        System.out.println("결과:");
        System.out.printf("  소요 시간:           %dms%n", elapsed);
        System.out.printf("  남은 쿠폰:           %s개%n", remaining);
        System.out.printf("  발급 성공 (카운터):  %,d건%n", issued.get());
        System.out.printf("  실제 발급 (Set):     %,d건%n", actualIssued);
        System.out.printf("  초과 발급:           %,d건  %s%n",
                overIssued, overIssued > 0 ? "<- Race Condition 발생!" : "");
        System.out.printf("  TPS:                 %.1f%n", threadCount / (elapsed / 1000.0));
    }

    // =============================================
    // 실습 2: Race Condition이 발생하는 정확한 시점 시각화
    //   - 스레드 수를 줄여 충돌 순간을 더 명확하게 보여줌
    // =============================================
    static void lab2_visualize(RedisCommands<String, String> commands)
            throws InterruptedException {

        printHeader("실습 2: Race Condition 발생 시점 시각화");

        int couponCount = 5;
        int threadCount = 20;

        commands.set("coupon:visual:count", String.valueOf(couponCount));
        commands.del("coupon:visual:users");

        System.out.printf("초기 쿠폰 수: %d개 / 동시 요청: %d개%n%n", couponCount, threadCount);
        System.out.printf("%-8s  %-10s  %-6s  %s%n", "스레드", "GET 시점값", "결과", "비고");
        System.out.println("-".repeat(50));

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await(); // 모든 스레드가 동시에 출발

                    String count = commands.get("coupon:visual:count");
                    boolean hasStock = count != null && Integer.parseInt(count) > 0;

                    if (hasStock) {
                        commands.decr("coupon:visual:count");
                        commands.sadd("coupon:visual:users", "user:" + idx);
                        System.out.printf("Thread-%-3d  GET=%-6s    발급   %s%n",
                                idx, count,
                                Integer.parseInt(count) <= 0 ? "<- 이미 0인데 발급!" : "");
                    } else {
                        System.out.printf("Thread-%-3d  GET=%-6s    거절%n", idx, count);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown(); // 동시 출발
        done.await();
        executor.shutdown();

        long actualIssued = commands.scard("coupon:visual:users");
        String remaining = commands.get("coupon:visual:count");

        System.out.println();
        System.out.printf("남은 쿠폰: %s개 / 실제 발급: %d건 / 초과: %d건%n",
                remaining, actualIssued, Math.max(0, actualIssued - couponCount));
        System.out.println();
        System.out.println("→ 여러 스레드가 GET으로 같은 재고값을 읽은 뒤 각자 DECR하면");
        System.out.println("  재고가 0 이하로 내려가도 발급이 허용됩니다.");
        System.out.println("  해결 방법 → Step 4: Lua Script");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Step 3 — Race Condition 재현");
        System.out.println("=".repeat(60));
        System.out.println("Redis  : " + REDIS_HOST + ":" + REDIS_PORT);
        System.out.println("Docker : step1-setup/ 에서 docker compose up -d");
        System.out.println("=".repeat(60));

        RedisClient client = RedisClient.create(RedisURI.create(REDIS_HOST, REDIS_PORT));

        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> commands = conn.sync();

            lab1_raceCondition(commands);
            lab2_visualize(commands);

            printHeader("실습 완료");
            System.out.println("핵심: GET + DECR 두 명령어는 원자적이지 않습니다.");
            System.out.println("      명령어 사이에 다른 스레드가 끼어들어 초과 발급이 발생합니다.");
            System.out.println("      → 해결: Step 4 LuaScript.java");

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