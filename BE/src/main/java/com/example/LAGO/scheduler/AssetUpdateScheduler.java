package com.example.LAGO.scheduler;

import com.example.LAGO.domain.Account;
import com.example.LAGO.repository.AccountRepository;
import com.example.LAGO.repository.MockTradeRepository;
import com.example.LAGO.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

/**
 * 계좌 총자산 업데이트 스케줄러
 * 3분마다 모든 계좌의 total_asset을 갱신
 * total_asset = balance + 보유주식평가금액
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetUpdateScheduler {

    private final AccountRepository accountRepository;
    private final MockTradeRepository mockTradeRepository;
    private final StockRepository stockRepository;
    private final DataSource dataSource;

    /**
     * 3분마다 모든 계좌의 총자산 업데이트
     * fixedRate = 3분 = 180000ms
     */
    @Scheduled(fixedRate = 180000) // 3분
    @Transactional
    public void updateAllAccountTotalAssets() {
        log.info("=== 계좌 총자산 일괄 업데이트 시작 ===");
        
        try {
            int updatedCount = updateAccountTotalAssetsWithSQL();
            log.info("=== 계좌 총자산 일괄 업데이트 완료: {} 건 ===", updatedCount);
        } catch (Exception e) {
            log.error("계좌 총자산 업데이트 중 오류 발생", e);
        }
    }

    /**
     * SQL로 모든 계좌의 총자산을 한 번에 업데이트
     * 성능 최적화를 위해 단일 쿼리 사용
     */
    private int updateAccountTotalAssetsWithSQL() throws Exception {
        String updateSql = """
            UPDATE accounts 
            SET 
                total_asset = balance + COALESCE(stock_values.total_stock_value, 0),
                profit = (balance + COALESCE(stock_values.total_stock_value, 0)) - 
                         CASE WHEN type = 0 THEN 1000000 
                              WHEN type = 1 THEN 10000000 
                              ELSE 1000000 END,
                profit_rate = CASE 
                    WHEN type = 0 THEN 
                        ((balance + COALESCE(stock_values.total_stock_value, 0)) - 1000000) * 100.0 / 1000000
                    WHEN type = 1 THEN 
                        ((balance + COALESCE(stock_values.total_stock_value, 0)) - 10000000) * 100.0 / 10000000
                    ELSE 0
                END
            FROM (
                SELECT
                    mt.account_id,
                    SUM(
                        CASE
                            WHEN mt.buy_sell = 'BUY' THEN mt.quantity * COALESCE(s.current_price, mt.price)
                            ELSE -mt.quantity * COALESCE(s.current_price, mt.price)
                        END
                    ) as total_stock_value
                FROM mock_trade mt
                LEFT JOIN stock_info s ON mt.stock_id = s.stock_info_id
                GROUP BY mt.account_id
                HAVING SUM(
                    CASE WHEN mt.buy_sell = 'BUY' THEN mt.quantity ELSE -mt.quantity END
                ) > 0
            ) as stock_values
            WHERE accounts.account_id = stock_values.account_id
            """;
        
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(updateSql)) {
            
            int updatedRows = stmt.executeUpdate();
            
            // 주식을 보유하지 않은 계좌들도 업데이트 (balance만으로 계산)
            String updateNoStockSql = """
                UPDATE accounts 
                SET 
                    total_asset = balance,
                    profit = balance - 
                             CASE WHEN type = 0 THEN 1000000 
                                  WHEN type = 1 THEN 10000000 
                                  ELSE 1000000 END,
                    profit_rate = CASE 
                        WHEN type = 0 THEN (balance - 1000000) * 100.0 / 1000000
                        WHEN type = 1 THEN (balance - 10000000) * 100.0 / 10000000
                        ELSE 0
                    END
                WHERE account_id NOT IN (
                    SELECT DISTINCT account_id FROM mock_trade
                )
                """;
            
            try (PreparedStatement noStockStmt = connection.prepareStatement(updateNoStockSql)) {
                int noStockUpdated = noStockStmt.executeUpdate();
                updatedRows += noStockUpdated;
            }
            
            return updatedRows;
            
        } catch (Exception e) {
            log.error("SQL 총자산 업데이트 실패", e);
            throw e;
        }
    }

    /**
     * 특정 계좌의 총자산 즉시 업데이트 (거래 후 호출용)
     */
    @Transactional
    public void updateAccountTotalAsset(Long accountId) {
        log.debug("계좌 총자산 즉시 업데이트: accountId={}", accountId);
        
        try {
            Account account = accountRepository.findById(accountId)
                    .orElseThrow(() -> new RuntimeException("계좌를 찾을 수 없습니다: " + accountId));

            // 현재 보유 주식 평가금액 계산
            Integer stockValue = calculateStockValue(accountId);
            
            // 총자산 = 보유현금 + 주식평가금액
            Integer totalAsset = account.getBalance() + stockValue;
            
            // 수익 및 수익률 계산
            Integer initialAmount = getInitialAmount(account.getType());
            Integer profit = totalAsset - initialAmount;
            Double profitRate = (profit * 100.0) / initialAmount;
            
            // 업데이트
            account.setTotalAsset(totalAsset);
            account.setProfit(profit);
            account.setProfitRate(profitRate);
            
            accountRepository.save(account);
            
            log.debug("계좌 총자산 업데이트 완료: accountId={}, totalAsset={}, profit={}, profitRate={}%", 
                     accountId, totalAsset, profit, profitRate);
                     
        } catch (Exception e) {
            log.error("계좌 총자산 즉시 업데이트 실패: accountId={}", accountId, e);
        }
    }

    /**
     * 특정 계좌의 보유 주식 평가금액 계산
     */
    private Integer calculateStockValue(Long accountId) {
        String sql = """
            SELECT SUM(
                holdings.current_quantity * COALESCE(s.current_price, 0)
            ) as total_value
            FROM (
                SELECT 
                    mt.stock_id,
                    SUM(CASE WHEN mt.buy_sell = 'BUY' THEN mt.quantity ELSE -mt.quantity END) as current_quantity
                FROM mock_trade mt
                WHERE mt.account_id = ?
                GROUP BY mt.stock_id
                HAVING SUM(CASE WHEN mt.buy_sell = 'BUY' THEN mt.quantity ELSE -mt.quantity END) > 0
            ) holdings
            LEFT JOIN stock_info s ON holdings.stock_id = s.stock_info_id
            """;
        
        try (Connection connection = dataSource.getConnection();
             PreparedStatement stmt = connection.prepareStatement(sql)) {
            
            stmt.setLong(1, accountId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Integer value = rs.getInt("total_value");
                    return rs.wasNull() ? 0 : value;
                }
            }
        } catch (Exception e) {
            log.error("주식 평가금액 계산 실패: accountId={}", accountId, e);
        }
        
        return 0;
    }

    /**
     * 계좌 타입별 초기 금액 반환
     */
    private Integer getInitialAmount(Integer type) {
        return switch (type) {
            case 0 -> 1000000;  // 모의투자: 100만원
            case 1 -> 10000000; // 역사챌린지: 1000만원
            default -> 1000000;
        };
    }
}