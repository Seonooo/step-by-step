# Step 1 — Redis Master-Replica-Sentinel Docker 환경 구성

Redis Master 1대 + Replica 2대 + Sentinel 3대를 Docker Compose로 구성하는 실습입니다.

---

## 디렉토리 구조

```
docker/
├── docker-compose.yml   # 서비스 정의 (Master, Replica ×2, Sentinel ×3)
├── sentinel1.conf       # Sentinel 1 전용 설정 파일
├── sentinel2.conf       # Sentinel 2 전용 설정 파일
├── sentinel3.conf       # Sentinel 3 전용 설정 파일
└── README.md            # 본 파일
```

> **왜 Sentinel 설정 파일을 분리하는가?**
> Sentinel은 기동 시 발견한 Replica·다른 Sentinel 정보를 설정 파일에 직접 재작성합니다.
> 3개 컨테이너가 동일한 파일을 bind mount로 공유하면 동시 쓰기로 파일이 손상됩니다.
> 각 Sentinel이 자신만의 파일을 갖도록 분리해야 안정적으로 동작합니다.

---

## 환경 구성 요약

| 컨테이너          | 역할       | 호스트 포트 |
|-------------------|------------|-------------|
| redis-master      | Master     | 6379        |
| redis-replica-1   | Replica 1  | 6380        |
| redis-replica-2   | Replica 2  | 6381        |
| redis-sentinel-1  | Sentinel 1 | 26379       |
| redis-sentinel-2  | Sentinel 2 | 26380       |
| redis-sentinel-3  | Sentinel 3 | 26381       |

- 네트워크: `redis-network` (bridge)
- AOF 지속성: Master + Replica 모두 활성화 (`appendonly yes`)
- Sentinel quorum: 2 (3개 중 2개 동의 시 장애 선언)

---

## 실행 명령어

### 전체 컨테이너 시작 (백그라운드)

```bash
docker compose up -d
```

### 컨테이너 상태 확인

```bash
docker compose ps
```

예상 출력:
```
NAME               IMAGE       STATUS    PORTS
redis-master       redis:7.0   Up        0.0.0.0:6379->6379/tcp
redis-replica-1    redis:7.0   Up        0.0.0.0:6380->6379/tcp
redis-replica-2    redis:7.0   Up        0.0.0.0:6381->6379/tcp
redis-sentinel-1   redis:7.0   Up        0.0.0.0:26379->26379/tcp
redis-sentinel-2   redis:7.0   Up        0.0.0.0:26380->26379/tcp
redis-sentinel-3   redis:7.0   Up        0.0.0.0:26381->26379/tcp
```

---

## 복제 상태 확인 명령어

### Master에서 복제 정보 확인

```bash
docker exec redis-master redis-cli INFO replication
```

예상 출력:
```
# Replication
role:master
connected_slaves:2
slave0:ip=172.20.0.3,port=6379,state=online,offset=1234,lag=0
slave1:ip=172.20.0.4,port=6379,state=online,offset=1234,lag=0
master_failover_state:no-failover
master_replid:a1b2c3d4e5f6...
master_repl_offset:1234
repl_backlog_active:1
repl_backlog_size:1048576
```

### Replica 1에서 복제 정보 확인

```bash
docker exec redis-replica-1 redis-cli INFO replication
```

예상 출력:
```
# Replication
role:slave
master_host:redis-master
master_port:6379
master_link_status:up
master_last_io_seconds_ago:1
master_sync_in_progress:0
slave_read_replication_offset:1234
slave_priority:100
slave_read_only:1
connected_slaves:0
```

### Replica 2에서 복제 정보 확인

```bash
docker exec redis-replica-2 redis-cli INFO replication
```

---

## Sentinel 상태 확인 명령어

### Sentinel이 인식한 Master 정보 확인

```bash
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL masters
```

예상 출력:
```
 1) "name"
 2) "mymaster"
 3) "ip"
 4) "172.20.0.2"
 5) "port"
 6) "6379"
 7) "flags"
 8) "master"
 9) "num-slaves"
10) "2"
11) "num-other-sentinels"
12) "2"
13) "quorum"
14) "2"
```

### Sentinel이 인식한 Replica 목록 확인

```bash
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL slaves mymaster
```

### Sentinel이 인식한 다른 Sentinel 목록 확인

```bash
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL sentinels mymaster
```

### 현재 Master 주소 확인 (자동 장애조치 후 변경 여부 확인용)

```bash
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster
```

예상 출력:
```
1) "172.20.0.2"
2) "6379"
```

---

## 복제 동작 직접 확인

### Master에 키 쓰기

```bash
docker exec redis-master redis-cli SET test:key "hello-from-master"
```

### Replica 1에서 읽기 (복제 확인)

```bash
docker exec redis-replica-1 redis-cli GET test:key
```

예상 출력: `"hello-from-master"`

### Replica 2에서 읽기

```bash
docker exec redis-replica-2 redis-cli GET test:key
```

예상 출력: `"hello-from-master"`

---

## 컨테이너 종료 명령어

### 컨테이너만 종료 (볼륨 유지)

```bash
docker compose down
```

### 컨테이너 + 볼륨 모두 삭제 (완전 초기화)

```bash
docker compose down -v
```

### 특정 컨테이너만 재시작

```bash
docker compose restart redis-master
```

---

## 로그 확인

### 전체 로그 실시간 확인

```bash
docker compose logs -f
```

### 특정 컨테이너 로그 확인

```bash
docker compose logs -f redis-master
docker compose logs -f redis-sentinel-1
```

---

## Failover 시뮬레이션 (선택 실습)

Master 컨테이너를 강제로 내려서 Sentinel 자동 장애조치를 확인합니다.

```bash
# 1. Master 중단
docker compose stop redis-master

# 2. Sentinel 로그에서 failover 확인 (약 5~10초 소요)
docker compose logs -f redis-sentinel-1

# 3. 새 Master 확인 (replica-1 또는 replica-2가 승격됨)
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster

# 4. Master 복구 (이전 Master는 이제 Replica로 재참여)
docker compose start redis-master
docker exec redis-master redis-cli INFO replication
```

Failover 후 Sentinel 로그 예시:
```
+sdown master mymaster 172.20.0.2 6379
+odown master mymaster 172.20.0.2 6379 #quorum 2/2
+elected-leader master mymaster 172.20.0.2 6379
+failover-state-select-slave master mymaster 172.20.0.2 6379
+selected-slave slave 172.20.0.3:6379 172.20.0.3 6379 @ mymaster 172.20.0.2 6379
+promoted-slave slave 172.20.0.3:6379 172.20.0.3 6379 @ mymaster 172.20.0.2 6379
+failover-end master mymaster 172.20.0.2 6379
+switch-master mymaster 172.20.0.2 6379 172.20.0.3 6379
```

---

## 다음 단계

- **Step 2**: [복제 지연(Replication Lag) 확인](../docs/step2/Step2_cs.md)
