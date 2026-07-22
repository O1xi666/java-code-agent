package com.example.javacodeagent.repository;

import com.example.javacodeagent.model.WatchlistStock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WatchlistRepository extends JpaRepository<WatchlistStock, Long> {
    Optional<WatchlistStock> findByStockCode(String stockCode);
    boolean existsByStockCode(String stockCode);
}