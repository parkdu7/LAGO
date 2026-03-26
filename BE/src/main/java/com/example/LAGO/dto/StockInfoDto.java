package com.example.LAGO.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StockInfoDto {
    private Integer stockInfoId;
    private String code;
    private String name;
    private String market;
    private Integer currentPrice;
    private Integer openPrice;
    private Integer highPrice;
    private Integer lowPrice;
    private Integer closePrice;
    private Double priceChangeRate;
    private Long volume;

    public StockInfoDto() {}

    public StockInfoDto(com.example.LAGO.domain.StockInfo stockInfo) {
        this.stockInfoId = stockInfo.getStockInfoId();
        this.code = stockInfo.getCode();
        this.name = stockInfo.getName();
        this.market = stockInfo.getMarket();
        this.currentPrice = stockInfo.getCurrentPrice();
        this.openPrice = stockInfo.getOpenPrice();
        this.highPrice = stockInfo.getHighPrice();
        this.lowPrice = stockInfo.getLowPrice();
        this.closePrice = stockInfo.getClosePrice();
        this.priceChangeRate = stockInfo.getFluctuationRate() != null ? 
            ((stockInfo.getCurrentPrice() - stockInfo.getClosePrice()) / (double)stockInfo.getClosePrice() * 100) : null;
        this.volume = stockInfo.getVolume();
    }
}
