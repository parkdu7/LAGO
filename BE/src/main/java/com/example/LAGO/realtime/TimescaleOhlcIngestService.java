package com.example.LAGO.realtime;

import com.example.LAGO.realtime.TickDataSerializer.Decoded16B;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.LAGO.service.TickChunkReaderService;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Redis에 저장된 압축 청크를 언패킹하여
 * TimescaleDB "TICKS" 하이퍼테이블에 "초(1s) 단위 OHLCV"로 업서트하는 서비스.
 *
 * - 같은 (stock_info_id, ts[초])에 여러 틱이 있으면 그룹핑해서
 *   open: 첫 틱, high: 최대, low: 최소, close: 마지막, volume: 합계
 * - PK (stock_info_id, ts) 기준으로 ON CONFLICT UPSERT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimescaleOhlcIngestService {


    private final TickChunkReaderService reader; // Redis에서 청크 읽기(압축해제+16B 파싱)
    private final JdbcTemplate jdbc;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private static final String UPSERT_SQL = """
        INSERT INTO ticks
            (stock_info_id, ts, open_price, high_price, low_price, close_price, volume)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (stock_info_id, ts) DO UPDATE SET
            open_price  = COALESCE(ticks.open_price, EXCLUDED.open_price),
            high_price  = GREATEST(COALESCE(ticks.high_price, EXCLUDED.high_price), EXCLUDED.high_price),
            low_price   = LEAST(COALESCE(ticks.low_price, EXCLUDED.low_price), EXCLUDED.low_price),
            close_price = EXCLUDED.close_price,
            volume      = COALESCE(ticks.volume, 0) + COALESCE(EXCLUDED.volume, 0)
        """;

    /**
     * 한 개의 청크를 읽어 1초 단위 OHLCV로 업서트.
     * @param chunkId ticks:chunk:{id}:blob / :meta 형식의 {id}
     * @return upsert된 행 수
     */
    @Transactional
    public int ingestChunkAs1sOHLC(String chunkId) {
        List<Decoded16B> ticks = reader.readChunk(chunkId);
        if (ticks.isEmpty()) {
            log.info("No rows in chunk {}", chunkId);
            return 0;
        }

        // (stockId, ts[초]) → 집계 구조
        final class Key {
            final int stockId;
            final Instant tsSec;
            Key(int stockId, Instant tsSec) { this.stockId = stockId; this.tsSec = tsSec; }
            @Override public boolean equals(Object o){ if(this==o) return true; if(!(o instanceof Key k)) return false;
                return stockId==k.stockId && Objects.equals(tsSec,k.tsSec); }
            @Override public int hashCode(){ return Objects.hash(stockId, tsSec); }
        }
        final class Agg {
            Integer open, high, low, close;
            long volume = 0;
            void accept(int price, int vol){
                if (open == null) open = price;
                if (high == null || price > high) high = price;
                if (low  == null || price < low )  low  = price;
                close = price;
                volume += vol;
            }
        }

        Map<Key, Agg> grouped = new LinkedHashMap<>();
        for (var d : ticks) {
//            // 1) Instant(절대시각) -> KST로 시각화
//            ZonedDateTime kst = d.ts().atZone(KST);
//
//            // 2) KST에서 초 단위 절삭
//            ZonedDateTime kstSec = kst.truncatedTo(ChronoUnit.SECONDS);

            // 3)
            Instant tsSec = d.ts().truncatedTo(ChronoUnit.SECONDS);
            Key k = new Key(d.stockId(), tsSec);
            grouped.computeIfAbsent(k, __ -> new Agg())
                    .accept(d.price(), d.volume());
        }

        // 배치 업서트
        List<Object[]> params = new ArrayList<>(grouped.size());
        for (var e : grouped.entrySet()) {
            Key k = e.getKey();
            Agg a = e.getValue();
            params.add(new Object[]{
                    k.stockId,
                    Timestamp.from(k.tsSec),
                    a.open, a.high, a.low, a.close,
                    a.volume
            });
        }
        int[] res = jdbc.batchUpdate(UPSERT_SQL, params);
        int affected = Arrays.stream(res).map(n -> Math.max(n, 0)).sum();

        log.info("✅ Ingested chunk={} rows={}", chunkId, affected);
        return affected;
    }
}
