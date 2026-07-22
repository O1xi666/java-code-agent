package com.example.javacodeagent.vo;

import java.io.Serializable;

/**
 * K 线数据 VO
 */
public class StockKLineVO implements Serializable {

    private String date;   // 日期
    private Double open;   // 开盘价
    private Double high;   // 最高价
    private Double low;    // 最低价
    private Double close;  // 收盘价
    private Long volume;   // 成交量

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
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

    public Double getClose() {
        return close;
    }

    public void setClose(Double close) {
        this.close = close;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    @Override
    public String toString() {
        return "StockKLineVO{" +
                "date='" + date + '\'' +
                ", close=" + close +
                '}';
    }
}
