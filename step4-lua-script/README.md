# Step 4 — Lua Script 원자적 해결

Step 3에서 재현한 Race Condition을 **Lua Script**로 해결합니다.
`GET + DECR + SADD` 세 명령어를 하나의 스크립트로 묶어 Redis가 원자적으로 처리하게 합니다.

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
cd step4-lua-script/
mvn compile exec:java -Dexec.mainClass="com.redislab.LuaScript"
```

---

## 실습 목록

| # | 실습명 | 핵심 내용 |
|---|--------|-----------|
| 1 | Lua Script 원자적 발급 | 10,000 동시 요청 → 초과 발급 0건 확인 |
| 2 | Step 3 vs Step 4 직접 비교 | unsafe / safe 실제 발급 건수 나란히 비교 |
| 3 | EVALSHA 스크립트 캐싱 | SHA1 해시로 네트워크 트래픽 절약 |

---

## 예상 출력

```
============================================================
실습 1: Lua Script 원자적 발급
============================================================
초기 쿠폰 수: 1,000개
동시 요청 수: 10,000개

결과:
  소요 시간:           876ms
  남은 쿠폰:           0개
  발급 성공 (카운터):  1,000건
  실제 발급 (Set):     1,000건
  초과 발급:           0건   <- 완벽한 원자성 보장!
  TPS:                 11,415.5

============================================================
실습 2: Race Condition vs Lua Script 비교
============================================================
방식                    실제 발급   초과 발급   소요 시간
----------------------------------------------------------
GET+DECR (Step3)        634        134        743ms
Lua Script (Step4)      500        0          612ms

============================================================
실습 3: EVALSHA로 스크립트 캐싱
============================================================
스크립트 SHA1: 7a8f3c2e...
→ EVAL 대신 EVALSHA + SHA1만 전송 → 네트워크 절약

EVALSHA 결과: 100건 발급 / 54ms / TPS: 1,851.9
```

---

## 핵심 개념

### Lua Script 구조

```lua
-- KEYS[1]: 쿠폰 재고 키
-- KEYS[2]: 발급 유저 Set 키
-- ARGV[1]: 유저 ID

local count = redis.call('GET', KEYS[1])
if count == false then
    return 0
end
if tonumber(count) > 0 then
    redis.call('DECR', KEYS[1])
    redis.call('SADD', KEYS[2], ARGV[1])
    return 1   -- 발급 성공
else
    return 0   -- 재고 없음
end
```

### 원자성 보장 원리

```
Redis 이벤트 루프 (Single-thread):

[클라이언트 A의 Lua Script 전체 실행]  ← 하나의 단위로 처리
    GET  coupon:count → 1
    DECR coupon:count → 0
    SADD coupon:users userA
        ↓
[클라이언트 B 실행]  ← A 완료 후에만 시작
    GET  coupon:count → 0
    return 0  (재고 없음)
```

Lua Script는 Redis 내부에서 **단일 명령어처럼** 처리됩니다. 실행 도중 다른 클라이언트 명령어가 끼어들 수 없습니다.

### EVAL vs EVALSHA

| | EVAL | EVALSHA |
|---|---|---|
| 전송 내용 | 스크립트 전체 | SHA1 해시(40자) |
| 네트워크 | 스크립트 크기만큼 | 고정 40바이트 |
| 사용 시점 | 최초 1회 또는 테스트 | 운영 환경 (스크립트 미리 로드 후) |

```java
// 스크립트 서버에 미리 로드
String sha = commands.scriptLoad(COUPON_SCRIPT);

// 이후 SHA1만 전송
commands.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
```

### MULTI/EXEC vs Lua Script

| | Lua Script | MULTI/EXEC |
|---|---|---|
| 원자성 | 완전 보장 | 실행 순서만 보장 |
| 조건 분기 | 가능 | 불가 |
| 중간 끼어들기 | 불가 | 가능 |
| 선착순 쿠폰 | 적합 | 부적합 |

---

## 다음 단계

[Step 5 — 1만 TPS 부하 테스트](../step5-load-test/README.md)