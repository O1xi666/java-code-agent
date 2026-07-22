package com.example.javacodeagent.controller;

import com.example.javacodeagent.service.MonitoringService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 监控预警 SSE 推送
 */
@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final MonitoringService monitoringService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MonitorController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    /**
     * SSE 端点：客户端订阅后持续接收最新预警
     */
    @GetMapping(value = "/alerts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamAlerts() {
        return Flux.interval(Duration.ofSeconds(5))
                .map(tick -> {
                    List<MonitoringService.AlertInfo> alerts = monitoringService.getRecentAlerts(20);
                    if (alerts.isEmpty()) {
                        return "data: {\"type\":\"heartbeat\"}\n\n";
                    }
                    try {
                        String json = objectMapper.writeValueAsString(Map.of(
                            "type", "alerts",
                            "count", alerts.size(),
                            "list", alerts.stream().map(a -> Map.of(
                                "id", a.getId(),
                                "stockCode", a.getStockCode(),
                                "stockName", a.getStockName(),
                                "message", a.getMessage(),
                                "time", a.getTimeStr()
                            )).toList()
                        ));
                        return "data: " + json + "\n\n";
                    } catch (JsonProcessingException e) {
                        return "data: {\"type\":\"error\"}\n\n";
                    }
                })
                .doOnError(e -> { /* SSE 连接断开时静默处理 */ });
    }

    /**
     * 获取当前预警列表（HTTP 轮询备用）
     */
    @GetMapping("/alerts")
    public Map<String, Object> getAlerts() {
        List<MonitoringService.AlertInfo> alerts = monitoringService.getRecentAlerts(50);
        return Map.of(
            "success", true,
            "count", alerts.size(),
            "list", alerts.stream().map(a -> Map.of(
                "id", a.getId(),
                "stockCode", a.getStockCode(),
                "stockName", a.getStockName(),
                "message", a.getMessage(),
                "time", a.getTimeStr()
            )).toList()
        );
    }

    /**
     * 清除预警
     */
    @DeleteMapping("/alerts")
    public Map<String, Object> clearAlerts() {
        monitoringService.clearAlerts();
        return Map.of("success", true, "message", "预警已清除");
    }
}
