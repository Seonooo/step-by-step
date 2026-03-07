#!/bin/bash

echo "================================================"
echo "Redis Lab 실행 스크립트"
echo "================================================"

# 1단계: Docker Redis 시작
echo ""
echo "[1단계] Docker Redis 시작..."
cd docker
docker compose up -d
cd ..

# Redis 준비 대기
echo "Redis 준비 대기 중 (5초)..."
sleep 5

# 복제 상태 확인
echo ""
echo "[확인] Master 복제 상태:"
docker exec redis-master redis-cli INFO replication | grep -E "role|connected_slaves|slave"

# 2단계: Maven 빌드
echo ""
echo "[2단계] Maven 빌드..."
mvn clean package -q

# 3단계: 실습 실행
echo ""
echo "[3단계] 실습 실행..."
java -jar target/redis-lab-1.0.0.jar

echo ""
echo "================================================"
echo "실습 완료!"
echo ""
echo "Redis 종료하려면:"
echo "  cd docker && docker compose down"
echo "================================================"