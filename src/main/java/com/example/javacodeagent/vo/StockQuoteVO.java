package com.example.javacodeagent.vo;

import java.io.Serializable;

/**
 * 股票行情数据 VO
 */
public class StockQuoteVO implements Serializable {

    private String code;      // 股票代码
    private String name;      // 股票名称
    private Double price;     // 最新价
    private Double open;      // 开盘价
    private Double high;      // 最高价
    private Double low;       // 最低价
    private Double change;    // 涨跌幅（百分比）
    private Long volume;      // 成交量（手）
    private Double turnover;  // 成交额
    private Double pe;        // 市盈率
    private Double marketCap; // 总市值
    private Double turnoverRate; // 换手率
    private Double changeSpeed; // 涨速

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Double getOpen() {
        return open;
    }

    public void setOpen(Double open) {
        this.open = open;
    }

    public Double getHigh() {
        return high;
    }

    public void setHigh(Double high) {
        this.high = high;
    }

    public Double getLow() {
        return low;
    }

    public void setLow(Double low) {
        this.low = low;
    }

    public Double getChange() {
        return change;
    }

    public void setChange(Double change) {
        this.change = change;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public Double getTurnover() {
        return turnover;
    }

    public void setTurnover(Double turnover) {
        this.turnover = turnover;
    }

    public Double getPe() {
        return pe;
    }

    public void setPe(Double pe) {
        this.pe = pe;
    }

    public Double getMarketCap() {
        return marketCap;
    }

    public void setMarketCap(Double marketCap) {
        this.marketCap = marketCap;
    }

    public Double getTurnoverRate() {
        return turnoverRate;
    }

    public void setTurnoverRate(Double turnoverRate) {
        this.turnoverRate = turnoverRate;
    }

    public Double getChangeSpeed() {
        return changeSpeed;
    }

    public void setChangeSpeed(Double changeSpeed) {
        this.changeSpeed = changeSpeed;
    }

    @Override
    public String toString() {
        return "StockQuoteVO{" +
                "code='" + code + '\'' +
                ", name='" + name + '\'' +
                ", price=" + price +
                ", change=" + change +
                '}';
    }
}
