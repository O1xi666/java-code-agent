package com.example.javacodeagent.memory;

import com.example.javacodeagent.model.MemoryCard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的内存实现:语义与 memory_card 表一致——按 userId 隔离、按状态过滤、按创建时间升序。
 * 用内存 Map 模拟表,是为了让记忆治理逻辑不依赖数据库也能回归。
 */
class InMemoryMemoryCardStore implements MemoryCardStore {

    private final Map<String, MemoryCard> rows = new LinkedHashMap<>();

    @Override
    public List<MemoryCard> findAll(String userId) {
        return byUser(userId);
    }

    @Override
    public List<MemoryCard> findByStatus(String userId, String status) {
        return byUser(userId).stream().filter(card -> status.equals(card.getStatus())).toList();
    }

    @Override
    public List<MemoryCard> findNotStatus(String userId, String status) {
        return byUser(userId).stream().filter(card -> !status.equals(card.getStatus())).toList();
    }

    @Override
    public void saveAll(List<MemoryCard> cards) {
        for (MemoryCard card : cards) {
            rows.put(card.getId(), card);
        }
    }

    @Override
    public void deleteAll(List<MemoryCard> cards) {
        for (MemoryCard card : cards) {
            rows.remove(card.getId());
        }
    }

    private List<MemoryCard> byUser(String userId) {
        List<MemoryCard> list = new ArrayList<>();
        for (MemoryCard card : rows.values()) {
            if (userId.equals(card.getUserId())) {
                list.add(card);
            }
        }
        list.sort(Comparator.comparingLong(MemoryCard::getCreatedAt));
        return list;
    }
}
