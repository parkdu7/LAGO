package com.example.LAGO.repository;

import com.example.LAGO.domain.StockInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Stock Repository (StockInfo 엔티티 기반)
 * 가격/거래량 관련 쿼리 전용
 */
@Repository
public interface StockRepository extends JpaRepository<StockInfo, Integer> {

    Optional<StockInfo> findByCode(String code);

    List<StockInfo> findByMarket(String market);

    @Query("SELECT s FROM StockInfo s WHERE s.code = :code ORDER BY s.updatedAt DESC")
    List<StockInfo> findByCodeOrderByDateDesc(@Param("code") String code);

    List<StockInfo> findBySector(String sector);

    @Query("SELECT s FROM StockInfo s WHERE s.currentPrice BETWEEN :minPrice AND :maxPrice")
    List<StockInfo> findByPriceRange(@Param("minPrice") Integer minPrice, @Param("maxPrice") Integer maxPrice);

    @Query("SELECT s FROM StockInfo s ORDER BY s.volume DESC")
    List<StockInfo> findTopByVolumeDesc(@Param("limit") int limit);

    @Query("SELECT s FROM StockInfo s WHERE s.fluctuationRate BETWEEN :minRate AND :maxRate ORDER BY s.fluctuationRate DESC")
    List<StockInfo> findByFluctuationRate(@Param("minRate") Float minRate, @Param("maxRate") Float maxRate);

    @Query("SELECT s FROM StockInfo s ORDER BY s.volume DESC LIMIT 50")
    List<StockInfo> findTop50ByOrderByVolumeDesc();

    @Query("SELECT s FROM StockInfo s WHERE s.code = :code ORDER BY s.updatedAt DESC LIMIT 1")
    Optional<StockInfo> findTopByCodeOrderByUpdatedAtDesc(@Param("code") String code);
}
