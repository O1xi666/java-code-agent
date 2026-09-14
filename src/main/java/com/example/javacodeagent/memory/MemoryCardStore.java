package com.example.javacodeagent.memory;

import com.example.javacodeagent.model.MemoryCard;

import java.util.List;

/**
 * 记忆卡片的存储抽象。
 *
 * <p>生产实现是 {@link JpaMemoryCardStore}(MySQL + Spring Data JPA),
 * 单元测试用内存实现,保证记忆治理逻辑可以脱离数据库独立验证。
 */
public interface MemoryCardStore {

    /** 该用户的全部卡片(有效 + 归档),用于差量同步 */
    List<MemoryCard> findAll(String userId);

    /** 该用户指定状态的卡片,按创建时间升序 */
    List<MemoryCard> findByStatus(String userId, String status);

    /** 该用户非指定状态的卡片(归档区) */
    List<MemoryCard> findNotStatus(String userId, String status);

    void saveAll(List<MemoryCard> cards);

    void deleteAll(List<MemoryCard> cards);
}
