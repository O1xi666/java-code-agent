package com.example.javacodeagent.memory;

import com.example.javacodeagent.model.MemoryCard;
import com.example.javacodeagent.repository.MemoryCardRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/** {@link MemoryCardStore} 的 MySQL 实现。 */
@Component
public class JpaMemoryCardStore implements MemoryCardStore {

    private final MemoryCardRepository repository;

    public JpaMemoryCardStore(MemoryCardRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<MemoryCard> findAll(String userId) {
        return repository.findByUserId(userId);
    }

    @Override
    public List<MemoryCard> findByStatus(String userId, String status) {
        return repository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, status);
    }

    @Override
    public List<MemoryCard> findNotStatus(String userId, String status) {
        return repository.findByUserIdAndStatusNotOrderByCreatedAtAsc(userId, status);
    }

    @Override
    public void saveAll(List<MemoryCard> cards) {
        repository.saveAll(cards);
    }

    @Override
    public void deleteAll(List<MemoryCard> cards) {
        repository.deleteAll(cards);
    }
}
