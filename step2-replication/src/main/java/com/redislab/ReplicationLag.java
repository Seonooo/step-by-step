package com.redislab;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

/**
 * Step 2 — 복제 지연(Replication Lag) 실습
 *
 * 목표:
 *   1. Master 쓰기 직후 Replica에서 읽으면 이전 값이 반환됨을 확인
 *   2. 지연 시간별(즉시/1ms/5ms/10ms) 복제 전파 속도 측정
 *   3. 연속 대량 쓰기 시 누적 지연 확인
 *   4. WAIT 명령으로 동기 복제 보장 방법 확인
 *   5. Read-Your-Own-Writes 문제 재현 및 해결
 *
 * 사전 조건:
 *   step1-setup/ 에서 docker compose up -d
 */
public class ReplicationLag {

    private static final String MASTER_HOST  = "localhost";
    private static final int    MASTER_PORT  = 6379;
    private static final String REPLICA_HOST = "localhost";
    private static final int    REPLICA_PORT = 6380;

    // =============================================
    // 실습 1: 기본 복제 지연 확인
    //   - Master SET 직후 Replica GET 시 이전 값 반환 여부
    //   - 1ms / 5ms / 10ms 간격으로 복제 완료 시점 관찰
    // =============================================
    static void lab1_basicReplicationLag(
            RedisCommands<String, String> master,
            RedisCommands<String, String> replica) throws InterruptedException {

        printHeader("실습 1: 기본 복제 지연 확인");

        String key = "balance:user:1";

        master.del(key);
        Thread.sleep(20);

        master.set(key, "100000");
        System.out.printf("%-25s Master SET  %s = 100000%n", ts(), key);

        String v0 = replica.get(key);
        System.out.printf("%-25s Replica GET (즉시)   → %-10s  %s%n",
                ts(), v0, staleOrFresh(v0, "100000"));

        Thread.sleep(1);
        String v1 = replica.get(key);
        System.out.printf("%-25s Replica GET (+1ms)   → %-10s  %s%n",
                ts(), v1, staleOrFresh(v1, "100000"));

        Thread.sleep(4);
        String v5 = replica.get(key);
        System.out.printf("%-25s Replica GET (+5ms)   → %-10s  %s%n",
                ts(), v5, staleOrFresh(v5, "100000"));

        Thread.sleep(5);
        String v10 = replica.get(key);
        System.out.printf("%-25s Replica GET (+10ms)  → %-10s  %s%n",
                ts(), v10, staleOrFresh(v10, "100000"));

        System.out.println();
        master.set(key, "50000");
        System.out.printf("%-25s Master SET  %s = 50000  (송금 후 잔액 차감)%n", ts(), key);

        String afterTransfer = replica.get(key);
        System.out.printf("%-25s Replica GET (즉시)   → %-10s  %s%n",
                ts(), afterTransfer, staleOrFresh(afterTransfer, "50000"));

        Thread.sleep(10);
        String afterWait = replica.get(key);
        System.out.printf("%-25s Replica GET (+10ms)  → %-10s  %s%n",
                ts(), afterWait, staleOrFresh(afterWait, "50000"));

        System.out.println();
        System.out.println("→ Replica에서 즉시 읽으면 이전 잔액(100000)이 반환될 수 있습니다.");
        System.out.println("  금융 잔액 조회는 반드시 Master에서 읽어야 합니다.");
    }

    // =============================================
    // 실습 2: 연속 대량 쓰기 시 누적 복제 지연
    //   - Master에 100회 연속 쓰기
    //   - 각 쓰기 직후 Replica에서 읽어 지연 확인
    // =============================================
    static void lab2_accumulatedLag(
            RedisCommands<String, String> master,
            RedisCommands<String, String> replica) throws InterruptedException {

        printHeader("실습 2: 연속 대량 쓰기 시 누적 복제 지연");

        String key = "counter:seq";
        int writes = 100;
        int staleCount = 0;

        System.out.printf("Master에 %d회 연속 쓰기 후 Replica 즉시 읽기%n%n", writes);
        System.out.printf("%-6s  %-12s  %-12s  %s%n", "회차", "Master 기록값", "Replica 읽은값", "상태");
        System.out.println("-".repeat(55));

        for (int i = 1; i <= writes; i++) {
            String expected = String.valueOf(i);
            master.set(key, expected);
            String actual = replica.get(key);
            boolean stale = !expected.equals(actual);
            if (stale) staleCount++;
            if (i % 10 == 0 || stale) {
                System.out.printf("%-6d  %-12s  %-12s  %s%n",
                        i, expected, actual, stale ? "← 복제 지연!" : "OK");
            }
        }

        System.out.println();
        System.out.printf("총 %d회 중 Replica 즉시 읽기에서 이전 값 반환: %d회 (%.1f%%)%n",
                writes, staleCount, (staleCount * 100.0 / writes));
        System.out.println("→ 쓰기 직후 즉시 같은 키를 Replica에서 읽으면 이전 값이 반환됩니다.");
    }

    // =============================================
    // 실습 3: WAIT 명령으로 동기 복제 보장
    //   - WAIT numreplicas timeout_ms
    //   - 지정한 수의 Replica가 복제 완료할 때까지 대기
    // =============================================
    static void lab3_waitSyncReplication(
            RedisCommands<String, String> master,
            RedisCommands<String, String> replica) throws InterruptedException {

        printHeader("실습 3: WAIT 명령으로 동기 복제 보장");

        String key = "payment:tx:9999";

        System.out.println("[비동기 복제 - 기본 방식]");
        master.set(key, "PENDING");
        String asyncRead = replica.get(key);
        System.out.printf("  Master SET → Replica 즉시 GET: %-10s  %s%n",
                asyncRead, staleOrFresh(asyncRead, "PENDING"));

        Thread.sleep(20);

        System.out.println();
        System.out.println("[동기 복제 - WAIT 사용]");
        master.set(key, "COMPLETED");
        System.out.printf("  Master SET  %s = COMPLETED%n", key);

        long waitStart = System.currentTimeMillis();
        Long replicasAck = master.wait(1L, 100L);
        long waitElapsed = System.currentTimeMillis() - waitStart;

        System.out.printf("  WAIT 1 100 결과: %d개 Replica 복제 완료 (소요: %dms)%n",
                replicasAck, waitElapsed);

        String syncRead = replica.get(key);
        System.out.printf("  WAIT 완료 후 Replica GET: %-10s  %s%n",
                syncRead, staleOrFresh(syncRead, "COMPLETED"));

        System.out.println();
        System.out.println("→ WAIT 사용 시 복제 완료를 보장할 수 있습니다.");
        System.out.println("  단, Latency가 증가하므로 금융 결제처럼 데이터 손실이");
        System.out.println("  치명적인 경우에만 사용하세요.");
    }

    // =============================================
    // 실습 4: 복제 정보 확인 (INFO replication)
    // =============================================
    static void lab4_replicationInfo(
            RedisCommands<String, String> master,
            RedisCommands<String, String> replica) {

        printHeader("실습 4: 복제 정보 확인 (INFO replication)");

        System.out.println("[Master INFO replication]");
        for (String line : master.info("replication").split("\r?\n")) {
            if (!line.startsWith("#") && !line.isBlank()) System.out.println("  " + line);
        }

        System.out.println();
        System.out.println("[Replica INFO replication]");
        for (String line : replica.info("replication").split("\r?\n")) {
            if (!line.startsWith("#") && !line.isBlank()) System.out.println("  " + line);
        }
    }

    // =============================================
    // 실습 5: Read-Your-Own-Writes 문제 재현
    // =============================================
    static void lab5_readYourOwnWrites(
            RedisCommands<String, String> master,
            RedisCommands<String, String> replica) throws InterruptedException {

        printHeader("실습 5: Read-Your-Own-Writes 문제 재현");

        String key = "user:1001:nickname";

        System.out.println("[시나리오] 사용자가 닉네임 변경 후 즉시 내 정보 페이지 조회");
        System.out.println();

        master.set(key, "NewNickname");
        System.out.printf("  WRITE  → Master: SET %s = NewNickname%n", key);

        String readValue = replica.get(key);
        System.out.printf("  READ   → Replica (즉시): GET %s = %s%n", key,
                readValue == null ? "null" : readValue);

        if (!"NewNickname".equals(readValue)) {
            System.out.println();
            System.out.println("  → 사용자가 닉네임 변경했지만 자신의 화면에선 이전 닉네임이 보임!");
        }

        System.out.println();
        System.out.println("[실무 해결 방법]");
        System.out.println("  1. 쓰기 후 일정 시간 동안 해당 키를 Master에서 읽기");
        System.out.println("  2. Session Sticky: 같은 사용자 요청은 Master로 라우팅");
        System.out.println("  3. WAIT 1 50: 복제 완료 후 읽기 (Latency 증가 감수)");
        System.out.println("  4. 쓰기 후 로컬 캐시(Caffeine)에 임시 저장");

        System.out.println();
        System.out.println("[WAIT 방식 적용]");
        master.set(key, "NewNickname_v2");
        System.out.printf("  WRITE  → Master: SET %s = NewNickname_v2%n", key);
        master.wait(1L, 100L);
        System.out.println("  WAIT 1 100 완료 (복제 보장)");
        String readAfterWait = replica.get(key);
        System.out.printf("  READ   → Replica: GET %s = %s  %s%n",
                key, readAfterWait, staleOrFresh(readAfterWait, "NewNickname_v2"));
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(60));
        System.out.println("Step 2 — 복제 지연(Replication Lag) 실습");
        System.out.println("=".repeat(60));
        System.out.println("Master  : " + MASTER_HOST + ":" + MASTER_PORT);
        System.out.println("Replica : " + REPLICA_HOST + ":" + REPLICA_PORT);
        System.out.println("Docker  : step1-setup/ 에서 docker compose up -d");
        System.out.println("=".repeat(60));

        RedisClient masterClient  = RedisClient.create(RedisURI.create(MASTER_HOST, MASTER_PORT));
        RedisClient replicaClient = RedisClient.create(RedisURI.create(REPLICA_HOST, REPLICA_PORT));

        try (
            StatefulRedisConnection<String, String> masterConn  = masterClient.connect();
            StatefulRedisConnection<String, String> replicaConn = replicaClient.connect()
        ) {
            RedisCommands<String, String> master  = masterConn.sync();
            RedisCommands<String, String> replica = replicaConn.sync();

            lab1_basicReplicationLag(master, replica);
            lab2_accumulatedLag(master, replica);
            lab3_waitSyncReplication(master, replica);
            lab4_replicationInfo(master, replica);
            lab5_readYourOwnWrites(master, replica);

            printHeader("실습 완료");
            System.out.println("핵심: 금융 데이터는 Master 읽기 / 캐시·세션은 Replica 읽기");

        } finally {
            masterClient.shutdown();
            replicaClient.shutdown();
        }
    }

    private static String ts() {
        java.time.LocalTime t = java.time.LocalTime.now();
        return String.format("%02d:%02d:%02d.%03d",
                t.getHour(), t.getMinute(), t.getSecond(), t.getNano() / 1_000_000);
    }

    private static String staleOrFresh(String actual, String expected) {
        if (actual == null) return "← null (복제 미완료)";
        return actual.equals(expected) ? "← 최신 값" : "← 이전 값! (복제 지연)";
    }

    private static void printHeader(String title) {
        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println(title);
        System.out.println("=".repeat(60));
    }
}