package com.example.javacodeagent.service;

import com.example.javacodeagent.vo.StockIndicatorVO;
import com.example.javacodeagent.vo.StockKLineVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StockIndicatorService {

    private static final int PERIOD_12 = 12;
    private static final int PERIOD_26 = 26;
    private static final int PERIOD_9 = 9;
    private static final int RSI_PERIOD_14 = 14;
    private static final int RSI_PERIOD_6 = 6;
    private static final int RSI_PERIOD_12 = 12;
    private static final int RSI_PERIOD_24 = 24;
    private static final int KDJ_PERIOD = 9;

    public StockIndicatorVO calculateAll(List<StockKLineVO> klineList) {
        StockIndicatorVO vo = new StockIndicatorVO();
        int size = (klineList == null) ? 0 : klineList.size();
        if (size < 34) return vo;
        calculateMACD(klineList, vo);
        calculateRSI(klineList, vo);
        calculateKDJ(klineList, vo);
        calculateBollinger(klineList, vo);
        calculateVolumeMA(klineList, vo);
        generateSignals(vo);
        return vo;
    }

    // ══════════════════════════════════════════════════════════
    // MACD — 使用 SMA seed 加速收敛，避免 DEA 从 0 起步的偏差
    // ══════════════════════════════════════════════════════════

    private void calculateMACD(List<StockKLineVO> klineList, StockIndicatorVO vo) {
        int size = klineList.size();
        if (size < PERIOD_26 + PERIOD_9) return;

        // Step 1: 用前26天SMA初始化EMA种子（比首日收盘价准确）
        double sma26 = 0;
        for (int i = 0; i < PERIOD_26; i++) sma26 += klineList.get(i).getClose();
        sma26 /= PERIOD_26;
        double e12 = sma26, e26 = sma26;

        // Step 2: Warmup 26 期
        for (int i = 0; i < PERIOD_26; i++) {
            double c = klineList.get(i).getClose();
            e12 = (e12 * (PERIOD_12 - 1) + c * 2) / (PERIOD_12 + 1);
            e26 = (e26 * (PERIOD_26 - 1) + c * 2) / (PERIOD_26 + 1);
        }

        // Step 3: 收集前9个DIF值，用SMA初始化DEA种子
        double[] difWin = new double[PERIOD_9];
        for (int i = 0; i < PERIOD_9; i++) {
            double c = klineList.get(PERIOD_26 + i).getClose();
            e12 = (e12 * 11 + c * 2) / 13;
            e26 = (e26 * 25 + c * 2) / 27;
            difWin[i] = e12 - e26;
        }
        double deaSeed = 0;
        for (int i = 0; i < PERIOD_9; i++) deaSeed += difWin[i];
        deaSeed /= PERIOD_9;

        // Step 4: 正式迭代（DEA 用 SMA 种子，非 0）
        List<Double> difList = new ArrayList<>();
        List<Double> deaList = new ArrayList<>();
        List<Double> barList = new ArrayList<>();
        double dea = deaSeed;
        int startIdx = PERIOD_26 + PERIOD_9 - 1;
        for (int i = startIdx; i < size; i++) {
            double c = klineList.get(i).getClose();
            e12 = (e12 * 11 + c * 2) / 13;
            e26 = (e26 * 25 + c * 2) / 27;
            double dif = e12 - e26;
            dea = (dea * 8 + dif * 2) / 10;
            difList.add(Math.round(dif * 100.0) / 100.0);
            deaList.add(Math.round(dea * 100.0) / 100.0);
            barList.add(Math.round(2 * (dif - dea) * 100.0) / 100.0);
        }
        vo.setMacdLine(difList);
        vo.setSignalLine(deaList);
        vo.setHistogram(barList);

        if (difList.size() >= 2) {
            double pd = difList.get(difList.size() - 2);
            double cd = difList.get(difList.size() - 1);
            double cdea = deaList.get(deaList.size() - 1);
            if (pd < cdea && cd >= cdea) vo.setMacdSignal("golden_cross");
            else if (pd >= cdea && cd < cdea) vo.setMacdSignal("death_cross");
            else vo.setMacdSignal("none");
        }
    }

    // ══════════════════════════════════════════════════════════
    // RSI — 按实际周期分别计算（原bug：三个字段同值）
    // ══════════════════════════════════════════════════════════

    private void calculateRSI(List<StockKLineVO> klineList, StockIndicatorVO vo) {
        vo.setRsi6(computeRSI(klineList, RSI_PERIOD_6));
        vo.setRsi12(computeRSI(klineList, RSI_PERIOD_12));
        vo.setRsi14(computeRSI(klineList, RSI_PERIOD_14));
        vo.setRsi24(computeRSI(klineList, RSI_PERIOD_24));
    }

    private Double computeRSI(List<StockKLineVO> klineList, int period) {
        int size = klineList.size();
        if (size < period + 1) return null;
        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double d = klineList.get(i).getClose() - klineList.get(i - 1).getClose();
            if (d > 0) gain += d; else loss -= d;
        }
        gain /= period; loss /= period;
        double rsi = 50;
        for (int i = period + 1; i < size; i++) {
            double d = klineList.get(i).getClose() - klineList.get(i - 1).getClose();
            gain = (gain * (period - 1) + Math.max(d, 0)) / period;
            loss = (loss * (period - 1) + Math.max(-d, 0)) / period;
            if (loss == 0) { rsi = 100; } else { rsi = 100 - 100 / (1 + gain / loss); }
        }
        return Math.round(rsi * 100.0) / 100.0;
    }

    // ══════════════════════════════════════════════════════════
    // KDJ
    // ══════════════════════════════════════════════════════════

    private void calculateKDJ(List<StockKLineVO> klineList, StockIndicatorVO vo) {
        int size = klineList.size();
        if (size < KDJ_PERIOD) return;
        double k = 50, d = 50;
        for (int i = 0; i < size; i++) {
            int start = Math.max(0, i - KDJ_PERIOD + 1);
            double high = -1e9, low = 1e9;
            for (int j = start; j <= i; j++) {
                high = Math.max(high, klineList.get(j).getHigh());
                low = Math.min(low, klineList.get(j).getLow());
            }
            double close = klineList.get(i).getClose();
            double rsv = (high == low) ? 50 : (close - low) / (high - low) * 100;
            k = (k * 2 + rsv) / 3;
            d = (d * 2 + k) / 3;
        }
        vo.setkValue(Math.round(k * 100.0) / 100.0);
        vo.setdValue(Math.round(d * 100.0) / 100.0);
        vo.setjValue(Math.round((3 * k - 2 * d) * 100.0) / 100.0);
    }

    // ══════════════════════════════════════════════════════════
    // 布林带
    // ══════════════════════════════════════════════════════════

    private void calculateBollinger(List<StockKLineVO> klineList, StockIndicatorVO vo) {
        int size = klineList.size();
        if (size < 20) return;
        double sum = 0;
        for (int i = size - 20; i < size; i++) sum += klineList.get(i).getClose();
        double ma20 = sum / 20;
        double var = 0;
        for (int i = size - 20; i < size; i++) { double d = klineList.get(i).getClose() - ma20; var += d * d; }
        double std = Math.sqrt(var / 20);
        vo.setBollingerMiddle(Math.round(ma20 * 100.0) / 100.0);
        vo.setBollingerUpper(Math.round((ma20 + 2 * std) * 100.0) / 100.0);
        vo.setBollingerLower(Math.round((ma20 - 2 * std) * 100.0) / 100.0);
    }

    // ══════════════════════════════════════════════════════════
    // 均量
    // ══════════════════════════════════════════════════════════

    private void calculateVolumeMA(List<StockKLineVO> klineList, StockIndicatorVO vo) {
        int size = klineList.size();
        if (size < 10) return;
        long s5 = 0, s10 = 0;
        for (int i = 0; i < 10 && i < size; i++) {
            long v = klineList.get(size - 1 - i).getVolume();
            if (i < 5) s5 += v;
            s10 += v;
        }
        vo.setAvgVolume5(s5 / Math.min(5, size));
        vo.setAvgVolume10(s10 / Math.min(10, size));
    }

    // ══════════════════════════════════════════════════════════
    // 综合信号 — 趋势上下文感知（原bug：超买缺少趋势区分）
    // ══════════════════════════════════════════════════════════

    private void generateSignals(StockIndicatorVO vo) {
        List<Double> ma = vo.getMaList();
        if (ma != null && ma.size() >= 2) {
            double ma5 = ma.get(0);
            double ma20 = ma.get(Math.min(1, ma.size() - 1));
            if (ma5 > ma20 * 1.01) vo.setTrend("bullish");
            else if (ma5 < ma20 * 0.99) vo.setTrend("bearish");
            else vo.setTrend("neutral");
        }
        Double rsi = vo.getRsi6();
        Double k = vo.getkValue();
        String trend = vo.getTrend();
        boolean overbought = (rsi != null && rsi > 70) || (k != null && k > 80);
        boolean oversold = (rsi != null && rsi < 30) || (k != null && k < 20);
        if (overbought) {
            vo.setMomentum("bullish".equals(trend) ? "overbought_trend_up" : "overbought");
        } else if (oversold) {
            vo.setMomentum("bearish".equals(trend) ? "oversold_trend_down" : "oversold");
        } else {
            vo.setMomentum("neutral");
        }
    }
}