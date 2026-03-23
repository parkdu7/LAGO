package com.example.LAGO.kis;

import com.example.LAGO.service.StockInfoService;
import com.example.LAGO.dto.StockInfoDto;
import com.example.LAGO.realtime.KisRealTimeDataProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service  // 매매 테스트를 위해 임시 비활성화
public class KisWebSocketService {
    
    private final Map<String, KisWebSocketClient> webSocketClients = new HashMap<>();
    private final Map<String, Set<String>> userSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> connectionStatus = new ConcurrentHashMap<>();
    private final int MAX_STOCKS_PER_USER = 20;
    private final StockInfoService stockInfoService;
    private final KisRealTimeDataProcessor dataProcessor;

    // 전역 가드: 종목 -> 소유 유저 (동일 종목을 다른 유저가 동시에 받는걸 방지)
    private final ConcurrentMap<String, String> stockOwner = new ConcurrentHashMap<>();
    
    public KisWebSocketService(
            @Qualifier("kisAuth_userA") KisAuthClient kisAuthClientA,
            @Qualifier("kisAuth_userB") KisAuthClient kisAuthClientB,
            StockInfoService stockInfoService,
            KisRealTimeDataProcessor dataProcessor) {
        
        this.stockInfoService = stockInfoService;
        this.dataProcessor = dataProcessor;
        
        // 각 사용자별 WebSocket 클라이언트 초기화
        this.webSocketClients.put("userA", new KisWebSocketClient(kisAuthClientA, dataProcessor));
        this.webSocketClients.put("userB", new KisWebSocketClient(kisAuthClientB, dataProcessor));
        
        // 구독 목록 및 연결 상태 초기화
        this.userSubscriptions.put("userA", ConcurrentHashMap.newKeySet());
        this.userSubscriptions.put("userB", ConcurrentHashMap.newKeySet());
        this.connectionStatus.put("userA", false);
        this.connectionStatus.put("userB", false);
    }

    public void startAll() {
        for (String userId : webSocketClients.keySet()) {
            try {
                KisWebSocketClient client = webSocketClients.get(userId);
                client.connect("prod"); // 실제 WS 연결 (한 번만 연결되고 재사용됨)
                connectionStatus.put(userId, client.isOpen());
                // 원하면 여기서 일부/전부 종목 바로 배분-구독까지 호출 가능
            } catch (Exception e) {
                connectionStatus.put(userId, false);
                // 로그 등 처리
            }
        }
    }


    public void stopAll() {
        System.out.println("Stopping all WebSocket connections...");
        
        for (String userId : webSocketClients.keySet()) {
            try {
                System.out.println("Stopping WebSocket for " + userId);
                connectionStatus.put(userId, false);
                userSubscriptions.get(userId).clear();
                System.out.println("WebSocket disconnected for " + userId);
            } catch (Exception e) {
                System.err.println("Failed to disconnect WebSocket for " + userId + ": " + e.getMessage());
            }
        }
    }
    
    public List<String> addStocks(List<String> stockCodes) {
        List<String> failedStocks = new ArrayList<>();
        
        for (String stockCode : stockCodes) {

            // 1. 유저 선택(라운드로빈/용량 기준)
            String assignedUser = assignStockToUser(stockCode);
            if (assignedUser == null) {
                failedStocks.add(stockCode);
                continue;
            }

            try {
                KisWebSocketClient client = webSocketClients.get(assignedUser);
                // H0STCNT0 고정 (실시간 체결가)
                client.connectAndSubscribe("H0STCNT0", stockCode, "prod");
                userSubscriptions.get(assignedUser).add(stockCode);
                System.out.println("Successfully subscribed " + stockCode + " to " + assignedUser);
            } catch (Exception e) {
                System.err.println("Failed to subscribe " + stockCode + " to " + assignedUser + ": " + e.getMessage());
                failedStocks.add(stockCode);
            }
        }

        return failedStocks;
    }
    
    /**
     * STOCK_INFO 테이블에서 모든 주식 종목을 조회하여 자동으로 WebSocket 구독
     * @deprecated KisRealtimeBootstrap에서 WatchList 기반 선택적 구독으로 대체됨.
     *             전체 종목 구독은 WS 용량(40개) 초과 및 리소스 낭비 위험.
     */
    @Deprecated
    public List<String> addAllStocksFromDatabase() {
        try {
            // DB에서 모든 주식 정보 조회
            List<StockInfoDto> allStocks = stockInfoService.getAllStockInfo();
            
            // 종목 코드만 추출
            List<String> stockCodes = allStocks.stream()
                    .map(StockInfoDto::getCode)
                    .collect(Collectors.toList());
            
            System.out.println("Found " + stockCodes.size() + " stocks in database: " + stockCodes);
            
            // 기존 addStocks 메서드 사용하여 구독
            return addStocks(stockCodes);
            
        } catch (Exception e) {
            System.err.println("Failed to load stocks from database: " + e.getMessage());
            return List.of(); // 빈 리스트 반환
        }
    }
    
    public boolean removeStock(String stockCode) {
        for (String userId : userSubscriptions.keySet()) {
            if (userSubscriptions.get(userId).contains(stockCode)) {
                userSubscriptions.get(userId).remove(stockCode);
                System.out.println("Successfully removed " + stockCode + " from " + userId);
                return true;
            }
        }
        
        System.out.println("Stock " + stockCode + " not found in any subscription");
        return false;
    }
    
    private String assignStockToUser(String stockCode) {
        // 이미 구독 중인지 확인
        for (String userId : userSubscriptions.keySet()) {
            if (userSubscriptions.get(userId).contains(stockCode)) {
                System.out.println("Stock " + stockCode + " already subscribed by " + userId);
                return null;
            }
        }
        
        // 라운드로빈 방식으로 할당
        String userA = "userA";
        String userB = "userB";
        
        int userACount = userSubscriptions.get(userA).size();
        int userBCount = userSubscriptions.get(userB).size();
        
        if (userACount < MAX_STOCKS_PER_USER && userACount <= userBCount) {
            return userA;
        } else if (userBCount < MAX_STOCKS_PER_USER) {
            return userB;
        }
        
        System.err.println("Cannot assign " + stockCode + ": All users at maximum capacity");
        return null;
    }
    
    public Map<String, Boolean> getConnectionStatus() {
        return Map.copyOf(connectionStatus);
    }
    
    public Map<String, Set<String>> getUserSubscriptions() {
        Map<String, Set<String>> copy = new HashMap<>();
        userSubscriptions.forEach((user, stocks) -> copy.put(user, Set.copyOf(stocks)));
        return copy;
    }
    
    public Set<String> getUserStocks(String userId) {
        return Set.copyOf(userSubscriptions.getOrDefault(userId, Set.of()));
    }
    
    public int getTotalActiveSubscriptions() {
        return userSubscriptions.values().stream()
                .mapToInt(Set::size)
                .sum();
    }
    
    public void printStatus() {
        System.out.println("=== KIS WebSocket Multi-User Status ===");
        
        for (String userId : webSocketClients.keySet()) {
            boolean connected = connectionStatus.get(userId);
            int stockCount = userSubscriptions.get(userId).size();
            
            System.out.println(userId + " - Connected: " + connected + ", Stocks: " + stockCount + "/" + MAX_STOCKS_PER_USER);
            userSubscriptions.get(userId).forEach(stock -> System.out.println("  - " + stock));
        }
        
        System.out.println("Total subscriptions: " + getTotalActiveSubscriptions());
        System.out.println("=======================================");
    }
}