package com.example.javacodeagent.service;

import com.example.javacodeagent.model.WatchlistStock;
import com.example.javacodeagent.repository.WatchlistRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class WatchlistService {

    private final WatchlistRepository repository;

    public WatchlistService(WatchlistRepository repository) {
        this.repository = repository;
    }

    public WatchlistStock add(String stockCode, String stockName, String note) {
        if (repository.existsByStockCode(stockCode)) {
            throw new IllegalArgumentException("自选股已存在: " + stockCode);
        }
        WatchlistStock stock = new WatchlistStock();
        stock.setStockCode(stockCode);
        stock.setStockName(stockName);
        stock.setNote(note);
        return repository.save(stock);
    }

    public List<WatchlistStock> listAll() {
        return repository.findAll();
    }

    public Optional<WatchlistStock> getById(Long id) {
        return repository.findById(id);
    }

    public WatchlistStock update(Long id, String stockCode, String stockName, String note) {
        WatchlistStock stock = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("自选股不存在: " + id));
        if (stockCode != null) stock.setStockCode(stockCode);
        if (stockName != null) stock.setStockName(stockName);
        if (note != null) stock.setNote(note);
        return repository.save(stock);
    }

    public void delete(Long id) {
        if (!repository.existsById(id)) {
            throw new IllegalArgumentException("自选股不存在: " + id);
        }
        repository.deleteById(id);
    }
}