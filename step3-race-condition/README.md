# Step 3 — Race Condition 재현

`GET + DECR` 두 명령어 사이에 다른 스레드가 끼어들어 쿠폰이 초과 발급되는 **Race Condition**을 직접 재현합니다.

해결 방법은 [Step 4 — Lua Script](../step4-lua-script/README.md)에서 다룹니다.

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
cd step3-race-condition/
mvn compile exec:java -Dexec.mainClass="com.redislab.RaceCondition"
```

---

## 실습 목록

| # | 실습명 | 핵심 내용 |
|---|--------|-----------|
| 1 | Race Condition 재현 | 10,000 스레드 동시 요청 → 쿠폰 초과 발급 확인 |
| 2 | 발생 시점 시각화 | 스레드 수를 줄여 충돌 순간을 출력으로 확인 |

---

## 예상 출력

```
============================================================
실습 1: Race Condition 재현 (GET + DECR)
============================================================
초기 쿠폰 수: 1,000개
동시 요청 수: 10,000개

결과:
  소요 시간:           1,243ms
  남은 쿠폰:           -312개
  발급 성공 (카운터):  1,312건
  실제 발급 (Set):     1,312건
  초과 발급:           312건  <- Race Condition 발생!
  TPS:                 8,046.7

============================================================
실습 2: Race Condition 발생 시점 시각화
============================================================
초기 쿠폰 수: 5개 / 동시 요청: 20개

스레드    GET 시점값   결과    비고
--------------------------------------------------
Thread-0   5          발급
Thread-1   5          발급
Thread-2   5          발급   <- 이미 0인데 발급!
Thread-3   4          발급
Thread-4   3          발급
Thread-5   2          거절
...

남은 쿠폰: -2개 / 실제 발급: 7건 / 초과: 2건
```

---

## 핵심 개념

### Race Condition 발생 구조

```
서버 A: GET coupon:count → "1"  (재고 있음 확인)
서버 B: GET coupon:count → "1"  (재고 있음 확인) ← A가 DECR하기 전!
서버 A: DECR coupon:count → 0
서버 B: DECR coupon:count → -1  ← 이미 0인데 차감! 초과 발급!
```

`GET`으로 재고를 확인한 순간과 `DECR`로 차감하는 순간 사이에 **다른 스레드가 끼어들 수 있습니다.**

### Lettuce가 스레드 안전해도 Race Condition이 발생하는 이유

```
Lettuce 스레드 안전 = 연결 하나를 여러 스레드가 공유해도 안전
                     (명령어가 섞이지 않음)

BUT: 명령어 단위의 원자성만 보장
     GET과 DECR 사이 구간은 보호하지 않음
     → 두 명령어 사이에 다른 스레드 끼어들기 가능!
```

---

## 다음 단계

[Step 4 — Lua Script로 해결](../step4-lua-script/README.md)