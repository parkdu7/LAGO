package com.example.LAGO.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

/**
 * 삼성전자 주가 시뮬레이터 서비스
 * ticks 테이블에 1분마다 더미 주가 데이터를 삽입하여 AI 자동매매봇 테스트 지원
 * 
 * @author LAGO D203팀
 * @since 2025-08-15
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "app.price.simulator.enabled", havingValue = "true", matchIfMissing = false)
public class PriceSimulatorService {

    private final JdbcTemplate jdbcTemplate;
    
    /** 삼성전자 종목 코드 */
    private static final String SAMSUNG_STOCK_CODE = "005930";
    
    /** 기준 가격 */
    private static final int BASE_PRICE = 75000;
    
    /** 가격 변동 범위 */
    private static final int MIN_PRICE = 70000;
    private static final int MAX_PRICE = 80000;
    
    /** 최대 변동률 (%) */
    private static final double MAX_CHANGE_PERCENT = 2.0;
    
    private final Random random = new Random();
    private int currentPrice = BASE_PRICE;
    
    @Value("${app.price.simulator.volatility:1.0}")
    private double volatility;
    
    /**
     * 매분마다 삼성전자 더미 주가 데이터 생성 및 삽입
     */
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    public void generateSamsungPriceData() {
        try {
            log.info("📈 === 삼성전자 주가 시뮬레이션 시작: {} ===", LocalDateTime.now());
            
            // 1. 새로운 가격 생성
            int newPrice = generateRealisticPrice();
            int oldPrice = currentPrice;
            currentPrice = newPrice;
            
            // 2. OHLCV 데이터 생성
            PriceData priceData = generateOHLCVData(newPrice);
            
            // 3. ticks 테이블에 삽입
            insertTicksData(priceData);
            
            // 4. 변동 정보 로깅
            logPriceChange(oldPrice, newPrice, priceData);
            
            // 5. 큰 변동 시 특별 알림
            double changePercent = ((double)(newPrice - oldPrice) / oldPrice) * 100;
            if (Math.abs(changePercent) > 1.5) {
                log.warn("🚨 삼성전자 급변동 발생! {:.2f}% → AI 자동매매 활성화 예상", changePercent);
            }
            
        } catch (Exception e) {
            log.error("🔥 삼성전자 주가 시뮬레이션 실패", e);
        }
    }
    
    /**
     * 현실적인 주가 변동 생성
     */
    private int generateRealisticPrice() {
        // 랜덤워크 + 평균회귀 모델
        
        // 1. 기본 랜덤 변동 (-2% ~ +2%)
        double randomChange = (random.nextGaussian() * MAX_CHANGE_PERCENT * volatility) / 100;
        
        // 2. 평균 회귀 효과 (기준가격으로 되돌아가려는 경향)
        double meanReversionForce = (BASE_PRICE - currentPrice) * 0.001;
        
        // 3. 트렌드 효과 (약간의 상승 편향)
        double trendEffect = 0.0002;
        
        // 4. 총 변동률 계산
        double totalChange = randomChange + meanReversionForce + trendEffect;
        
        // 5. 새 가격 계산
        int newPrice = (int) (currentPrice * (1 + totalChange));
        
        // 6. 가격 범위 제한
        newPrice = Math.max(MIN_PRICE, Math.min(MAX_PRICE, newPrice));
        
        // 7. 100원 단위로 반올림 (현실적인 주가 단위)
        return (newPrice / 100) * 100;
    }
    
    /**
     * OHLCV 데이터 생성 (Close 기준으로 Open, High, Low, Volume 생성)
     */
    private PriceData generateOHLCVData(int closePrice) {
        // Open: 이전 Close와 유사하게 생성
        int openPrice = currentPrice + random.nextInt(200) - 100;
        openPrice = Math.max(MIN_PRICE, Math.min(MAX_PRICE, openPrice));
        
        // High, Low: Open과 Close 기준으로 생성
        int minPrice = Math.min(openPrice, closePrice);
        int maxPrice = Math.max(openPrice, closePrice);
        
        int highPrice = maxPrice + random.nextInt(300);
        int lowPrice = minPrice - random.nextInt(300);
        
        // 범위 제한
        highPrice = Math.max(maxPrice, Math.min(MAX_PRICE, highPrice));
        lowPrice = Math.max(MIN_PRICE, Math.min(minPrice, lowPrice));
        
        // Volume: 10만 ~ 50만주 사이
        int volume = 100000 + random.nextInt(400000);
        
        return new PriceData(openPrice, highPrice, lowPrice, closePrice, volume);
    }
    
    /**
     * ticks 테이블에 데이터 삽입
     */
    private void insertTicksData(PriceData priceData) {
        String sql = """
            INSERT INTO ticks (ts, stock_info_id, open_price, high_price, low_price, close_price, volume)
            VALUES (NOW(), (SELECT stock_info_id FROM stock_info WHERE code = ?), ?, ?, ?, ?, ?)
        """;

        jdbcTemplate.update(sql,
            SAMSUNG_STOCK_CODE,
            priceData.openPrice,
            priceData.highPrice,
            priceData.lowPrice,
            priceData.closePrice,
            priceData.volume
        );
        
        log.debug("📊 ticks 데이터 삽입 성공: O({}) H({}) L({}) C({}) V({})", 
            priceData.openPrice, priceData.highPrice, priceData.lowPrice, 
            priceData.closePrice, priceData.volume);
    }
    
    /**
     * 가격 변동 로깅
     */
    private void logPriceChange(int oldPrice, int newPrice, PriceData priceData) {
        int change = newPrice - oldPrice;
        double changePercent = oldPrice > 0 ? ((double)change / oldPrice) * 100 : 0;
        
        String direction = change > 0 ? "📈" : change < 0 ? "📉" : "➡️";
        String trend = Math.abs(changePercent) > 1.0 ? " 🔥" : "";
        
        log.info("{} 삼성전자: {:,}원 → {:,}원 ({:+,}원, {:+.2f}%){}",
            direction, oldPrice, newPrice, change, changePercent, trend);
        
        log.debug("   📊 OHLCV: O({:,}) H({:,}) L({:,}) C({:,}) V({:,})",
            priceData.openPrice, priceData.highPrice, priceData.lowPrice, 
            priceData.closePrice, priceData.volume);
    }
    
    /**
     * 현재 시뮬레이션 상태 조회
     */
    public SimulatorStatus getStatus() {
        return new SimulatorStatus(
            SAMSUNG_STOCK_CODE,
            currentPrice,
            BASE_PRICE,
            volatility,
            LocalDateTime.now()
        );
    }
    
    /**
     * 변동성 조정
     */
    public void setVolatility(double volatility) {
        this.volatility = Math.max(0.1, Math.min(3.0, volatility));
        log.info("📊 변동성 조정: {:.1f}", this.volatility);
    }
    
    /**
     * 가격을 특정 값으로 강제 설정
     */
    public void forcePrice(int price) {
        price = Math.max(MIN_PRICE, Math.min(MAX_PRICE, price));
        this.currentPrice = price;
        log.info("🎯 강제 가격 설정: {:,}원", price);
    }
    
    // ===================== 내부 클래스 =====================
    
    /**
     * OHLCV 가격 데이터
     */
    private static class PriceData {
        final int openPrice;
        final int highPrice;
        final int lowPrice;
        final int closePrice;
        final int volume;
        
        public PriceData(int openPrice, int highPrice, int lowPrice, int closePrice, int volume) {
            this.openPrice = openPrice;
            this.highPrice = highPrice;
            this.lowPrice = lowPrice;
            this.closePrice = closePrice;
            this.volume = volume;
        }
    }
    
    /**
     * 시뮬레이터 상태 정보
     */
    public static class SimulatorStatus {
        public final String stockCode;
        public final int currentPrice;
        public final int basePrice;
        public final double volatility;
        public final LocalDateTime lastUpdate;
        
        public SimulatorStatus(String stockCode, int currentPrice, int basePrice, 
                             double volatility, LocalDateTime lastUpdate) {
            this.stockCode = stockCode;
            this.currentPrice = currentPrice;
            this.basePrice = basePrice;
            this.volatility = volatility;
            this.lastUpdate = lastUpdate;
        }
    }
}