package com.example.javacodeagent.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 技术指标数据 VO
 */
public class StockIndicatorVO implements Serializable {

    // MACD 指标
    private List<Double> macdLine;      // MACD 快线（DIF）
    private List<Double> signalLine;    // MACD 慢线（DEA）
    private List<Double> histogram;     // MACD 柱状图（BAR）

    // RSI 指标
    private Double rsi6;    // 6 日 RSI
    private Double rsi12;   // 12 日 RSI
    private Double rsi14;   // 14 日 RSI
    private Double rsi24;   // 24 日 RSI

    // KDJ 指标
    private Double kValue;  // K 值
    private Double dValue;  // D 值
    private Double jValue;  // J 值

    // 均线
    private List<Double> maList;  // [MA5, MA10, MA20, MA60]

    // 布林带
    private Double bollingerUpper;  // 上轨
    private Double bollingerMiddle; // 中轨（MA20）
    private Double bollingerLower;  // 下轨

    // 成交量
    private Long avgVolume5;   // 5 日均量
    private Long avgVolume10;  // 10 日均量

    // 综合判断
    private String trend;         // 趋势方向（bullish/bearish/neutral）
    private String momentum;      // 动量（overbought/oversold/neutral）
    private String macdSignal;    // MACD 信号（golden_cross/death_cross/none）

    public List<Double> getMacdLine() {
        return macdLine;
    }

    public void setMacdLine(List<Double> macdLine) {
        this.macdLine = macdLine;
    }

    public List<Double> getSignalLine() {
        return signalLine;
    }

    public void setSignalLine(List<Double> signalLine) {
        this.signalLine = signalLine;
    }

    public List<Double> getHistogram() {
        return histogram;
    }

    public void setHistogram(List<Double> histogram) {
        this.histogram = histogram;
    }

    public Double getRsi6() {
        return rsi6;
    }

    public void setRsi6(Double rsi6) {
        this.rsi6 = rsi6;
    }

    public Double getRsi12() {
        return rsi12;
    }

    public void setRsi12(Double rsi12) {
        this.rsi12 = rsi12;
    }

    public Double getRsi14() {
        return rsi14;
    }

    public void setRsi14(Double rsi14) {
        this.rsi14 = rsi14;
    }

    public Double getRsi24() {
        return rsi24;
    }

    public void setRsi24(Double rsi24) {
        this.rsi24 = rsi24;
    }

    public Double getkValue() {
        return kValue;
    }

    public void setkValue(Double kValue) {
        this.kValue = kValue;
    }

    public Double getdValue() {
        return dValue;
    }

    public void setdValue(Double dValue) {
        this.dValue = dValue;
    }

    public Double getjValue() {
        return jValue;
    }

    public void setjValue(Double jValue) {
        this.jValue = jValue;
    }

    public List<Double> getMaList() {
        return maList;
    }

    public void setMaList(List<Double> maList) {
        this.maList = maList;
    }

    public Double getBollingerUpper() {
        return bollingerUpper;
    }

    public void setBollingerUpper(Double bollingerUpper) {
        this.bollingerUpper = bollingerUpper;
    }

    public Double getBollingerMiddle() {
        return bollingerMiddle;
    }

    public void setBollingerMiddle(Double bollingerMiddle) {
        this.bollingerMiddle = bollingerMiddle;
    }

    public Double getBollingerLower() {
        return bollingerLower;
    }

    public void setBollingerLower(Double bollingerLower) {
        this.bollingerLower = bollingerLower;
    }

    public Long getAvgVolume5() {
        return avgVolume5;
    }

    public void setAvgVolume5(Long avgVolume5) {
        this.avgVolume5 = avgVolume5;
    }

    public Long getAvgVolume10() {
        return avgVolume10;
    }

    public void setAvgVolume10(Long avgVolume10) {
        this.avgVolume10 = avgVolume10;
    }

    public String getTrend() {
        return trend;
    }

    public void setTrend(String trend) {
        this.trend = trend;
    }

    public String getMomentum() {
        return momentum;
    }

    public void setMomentum(String momentum) {
        this.momentum = momentum;
    }

    public String getMacdSignal() {
        return macdSignal;
    }

    public void setMacdSignal(String macdSignal) {
        this.macdSignal = macdSignal;
    }

    @Override
    public String toString() {
        return "StockIndicatorVO{" +
                "rsi6=" + rsi6 +
                ", macdSignal='" + macdSignal + '\'' +
                ", trend='" + trend + '\'' +
                '}';
    }
}