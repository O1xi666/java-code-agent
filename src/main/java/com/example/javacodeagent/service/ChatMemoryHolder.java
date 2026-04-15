package com.example.javacodeagent.service;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆管理器
 * 作用：同一个sessionId永远用同一个记忆，让AI真正记住上下文
 */
@Component
public class ChatMemoryHolder {

    // 线程安全的记忆仓库：key=sessionId，value=当前对话的记忆
    private final Map<String, ChatMemory> chatMemoryMap = new ConcurrentHashMap<>();

    // 最多保留15条对话历史（足够用）
    private static final int MAX_MESSAGE_COUNT = 15;

    /**
     * 获取当前会话的记忆（没有就创建，有就直接复用）
     */
    public ChatMemory getChatMemory(String sessionId) {
        return chatMemoryMap.computeIfAbsent(sessionId, id ->
                MessageWindowChatMemory.builder()
                        .maxMessages(MAX_MESSAGE_COUNT)
                        .build()
        );
    }

    /**
     * 清空某个会话的记忆（可选功能）
     */
    public void clearMemory(String sessionId) {
        chatMemoryMap.remove(sessionId);
    }
}
