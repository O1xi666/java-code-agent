package com.example.javacodeagent.vo;

import java.io.Serializable;

/**
 * 股票财务数据 VO
 */
public class StockFinancialVO implements Serializable {

    private String year;           // 年份
    private String reportType;     // 报表类型（年报/半年报/季报）
    private Double revenue;        // 营业收入
    private Double profit;         // 净利润
    private Double grossMargin;    // 毛利率
    private Double netMargin;      // 净利率
    private Double roe;            // ROE
    private Double pe;             // 市盈率
    private Double pb;             // 市净率
    private Double debtRatio;      // 资产负债率
    private Double assetTurnover;  // 资产周转率
    private Double assetLiability; // 资产负债

    public String getYear() {
        return year;
    }

    public void setYear(String year) {
        this.year = year;
    }

    public String getReportType() {
        return reportType;
    }

    public void setReportType(String reportType) {
        this.reportType = reportType;
    }

    public Double getRevenue() {
        return revenue;
    }

    public void setRevenue(Double revenue) {
        this.revenue = revenue;
    }

    public Double getProfit() {
        return profit;
    }

    public void setProfit(Double profit) {
        this.profit = profit;
    }

    public Double getGrossMargin() {
        return grossMargin;
    }

    public void setGrossMargin(Double grossMargin) {
        this.grossMargin = grossMargin;
    }

    public Double getNetMargin() {
        return netMargin;
    }

    public void setNetMargin(Double netMargin) {
        this.netMargin = netMargin;
    }

    public Double getRoE() {
        return roe;
    }

    public void setRoE(Double roe) {
        this.roe = roe;
    }

    public Double getPe() {
        return pe;
    }

    public void setPe(Double pe) {
        this.pe = pe;
    }

    public Double getPb() {
        return pb;
    }

    public void setPb(Double pb) {
        this.pb = pb;
    }

    public Double getDebtRatio() {
        return debtRatio;
    }

    public void setDebtRatio(Double debtRatio) {
        this.debtRatio = debtRatio;
    }

    public Double getAssetTurnover() {
        return assetTurnover;
    }

    public void setAssetTurnover(Double assetTurnover) {
        this.assetTurnover = assetTurnover;
    }

    public Double getAssetLiability() {
        return assetLiability;
    }

    public void setAssetLiability(Double assetLiability) {
        this.assetLiability = assetLiability;
    }

    @Override
    public String toString() {
        return "StockFinancialVO{" +
                "year='" + year + '\'' +
                ", revenue=" + revenue +
                ", profit=" + profit +
                '}';
    }
}
