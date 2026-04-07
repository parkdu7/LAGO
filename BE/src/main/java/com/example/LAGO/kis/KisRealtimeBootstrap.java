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

            // 1) 코스피 상위 30종목
            requiredStocks.add("005930"); // 삼성전자
            requiredStocks.add("000660"); // SK하이닉스
            requiredStocks.add("005380"); // 현대차
            requiredStocks.add("005935"); // 삼성전자우
            requiredStocks.add("000270"); // 기아
            requiredStocks.add("068270"); // 셀트리온
            requiredStocks.add("105560"); // KB금융
            requiredStocks.add("055550"); // 신한지주
            requiredStocks.add("012330"); // 현대모비스
            requiredStocks.add("035420"); // NAVER
            requiredStocks.add("003550"); // LG
            requiredStocks.add("096770"); // SK이노베이션
            requiredStocks.add("003670"); // 포스코홀딩스
            requiredStocks.add("051910"); // LG화학
            requiredStocks.add("034730"); // SK
            requiredStocks.add("030200"); // KT
            requiredStocks.add("017670"); // SK텔레콤
            requiredStocks.add("032830"); // 삼성생명
            requiredStocks.add("086790"); // 하나금융지주
            requiredStocks.add("066570"); // LG전자
            requiredStocks.add("028260"); // 삼성물산
            requiredStocks.add("009150"); // 삼성전기
            requiredStocks.add("018260"); // 삼성에스디에스
            requiredStocks.add("011170"); // 롯데케미칼
            requiredStocks.add("000810"); // 삼성화재
            requiredStocks.add("033780"); // KT&G
            requiredStocks.add("035720"); // 카카오
            requiredStocks.add("352820"); // 하이브
            requiredStocks.add("207940"); // 삼성바이오로직스
            requiredStocks.add("006400"); // 삼성SDI

            // 2) 사용자 관심종목
            try {
                List<WatchList> activeWatches = watchListRepository.findAllActive();
                for (WatchList w : activeWatches) {
                    requiredStocks.add(w.getStock().getCode());
                }
            } catch (Exception e) {
                log.warn("[KIS] Failed to load watchlist, proceeding with default stocks only: {}", e.getMessage());
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
