# LAGO 인프라 장애 분석 및 개선 계획

> 작성일: 2026-03-23
> 배경: AWS 프리티어 배포 시 CPU 과부하로 인한 서버 다운 → 자체 서버 이전 및 구조 개선 계획

---

## 목차

1. [장애 원인 분석](#1-장애-원인-분석)
2. [틱 데이터 흐름 현황](#2-틱-데이터-흐름-현황)
3. [서비스별 리소스 현황](#3-서비스별-리소스-현황)
4. [개선 인프라 설계](#4-개선-인프라-설계)
5. [코드 수정 항목](#5-코드-수정-항목)
6. [우선순위별 작업 계획](#6-우선순위별-작업-계획)

---

## 1. 장애 원인 분석

### 1-1. 틱 데이터 동기 처리 (가장 큰 원인)

**위치:** `BE/src/main/java/com/example/LAGO/realtime/KisRealTimeDataProcessor.java:80-123`
**위치:** `BE/src/main/java/com/example/LAGO/realtime/RealtimeDataService.java:73-94`

KIS WebSocket에서 틱 데이터를 수신하면 아래 작업이 모두 **같은 스레드에서 동기적으로** 실행된다.

```
틱 수신
 → ZSTD 압축 (CPU 집약적)
 → Redis 저장 (I/O 블로킹)
 → WebSocket 클라이언트에 브로드캐스트
```

100개 종목 × 초당 1~10틱 기준으로 초당 최대 1,000번의 동기 작업이 발생한다.
AWS 프리티어(1 vCPU)에서는 감당 불가 수준.

---

### 1-2. 스케줄러 동시 실행 (CPU 스파이크)

| 파일 | 주기 | 문제 |
|------|------|------|
| `ChunkAutoIngestor.java:27` | **2초마다** | Redis ZSET 폴링 - 분당 30회 쿼리 |
| `AssetUpdateScheduler.java:38` | 3분마다 | 전체 계좌 대상 LEFT JOIN 쿼리 |
| `AutoTradingBotService.java:78` | 1분마다 | `.parallelStream()` → 스레드 수 무제한 |
| `RealtimeDataService.java:407` | 1분마다 | 전체 청크 압축 및 Redis 일괄 저장 |
| `PriceSimulatorService.java:51` | 1분마다 | 더미 틱 데이터 생성 및 DB 삽입 (개발용) |
| `NewsScheduler.java:20` | 20분마다 | 뉴스 크롤링 |

여러 스케줄러가 같은 시각에 겹치면 CPU 사용률이 순간적으로 치솟는다.

---

### 1-3. JVM 메모리 제한 없음 (OOM 강제 종료)

**위치:** `BE/Dockerfile:15`

```dockerfile
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`-Xmx` 옵션이 없으면 JVM이 서버 전체 메모리를 최대 25%까지 자동 할당한다.
FinBERT(2~4GB), Chart Analysis(500MB~1GB)와 함께 실행되면 OOM으로 컨테이너 강제 종료된다.

---

### 1-4. 서버 시작 시 전체 종목 KIS WebSocket 구독

**위치:** `BE/src/main/java/com/example/LAGO/kis/KisRealtimeBootstrap.java:30`

```java
kisWebSocketService.addAllStocksFromDatabase();
```

애플리케이션 기동 직후 DB의 STOCK_INFO 전체를 불러와 WebSocket 구독을 시작한다.
구독 종목 수에 비례해 메모리 및 CPU 사용량이 선형 증가한다.

---

### 1-5. 인메모리 청크 무제한 누적 (메모리 누수)

**위치:** `BE/src/main/java/com/example/LAGO/realtime/RealtimeDataService.java:60`

```java
Map<Integer, TickChunk> stockChunks = new ConcurrentHashMap<>();
```

종목별 틱 청크가 1,000개 미만이면 메모리에 무기한 누적된다.
TTL이나 만료 정책이 없어 장기 운영 시 메모리 누수가 발생한다.

---

### 1-6. FinBERT 서비스 메모리 과다 (프리티어에서 즉사)

PyTorch + Transformers 모델이 상시 메모리 2~4GB를 점유한다.
AWS 프리티어(1GB RAM)에서는 Spring Backend와 공존 불가능.

---

## 2. 틱 데이터 흐름 현황

```
KIS WebSocket
    ↓ (동기, 블로킹)
KisRealTimeDataProcessor.processStockData()
    ↓
RealtimeDataService.saveTickData()
    ├─→ ZSTD 압축 → Redis 저장 (ticks:chunk:{id}:blob)
    ├─→ 최신 틱 저장 (realtime:stock:{code})
    └─→ WebSocket 브로드캐스트 (클라이언트)

    ↓ (매 60초, 별도 스레드)
RealtimeDataService.flushPendingChunks()
    → 미완성 청크 Redis 저장

    ↓ (매 2초, 폴링)
ChunkAutoIngestor.drain()
    → Redis ZSET에서 만료된 청크 조회
    → TimescaleDB UPSERT

    ↓ (매 60초)
MinuteCandleService
    → 분봉 생성 → DB 저장 → WebSocket 브로드캐스트
```

**핵심 문제:** 수신부터 DB 저장까지 모두 동기식. 폴링 기반이라 2초마다 불필요한 Redis 조회 발생.

---

## 3. 서비스별 리소스 현황

| 서비스 | 언어 | 예상 메모리 | CPU 부하 | 비고 |
|--------|------|------------|---------|------|
| Spring Backend | Java 21 | 512MB~2GB | 높음 (틱처리, 스케줄러) | 메모리 제한 필요 |
| TimescaleDB | PostgreSQL | 256MB~1GB | 중간 | 쿼리 최적화 필요 |
| Redis | - | 128MB~512MB | 낮음 | - |
| FinBERT | Python | **2~4GB** | 높음 (추론 시) | 분리 배포 권장 |
| Chart Analysis | Python | 500MB~1GB | 중간 | - |

**현재 서버 (RAM 7.6GB) 기준:**
- FinBERT 포함 시 빡빡하게 가능 (모델 경량화 권장)
- FinBERT 제외 시 안정적으로 운영 가능

---

## 4. 개선 인프라 설계

### 4-1. RabbitMQ 도입 (핵심 개선)

현재 ChunkAutoIngestor의 2초 폴링 구조와 동기식 틱 처리를 이벤트 드리븐으로 전환한다.

```
KIS WebSocket
    ↓ (논블로킹, 즉시 반환)
[RabbitMQ: tick.raw 큐]
    ↓ (Consumer 워커 - 별도 스레드)
    ├─→ Redis 캐시 저장 (최신 틱)
    ├─→ WebSocket 브로드캐스트
    └─→ [RabbitMQ: tick.ingest 큐]
            ↓ (rate-limited Consumer)
        TimescaleDB 저장
```

**도입 효과:**
- WebSocket 수신 스레드가 즉시 반환 → CPU 스파이크 제거
- 큐에 메시지가 쌓이면 자동으로 백프레셔(처리 속도 조절)
- 폴링 제거 → 2초마다 Redis 조회 없어짐
- 재처리, 모니터링, 라우팅 등 운영 기능 내장

### 4-2. docker-compose 구성 (개선안)

```yaml
services:
  timescaledb:     # 기존 유지
  redis:           # 기존 유지
  rabbitmq:        # 신규 추가
    image: rabbitmq:3.13-management
    ports:
      - "5672:5672"
      - "15672:15672"  # 관리 UI
  chart-analysis:  # 기존 유지
  backend:         # JVM 옵션 추가
    # FinBERT는 별도 서버 또는 On-demand로 분리 권장
```

### 4-3. JVM 튜닝 (즉시 적용 가능)

```dockerfile
ENTRYPOINT ["java",
  "-Xms256m",
  "-Xmx512m",
  "-XX:+UseG1GC",
  "-XX:MaxGCPauseMillis=200",
  "-jar", "app.jar"]
```

---

## 5. 코드 수정 항목

### [긴급] 즉시 수정

| 파일 | 위치 | 문제 | 조치 |
|------|------|------|------|
| `BE/Dockerfile` | line 15 | JVM 메모리 무제한 | `-Xmx512m -XX:+UseG1GC` 추가 |
| `PriceSimulatorService.java` | line 51 | 개발용 더미 데이터 생성기가 운영에서도 동작 | `@ConditionalOnProperty`로 dev 환경에서만 활성화 |
| `AutoTradingBotService.java` | line 103 | `.parallelStream()` 스레드 무제한 | 고정 크기 `ExecutorService`로 교체 |

### [단기] RabbitMQ 전환 시 수정

| 파일 | 위치 | 조치 |
|------|------|------|
| `KisRealTimeDataProcessor.java` | line 80-123 | 틱 수신 후 RabbitMQ 큐에 publish만 하도록 변경 |
| `RealtimeDataService.java` | line 407-430 | `flushPendingChunks` 스케줄러 제거 → 메시지 드리븐 전환 |
| `ChunkAutoIngestor.java` | line 27 | 2초 폴링 제거 → RabbitMQ Consumer로 교체 |
| `KisRealtimeBootstrap.java` | line 30 | 전체 종목 즉시 구독 → 페이지네이션 또는 지연 로딩 |
| `RealtimeDataService.java` | line 60 | 인메모리 청크에 TTL 정책 추가 |

### [중기] 안정화

| 항목 | 내용 |
|------|------|
| DB 인덱스 추가 | `AssetUpdateScheduler`의 JOIN 대상 컬럼 인덱스 확인 |
| FinBERT 분리 | 별도 서버 or GPU 인스턴스로 이전, 또는 모델 경량화 (quantization) |
| 커넥션 풀 설정 | HikariCP `maximumPoolSize` 명시적 설정 |
| 서킷 브레이커 | KIS API 장애 시 무한 재시도 방지 |

---

## 6. 우선순위별 작업 계획

### Phase 1 — 서버 세팅 (지금 당장)
- [ ] Docker / Docker Compose 설치
- [ ] `.env` 파일 구성 (DB, Redis, KIS API, OAuth 키 등)
- [ ] `BE/Dockerfile` JVM 옵션 추가
- [ ] `PriceSimulatorService` 운영 환경 비활성화
- [ ] `docker compose up -d` 로 전체 스택 기동 확인

### Phase 2 — 긴급 코드 패치 (서버 안정화)
- [ ] `AutoTradingBotService.parallelStream()` → 고정 스레드풀 교체
- [ ] `KisRealtimeBootstrap` 전체 종목 즉시 구독 제한
- [ ] 인메모리 청크 TTL 정책 추가

### Phase 3 — RabbitMQ 도입 (구조 개선)
- [ ] `docker-compose.yml`에 RabbitMQ 서비스 추가
- [ ] Spring `spring-boot-starter-amqp` 의존성 추가
- [ ] `KisRealTimeDataProcessor` → 틱 수신 시 큐 publish만
- [ ] `TickRawConsumer` 신규 작성 (Redis 저장 + 브로드캐스트)
- [ ] `TickIngestConsumer` 신규 작성 (TimescaleDB 저장)
- [ ] `ChunkAutoIngestor` 폴링 제거

### Phase 4 — 장기 안정화
- [ ] FinBERT 분리 또는 경량화
- [ ] DB 쿼리 최적화 및 인덱스 점검
- [ ] 서킷 브레이커 / Rate Limiter 도입
- [ ] 모니터링 대시보드 구축 (Prometheus + Grafana)
