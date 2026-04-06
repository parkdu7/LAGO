package com.example.LAGO.service;

import com.example.LAGO.constants.TradingConstants;
import com.example.LAGO.domain.Account;
import com.example.LAGO.domain.News;
import com.example.LAGO.domain.TradeType;
import com.example.LAGO.domain.User;
import com.example.LAGO.dto.request.TradeRequest;
import com.example.LAGO.dto.response.TechnicalAnalysisResult;
import com.example.LAGO.repository.AccountRepository;
import com.example.LAGO.repository.NewsRepository;
import com.example.LAGO.repository.UserRepository;
import com.example.LAGO.repository.TicksRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * FinBERT 뉴스분석 + 기술적분석 기반 AI 자동매매봇 서비스
 * 
 * 핵심 기능:
 * - 매분마다 실행되는 자동매매 스케줄러
 * - 뉴스 감정분석 점수와 기술적분석 통합 판단
 * - 각 AI 봇의 성향별 차별화된 매매 전략
 * - 검증된 StockController API 활용한 안전한 매매
 * 
 * @author LAGO D203팀
 * @since 2025-08-15
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AutoTradingBotService {

    // ======================== 의존성 주입 ========================
    
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final NewsRepository newsRepository;
    private final TechnicalAnalysisService technicalAnalysisService;
    private final TicksRepository ticksRepository;
    private final RestTemplate restTemplate;
    
    @Value("${server.port:9000}")
    private String serverPort;
    
    // ======================== 상수 정의 ========================
    
    /** 삼성전자 종목 코드 */
    private static final String SAMSUNG_STOCK_CODE = "005930";
    
    /** AI 봇 계좌 타입 */
    private static final Integer AI_BOT_ACCOUNT_TYPE = 2;
    
    /** 매매 신호 임계값 */
    private static final double BUY_THRESHOLD = 0.1;  // 낮춤: 더 쉽게 매수 신호
    private static final double SELL_THRESHOLD = -0.1; // 낮춤: 더 쉽게 매도 신호
    
    /** 기본 매매 수량 */
    private static final int DEFAULT_QUANTITY = 1;
    
    // ======================== 메인 스케줄러 ========================
    
    /**
     * AI 자동매매 메인 스케줄러
     * 매분마다 실행되어 모든 AI 봇의 매매를 처리
     */
    @Scheduled(fixedRate = 60000) // 매분 실행 (60초)
    public void executeAutoTrading() {
        log.info("🤖 === AI 자동매매 실행 시작: {} ===", LocalDateTime.now());
        
        try {
            // 1. 활성 AI 봇들 조회
            List<User> activeBots = getActiveAiBots();
            if (activeBots.isEmpty()) {
                log.info("⚠️ 활성 AI 봇이 없습니다. 다음 주기를 대기합니다.");
                return;
            }
            
            log.info("📊 활성 AI 봇 {}개 발견, 매매 분석을 시작합니다.", activeBots.size());
            
            // 2. 삼성전자 뉴스 감정분석 점수 조회
            double sentimentScore = getLatestSamsungSentiment();
            log.info("📰 삼성전자 뉴스 감정점수: {}", sentimentScore);
            
            // 3. 삼성전자 기술적 분석
            TechnicalAnalysisResult technical = getTechnicalAnalysis();
            log.info("📈 삼성전자 기술적분석: RSI={}, MACD={}", 
                    technical != null ? technical.getRsi() : "N/A",
                    technical != null ? technical.getMacdLine() : "N/A");
            
            // 4. 각 봇별 매매 실행 (병렬 처리)
            activeBots.stream().forEach(bot -> {
                try {
                    executeTradeForBot(bot, sentimentScore, technical);
                } catch (Exception e) {
                    log.error("🔥 AI 봇 {} 매매 실행 중 오류: {}", bot.getNickname(), e.getMessage(), e);
                }
            });
            
            log.info("✅ === 모든 AI 봇 매매 실행 완료: {} ===", LocalDateTime.now());
            
        } catch (Exception e) {
            log.error("🔥 자동매매 스케줄러 실행 중 오류 발생", e);
        }
    }
    
    // ======================== AI 봇 조회 ========================
    
    /**
     * Type 2 계좌를 보유한 활성 AI 봇들 조회
     */
    private List<User> getActiveAiBots() {
        try {
            List<User> aiBots = userRepository.findByIsAiTrueAndDeletedAtIsNull();
            
            // Type 2 계좌 보유 여부 확인
            List<User> activeBots = aiBots.stream()
                    .filter(this::hasAiBotAccount)
                    .toList();
            
            log.debug("🔍 총 AI 봇: {}개, 활성 봇: {}개", aiBots.size(), activeBots.size());
            return activeBots;
            
        } catch (Exception e) {
            log.error("🔥 활성 AI 봇 조회 실패", e);
            return List.of();
        }
    }
    
    /**
     * AI 봇의 Type 2 계좌 보유 여부 확인
     */
    private boolean hasAiBotAccount(User aiBot) {
        try {
            return accountRepository.findByUserIdAndType(
                    aiBot.getUserId(), 
                    AI_BOT_ACCOUNT_TYPE
            ).isPresent();
            
        } catch (Exception e) {
            log.warn("⚠️ AI 봇 {}의 계좌 확인 실패: {}", aiBot.getNickname(), e.getMessage());
            return false;
        }
    }
    
    // ======================== 뉴스 감정분석 ========================
    
    /**
     * 최근 뉴스에서 삼성전자 관련 감정분석 점수 조회
     * @return 감정점수 (-1.0 ~ 1.0, 없으면 0.0)
     */
    private double getLatestSamsungSentiment() {
        try {
            // 최근 2시간 이내 뉴스 중 삼성전자 관련 뉴스 조회
            LocalDateTime since = LocalDateTime.now().minusHours(2);
            
            List<News> recentNews = newsRepository.findByPublishedAtAfterAndTitleContainingOrContentContaining(
                    since, "삼성전자", "삼성전자"
            );
            
            if (recentNews.isEmpty()) {
                log.debug("📰 최근 삼성전자 뉴스가 없음, 중립 점수 사용");
                return 0.0;
            }
            
            // sentiment 필드에서 점수 추출하여 평균 계산
            double averageSentiment = recentNews.stream()
                    .filter(news -> news.getSentiment() != null)
                    .mapToDouble(this::parseSentimentScore)
                    .filter(score -> score != 0.0) // 파싱 실패한 것들 제외
                    .average()
                    .orElse(0.0);
            
            log.debug("📊 삼성전자 뉴스 {}개 분석, 평균 감정점수: {}", recentNews.size(), averageSentiment);
            
            return averageSentiment;
            
        } catch (Exception e) {
            log.error("🔥 뉴스 감정분석 점수 조회 실패", e);
            return 0.0;
        }
    }
    
    /**
     * sentiment 문자열에서 숫자 점수 추출
     */
    private double parseSentimentScore(News news) {
        try {
            String sentiment = news.getSentiment();
            if (sentiment == null) return 0.0;
            
            // "positive: 0.75" 형태에서 숫자 추출
            if (sentiment.contains("positive")) {
                String[] parts = sentiment.split(":");
                if (parts.length > 1) {
                    return Double.parseDouble(parts[1].trim());
                }
            } else if (sentiment.contains("negative")) {
                String[] parts = sentiment.split(":");
                if (parts.length > 1) {
                    return -Double.parseDouble(parts[1].trim());
                }
            }
            
            // 직접 숫자인 경우
            return Double.parseDouble(sentiment);
            
        } catch (Exception e) {
            log.debug("⚠️ sentiment 파싱 실패: {}", news.getSentiment());
            return 0.0;
        }
    }
    
    // ======================== 기술적 분석 ========================
    
    /**
     * 삼성전자 기술적 분석 결과 조회
     */
    private TechnicalAnalysisResult getTechnicalAnalysis() {
        try {
            return technicalAnalysisService.analyzeStock(SAMSUNG_STOCK_CODE);
            
        } catch (Exception e) {
            log.error("🔥 기술적 분석 실행 실패", e);
            return null;
        }
    }
    
    // ======================== 개별 봇 매매 실행 ========================
    
    /**
     * 개별 AI 봇의 매매 실행
     */
    private void executeTradeForBot(User bot, double sentimentScore, TechnicalAnalysisResult technical) {
        log.info("🤖 AI 봇 [{}] 매매 분석 시작", bot.getNickname());
        
        try {
            // 1. 봇의 계좌 조회
            Account account = accountRepository.findByUserIdAndType(
                    bot.getUserId(), AI_BOT_ACCOUNT_TYPE
            ).orElse(null);
            
            if (account == null) {
                log.warn("⚠️ AI 봇 [{}]의 Type 2 계좌를 찾을 수 없음", bot.getNickname());
                return;
            }
            
            // 2. 봇 성향별 매매 신호 계산
            TradingDecision decision = calculateTradingDecision(bot, sentimentScore, technical, account);
            
            // 3. 매매 실행
            if (decision.getAction() != TradeAction.HOLD) {
                executeTrade(bot, account, decision);
            } else {
                log.info("📊 AI 봇 [{}]: 현재 관망 (통합점수: {:.3f})", 
                        bot.getNickname(), decision.getScore());
            }
            
        } catch (Exception e) {
            log.error("🔥 AI 봇 [{}] 매매 실행 실패", bot.getNickname(), e);
        }
    }
    
    /**
     * 봇 성향별 매매 신호 계산 (단순화된 전략)
     */
    private TradingDecision calculateTradingDecision(User bot, double sentiment, 
                                                   TechnicalAnalysisResult technical, Account account) {
        
        String nickname = bot.getNickname();
        if (nickname == null) nickname = "균형이";
        
        // 기술적 분석 점수 계산 (-1.0 ~ 1.0)
        double technicalScore = calculateTechnicalScore(technical);
        
        // 봇별 단순화된 전략
        TradeAction action = TradeAction.HOLD;
        double finalScore = 0.0;
        String strategy = "";
        
        switch (nickname) {
            case "화끈이": // 뉴스 감정분석 우선, 공격적 매매
                finalScore = sentiment * 0.7 + technicalScore * 0.3;
                if (sentiment > 0.5 && technical != null && technical.getRsi() != null && technical.getRsi() < 40) {
                    action = TradeAction.BUY;
                    strategy = "강한매수신호: 뉴스긍정+" + "RSI과매도";
                } else if (sentiment < -0.3 && technical != null && technical.getRsi() != null && technical.getRsi() > 70) {
                    action = TradeAction.SELL;
                    strategy = "매도신호: 뉴스부정+RSI과매수";
                }
                break;
                
            case "적극이": // 균형있는 접근, MACD 활용
                finalScore = sentiment * 0.5 + technicalScore * 0.5;
                if (sentiment > 0.3 && technical != null && technical.getMacdLine() != null 
                    && technical.getMacdLine() > 0) {
                    action = TradeAction.BUY;
                    strategy = "매수신호: 뉴스중립긍정+MACD상승";
                } else if (sentiment < -0.2 && technical != null && technical.getMacdLine() != null 
                           && technical.getMacdLine() < 0) {
                    action = TradeAction.SELL;
                    strategy = "매도신호: 뉴스부정+MACD하락";
                }
                break;
                
            case "균형이": // 기술적분석 우선, 이동평균 활용
                finalScore = sentiment * 0.4 + technicalScore * 0.6;
                if (sentiment > 0.1 && technical != null && technical.getMa20() != null 
                    && technical.getMa60() != null && technical.getMa20() > technical.getMa60()) {
                    action = TradeAction.BUY;
                    strategy = "매수신호: 뉴스중립+20일선>60일선";
                } else if (sentiment < -0.1 && technical != null && technical.getMa20() != null 
                           && technical.getMa60() != null && technical.getMa20() < technical.getMa60()) {
                    action = TradeAction.SELL;
                    strategy = "매도신호: 뉴스중립부정+20일선<60일선";
                }
                break;
                
            case "조심이": // 매우 보수적, 강한 신호에서만 매매
                finalScore = sentiment * 0.3 + technicalScore * 0.7;
                if (sentiment < -0.3 && technical != null && technical.getRsi() != null 
                    && technical.getRsi() > 70) {
                    action = TradeAction.SELL;
                    strategy = "보수매도: 뉴스강한부정+RSI과매수";
                }
                // 조심이는 매수하지 않고 위험할 때만 매도
                break;
                
            default: // 기본 전략
                finalScore = sentiment * 0.4 + technicalScore * 0.6;
                if (finalScore >= BUY_THRESHOLD) {
                    action = TradeAction.BUY;
                } else if (finalScore <= SELL_THRESHOLD) {
                    action = TradeAction.SELL;
                }
                break;
        }
        
        log.info("📊 AI 봇 [{}] 분석결과 - 감정:{:.3f}, 기술:{:.3f}, 통합:{:.3f} → {} ({})", 
                nickname, sentiment, technicalScore, finalScore, action, strategy);
        
        return new TradingDecision(action, finalScore, DEFAULT_QUANTITY);
    }
    
    /**
     * 기술적 분석 결과를 점수로 변환 (-1.0 ~ 1.0)
     */
    private double calculateTechnicalScore(TechnicalAnalysisResult technical) {
        if (technical == null) return 0.0;
        
        double score = 0.0;
        int indicators = 0;
        
        // RSI 점수 (0~100 → -1~1)
        if (technical.getRsi() != null) {
            double rsi = technical.getRsi();
            if (rsi < 30) {
                score += 0.8; // 과매도, 매수 신호
            } else if (rsi > 70) {
                score -= 0.8; // 과매수, 매도 신호
            } else {
                score += (50 - rsi) / 50.0; // 50 기준으로 정규화
            }
            indicators++;
        }
        
        // MACD 점수 (히스토그램 사용)
        if (technical.getHistogram() != null) {
            score += technical.getHistogram() > 0 ? 0.5 : -0.5;
            indicators++;
        }
        
        return indicators > 0 ? score / indicators : 0.0;
    }
    
    /**
     * 삼성전자 현재가 조회
     */
    private Integer getCurrentPrice() {
        try {
            // ticks 테이블에서 삼성전자 최신 가격 조회
            List<Object[]> latestTick = ticksRepository.findLatestTicksByStockCode(SAMSUNG_STOCK_CODE, 1);
            
            if (!latestTick.isEmpty()) {
                Object[] tick = latestTick.get(0);
                // close_price는 인덱스 4에 위치 (ts, open, high, low, close, volume)
                Integer currentPrice = ((Number) tick[4]).intValue();
                log.debug("📊 삼성전자 현재가: {}원", currentPrice);
                return currentPrice;
            }
            
            // ticks 데이터가 없으면 기본값 사용
            log.warn("⚠️ 삼성전자 실시간 가격 조회 실패, 기본값 사용");
            return 75000; // 기본값
            
        } catch (Exception e) {
            log.error("🔥 현재가 조회 실패", e);
            return 75000; // 기본값
        }
    }
    
    /**
     * 실제 매매 API 호출
     */
    private void executeTrade(User bot, Account account, TradingDecision decision) {
        try {
            // 현재가 조회
            Integer currentPrice = getCurrentPrice();
            
            // 매매 요청 DTO 생성
            TradeRequest request = TradeRequest.builder()
                    .userId(bot.getUserId())
                    .stockCode(SAMSUNG_STOCK_CODE)
                    .tradeType(decision.getAction() == TradeAction.BUY ? TradeType.BUY : TradeType.SELL)
                    .quantity(decision.getQuantity())
                    .price(currentPrice) // 실시간 현재가 사용
                    .accountType(AI_BOT_ACCOUNT_TYPE)
                    .build();
            
            // API 엔드포인트 결정
            String endpoint = decision.getAction() == TradeAction.BUY ? "/api/stocks/buy" : "/api/stocks/sell";
            String url = "http://localhost:" + serverPort + endpoint;
            
            // HTTP 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            
            // API 호출
            HttpEntity<TradeRequest> entity = new HttpEntity<>(request, headers);
            ResponseEntity<Object> response = restTemplate.exchange(url, HttpMethod.POST, entity, Object.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("✅ AI 봇 [{}] {} 성공: {}주 (점수: {:.3f})", 
                        bot.getNickname(), 
                        decision.getAction() == TradeAction.BUY ? "매수" : "매도",
                        decision.getQuantity(),
                        decision.getScore());
            } else {
                log.warn("⚠️ AI 봇 [{}] {} 실패: HTTP {}", 
                        bot.getNickname(), 
                        decision.getAction() == TradeAction.BUY ? "매수" : "매도",
                        response.getStatusCode());
            }
            
        } catch (Exception e) {
            log.error("🔥 AI 봇 [{}] API 호출 실패", bot.getNickname(), e);
        }
    }
    
    // ======================== 내부 클래스 ========================
    
    /**
     * 매매 결정 결과
     */
    private static class TradingDecision {
        private final TradeAction action;
        private final double score;
        private final int quantity;
        
        public TradingDecision(TradeAction action, double score, int quantity) {
            this.action = action;
            this.score = score;
            this.quantity = quantity;
        }
        
        public TradeAction getAction() { return action; }
        public double getScore() { return score; }
        public int getQuantity() { return quantity; }
    }
    
    /**
     * 매매 액션
     */
    private enum TradeAction {
        BUY, SELL, HOLD
    }
}