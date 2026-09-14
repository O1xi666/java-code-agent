package com.example.javacodeagent.repository;

import com.example.javacodeagent.model.MemoryCard;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 记忆卡片的数据访问入口。
 *
 * <p>方法名即查询语义,由 Spring Data JPA 自动生成实现,不需要手写 SQL。
 */
public interface MemoryCardRepository extends JpaRepository<MemoryCard, String> {

    /** 取某用户全部卡片(有效 + 归档),用于差量同步 */
    List<MemoryCard> findByUserId(String userId);

    /** 取某用户指定状态的卡片,按创建时间升序 */
    List<MemoryCard> findByUserIdAndStatusOrderByCreatedAtAsc(String userId, String status);

    /** 取某用户非指定状态的卡片(归档区:STALE + ARCHIVED) */
    List<MemoryCard> findByUserIdAndStatusNotOrderByCreatedAtAsc(String userId, String status);

    /** 供诊断接口使用:某用户某状态的卡片条数 */
    long countByUserIdAndStatus(String userId, String status);
}
