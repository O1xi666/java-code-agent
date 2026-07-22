package com.example.javacodeagent.controller;

import com.example.javacodeagent.model.WatchlistStock;
import com.example.javacodeagent.service.WatchlistService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        String stockCode = body.getOrDefault("stockCode", "").trim();
        String stockName = body.getOrDefault("stockName", "").trim();
        String note = body.getOrDefault("note", "").trim();
        if (stockCode.isBlank() || stockName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "stockCode 和 stockName 不能为空"));
        }
        try {
            WatchlistStock stock = watchlistService.add(stockCode, stockName, note);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("id", stock.getId());
            resp.put("stockCode", stock.getStockCode());
            resp.put("stockName", stock.getStockName());
            resp.put("note", stock.getNote());
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> list() {
        List<Map<String, Object>> items = watchlistService.listAll().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("stockCode", s.getStockCode());
            m.put("stockName", s.getStockName());
            m.put("note", s.getNote() != null ? s.getNote() : "");
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : "");
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("success", true, "count", items.size(), "list", items));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            WatchlistStock stock = watchlistService.update(id,
                    body.get("stockCode"), body.get("stockName"), body.get("note"));
            return ResponseEntity.ok(Map.of("success", true, "id", stock.getId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable Long id) {
        try {
            watchlistService.delete(id);
            return ResponseEntity.ok(Map.of("success", true, "message", "已删除"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }
}