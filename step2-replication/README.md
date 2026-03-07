# Step 2 — 복제 지연(Replication Lag) 확인

Redis Master-Replica 구조에서 비동기 복제로 인한 **복제 지연**을 직접 측정하고, 실무 대응 방법을 실습합니다.

---

## 사전 조건

```bash
cd step1-setup/
docker compose up -d
docker compose ps  # 6개 컨테이너 모두 Up 확인
```

---

## 실행 방법

```bash
cd step2-replication/
mvn compile exec:java -Dexec.mainClass="com.redislab.ReplicationLag"
```

---

## 실습 목록

| # | 실습명 | 핵심 내용 |
|---|--------|-----------|
| 1 | 기본 복제 지연 확인 | 쓰기 직후 즉시 / 1ms / 5ms / 10ms 간격 Replica 읽기 |
| 2 | 연속 대량 쓰기 누적 지연 | 100회 연속 쓰기 중 Replica 즉시 읽기 — 이전 값 반환 비율 |
| 3 | WAIT 동기 복제 보장 | `WAIT 1 100` 으로 복제 완료 후 읽기 |
| 4 | INFO replication 확인 | lag, offset, connected_slaves 확인 |
| 5 | Read-Your-Own-Writes 재현 | 닉네임 변경 후 즉시 조회 시 이전 값 반환 문제 |

---

## 예상 출력

```
============================================================
실습 1: 기본 복제 지연 확인
============================================================
12:00:00.001   Master SET  balance:user:1 = 100000
12:00:00.003   Replica GET (즉시)   → null        ← null (복제 미완료)
12:00:00.005   Replica GET (+1ms)   → 100000      ← 최신 값
12:00:00.010   Replica GET (+5ms)   → 100000      ← 최신 값
12:00:00.015   Replica GET (+10ms)  → 100000      ← 최신 값

12:00:00.016   Master SET  balance:user:1 = 50000  (송금 후 잔액 차감)
12:00:00.018   Replica GET (즉시)   → 100000      ← 이전 값! (복제 지연)
12:00:00.028   Replica GET (+10ms)  → 50000       ← 최신 값

============================================================
실습 3: WAIT 명령으로 동기 복제 보장
============================================================
[비동기 복제 - 기본 방식]
  Master SET → Replica 즉시 GET: null        ← null (복제 미완료)

[동기 복제 - WAIT 사용]
  Master SET  payment:tx:9999 = COMPLETED
  WAIT 1 100 결과: 2개 Replica 복제 완료 (소요: 3ms)
  WAIT 완료 후 Replica GET: COMPLETED        ← 최신 값
```

---

## 핵심 개념

### 복제 지연 발생 원인

```
Master (쓰기 완료)
    │
    │  비동기 복제 — 수 ms 지연
    ▼
Replica (아직 이전 값)  ← GET 하면 이전 값 반환!
```

Redis 복제는 기본적으로 **비동기**입니다. Master 쓰기 완료 후 Replica에 전파되기까지 수 ms가 소요됩니다.

### WAIT 명령

```bash
WAIT 1 100   # Replica 1개 이상 복제 완료 대기, 최대 100ms
             # 반환값: 실제 복제 완료된 Replica 수
```

### 실무 읽기 전략

| 데이터 | 읽기 위치 | 이유 |
|--------|-----------|------|
| 잔액, 결제 상태 | **Master** | 정합성 필수 |
| 상품 정보, 배너 | **Replica** | 수 ms 지연 허용 |
| 세션, 캐시 | **Replica** | 부하 분산 |

---

## 다음 단계

[Step 3 — Race Condition 재현](../step3-race-condition/README.md)