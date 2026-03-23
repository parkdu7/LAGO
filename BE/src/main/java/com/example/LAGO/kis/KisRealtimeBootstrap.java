package com.example.LAGO.kis;

import com.example.LAGO.domain.WatchList;
import com.example.LAGO.repository.WatchListRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class KisRealtimeBootstrap {

    private final KisWebSocketService kisWebSocketService;
    private final WatchListRepository watchListRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            log.info("[KIS] Autostart: connecting WebSocket sessions...");
            kisWebSocketService.startAll();

            // 실제 필요한 종목만 수집 (DB 전체 구독 대신)
            Set<String> requiredStocks = new LinkedHashSet<>();

            // 1) AI 봇 거래 대상 (삼성전자)
            requiredStocks.add("005930");

            // 2) 사용자 관심종목
            try {
                List<WatchList> activeWatches = watchListRepository.findAllActive();
                for (WatchList w : activeWatches) {
                    requiredStocks.add(w.getStock().getCode());
                }
            } catch (Exception e) {
                log.warn("[KIS] Failed to load watchlist, proceeding with bot stocks only: {}", e.getMessage());
            }

            log.info("[KIS] Subscribing to {} active stocks (max 40): {}", requiredStocks.size(), requiredStocks);

            // WS 용량 제한: 2 유저 × 20종목 = 최대 40
            List<String> stockList = requiredStocks.stream().limit(40).toList();
            List<String> failed = kisWebSocketService.addStocks(stockList);

            Map<String, Set<String>> dist = kisWebSocketService.getUserSubscriptions();
            int total = kisWebSocketService.getTotalActiveSubscriptions();

            log.info("[KIS] Subscriptions total={}, distribution={}, failed={}", total, dist, failed);
        } catch (Exception e) {
            log.error("[KIS] Autostart failed", e);
        }
    }
}
