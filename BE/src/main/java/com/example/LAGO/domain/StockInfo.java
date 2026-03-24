package com.example.LAGO.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "stock_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stock_info_id")
    private Integer stockInfoId;

    @Column(name = "code", length = 20)
    private String code;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "market", length = 20)
    private String market;

    @Column(name = "sector")
    private String sector;

    @Column(name = "current_price")
    private Integer currentPrice;

    @Column(name = "open_price")
    private Integer openPrice;

    @Column(name = "high_price")
    private Integer highPrice;

    @Column(name = "low_price")
    private Integer lowPrice;

    @Column(name = "close_price")
    private Integer closePrice;

    @Column(name = "fluctuation_rate")
    private Float fluctuationRate;

    @Column(name = "volume")
    private Long volume;

    @Column(name = "market_cap")
    private Long marketCap;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    private String companyName;

    @Transient
    private String companyNameEn;

    @OneToMany(mappedBy = "stockInfo", fetch = FetchType.LAZY)
    private List<StockMinute> stockMinutes;

    public StockInfo(String stockCode, String name, String market) {
        this.code = stockCode;
        this.name = name;
        this.companyName = name;
        this.market = market;
    }

    public String getCompanyName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.companyName = name;
    }

    public void setCompanyName(String companyName) {
        this.name = companyName;
        this.companyName = companyName;
    }

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
