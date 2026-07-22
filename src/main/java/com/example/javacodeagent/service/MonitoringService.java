package com.example.javacodeagent.service;

import com.example.javacodeagent.model.WatchlistStock;
import com.example.javacodeagent.repository.WatchlistRepository;
import com.example.javacodeagent.util.SeleniumDriver;
import com.example.javacodeagent.vo.StockKLineVO;
import com.example.javacodeagent.vo.StockQuoteVO;
import com.example.javacodeagent.vo.StockIndicatorVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 自选股 K 线监控服务
 * 每 30 分钟轮询一次，异常时通过 SSE 推送预警
 */
@Service
public class MonitoringService {

    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private static final int MAX_STOCKS = 20;
    private static final int SLEEP_MIN = 800;
    private static final int SLEEP_MAX = 1500;

    // 预警阈值
    private static final double CHANGE_ALERT_PCT = 3.0;       // 涨跌幅预警
    private static final double VOLUME_SPIKE_RATIO = 2.5;      // 成交量放大倍数
    private static final int CHANGE_SPEED_ALERT = 200;         // 涨速阈值（百分比*100）

    private final SeleniumDriver seleniumDriver;
    private final WatchlistRepository watchlistRepository;
    private final StockMarketService stockMarketService;
    private final StockIndicatorService indicatorService;

    /** 预警列表，线程安全 */
    private final List<AlertInfo> alerts = new CopyOnWriteArrayList<>();
    private final AtomicLong alertIdSeq = new AtomicLong(0);

    public MonitoringService(SeleniumDriver seleniumDriver,
                             WatchlistRepository watchlistRepository,
                             StockMarketService stockMarketService,
                             StockIndicatorService indicatorService) {
        this.seleniumDriver = seleniumDriver;
        this.watchlistRepository = watchlistRepository;
        this.stockMarketService = stockMarketService;
        this.indicatorService = indicatorService;
    }

    /**
     * 每 30 分钟轮询一次（仅在交易时段：9:30-11:30, 13:00-15:00）
     */
    @Scheduled(fixedRate = 1800000) // 30 分钟 = 1800000ms
    public void monitorCycle() {
        LocalTime now = LocalTime.now();
        // 非交易时段跳过
        if (!isTradingTime(now)) {
            log.debug("非交易时段，跳过监控轮询");
            return;
        }

        log.info("=== 开始监控轮询 ===");

        // 1. 健康检查
        if (!seleniumDriver.isHealthy()) {
            log.warn("Driver 不健康，尝试重启...");
            seleniumDriver.restart();
        }

        // 2. 获取所有自选股
        List<WatchlistStock> stocks;
        try {
            stocks = watchlistRepository.findAll();
        } catch (Exception e) {
            log.error("获取自选股列表失败: {}", e.getMessage());
            return;
        }

        if (stocks.size() > MAX_STOCKS) {
            stocks = stocks.subList(0, MAX_STOCKS);
        }
        if (stocks.isEmpty()) {
            log.info("自选股为空，跳过");
            return;
        }

        // 3. 逐只检查
        for (int i = 0; i < stocks.size(); i++) {
            WatchlistStock stock = stocks.get(i);
            String code = normalizeCode(stock.getStockCode());
            String name = stock.getStockName();
            if (code == null || name == null) continue;

            log.info("检查 [{}/{}] {} ({})", i + 1, stocks.size(), name, code);

            try {
                checkStockAlerts(code, name);
            } catch (Exception e) {
                log.error("检查 {} 异常: {}", name, e.getMessage());
            }

            // 股票之间随机休眠 800~1500ms
            if (i < stocks.size() - 1) {
                sleepRandom();
            }
        }

        // 4. 轮询后健康检查
        if (!seleniumDriver.isHealthy()) {
            log.warn("监控轮询后 Driver 异常，重启...");
            seleniumDriver.restart();
        }

        log.info("=== 监控轮询完成，当前预警数: {} ===", alerts.size());
    }

    /**
     * 检查单只股票的异常信号
     */
    private void checkStockAlerts(String secid, String stockName) {
        // A. 通过 HTTP 获取行情（快速、稳定）
        StockQuoteVO quote = stockMarketService.getQuote(secid);

        // B. 通过 HTTP 获取 K 线数据
        List<StockKLineVO> klineList = stockMarketService.getDailyKLine(secid, 30);

        // C. 计算指标
        StockIndicatorVO indicator = null;
        if (klineList.size() >= 30) {
            indicator = indicatorService.calculateAll(klineList);
            indicator.setMaList(stockMarketService.calculateMA(klineList));
        }

        String name = quote != null ? quote.getName() : stockName;
        List<String> currentAlerts = new ArrayList<>();

        // 1. 涨跌幅异常
        if (quote != null && quote.getChange() != null) {
            double change = quote.getChange();
            if (Math.abs(change) >= CHANGE_ALERT_PCT) {
                String msg = String.format("%s 涨跌幅异常: %.2f%%", name, change);
                currentAlerts.add(msg);
            }
        }

        // 2. 成交量异常放大
        if (indicator != null && quote != null && quote.getVolume() != null) {
            long vol = quote.getVolume();
            Long avg5 = indicator.getAvgVolume5();
            if (avg5 != null && avg5 > 0 && vol > avg5 * VOLUME_SPIKE_RATIO) {
                String msg = String.format("%s 成交量异常放大: %d 手 (5日均量: %d 手)", name, vol, avg5);
                currentAlerts.add(msg);
            }
        }

        // 3. MACD 信号
        if (indicator != null && indicator.getMacdSignal() != null) {
            if ("golden_cross".equals(indicator.getMacdSignal())) {
                currentAlerts.add(name + " MACD 金叉信号");
            } else if ("death_cross".equals(indicator.getMacdSignal())) {
                currentAlerts.add(name + " MACD 死叉信号");
            }
        }

        // 4. RSI 超买/超卖
        if (indicator != null && indicator.getRsi6() != null) {
            double rsi = indicator.getRsi6();
            if (rsi > 75) {
                currentAlerts.add(String.format("%s RSI超买: %.1f", name, rsi));
            } else if (rsi < 25) {
                currentAlerts.add(String.format("%s RSI超卖: %.1f", name, rsi));
            }
        }

        // 5. 股价接近布林带上下轨
        if (indicator != null && quote != null && quote.getPrice() != null) {
            double price = quote.getPrice();
            Double upper = indicator.getBollingerUpper();
            Double lower = indicator.getBollingerLower();
            if (upper != null && price >= upper * 0.995) {
                currentAlerts.add(String.format("%s 触及布林上轨 (%.2f)", name, price));
            }
            if (lower != null && price <= lower * 1.005) {
                currentAlerts.add(String.format("%s 触及布林下轨 (%.2f)", name, price));
            }
        }

        // 生成预警事件
        if (!currentAlerts.isEmpty()) {
            AlertInfo alert = new AlertInfo(
                alertIdSeq.incrementAndGet(),
                secid, name,
                String.join("; ", currentAlerts),
                System.currentTimeMillis()
            );
            alerts.add(alert);
            log.warn("🚨 预警: {} - {}", name, alert.getMessage());

            // 最多保留 100 条预警
            while (alerts.size() > 100) {
                alerts.remove(0);
            }
        }
    }

    // ---- 公开方法 ----

    /** 获取当前预警列表 */
    public List<AlertInfo> getAlerts() {
        return new ArrayList<>(alerts);
    }

    /** 获取最近 N 条预警 */
    public List<AlertInfo> getRecentAlerts(int limit) {
        int size = alerts.size();
        if (size == 0) return List.of();
        int from = Math.max(0, size - limit);
        return new ArrayList<>(alerts.subList(from, size));
    }

    /** 清除预警 */
    public void clearAlerts() {
        alerts.clear();
    }

    // ---- 内部 ----

    private boolean isTradingTime(LocalTime t) {
        int h = t.getHour(), m = t.getMinute();
        int minute = h * 60 + m;
        // 9:30-11:30 (570-690) 或 13:00-15:00 (780-900)
        return (minute >= 570 && minute <= 690) || (minute >= 780 && minute <= 900);
    }

    private String normalizeCode(String code) {
        if (code == null || code.isBlank()) return null;
        if (code.contains(".")) return code;
        if (code.matches("\\d{6}")) {
            return code.startsWith("6") ? "1." + code : "0." + code;
        }
        return code;
    }

    private void sleepRandom() {
        try {
            int delay = SLEEP_MIN + new Random().nextInt(SLEEP_MAX - SLEEP_MIN + 1);
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 预警值对象 ----

    public static class AlertInfo {
        private final long id;
        private final String stockCode;
        private final String stockName;
        private final String message;
        private final long timestamp;

        public AlertInfo(long id, String stockCode, String stockName, String message, long timestamp) {
            this.id = id;
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.message = message;
            this.timestamp = timestamp;
        }

        public long getId() { return id; }
        public String getStockCode() { return stockCode; }
        public String getStockName() { return stockName; }
        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }

        public String getTimeStr() {
            java.time.Instant instant = java.time.Instant.ofEpochMilli(timestamp);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault());
            return ldt.toLocalTime().toString().substring(0, 8);
        }
    }
}
