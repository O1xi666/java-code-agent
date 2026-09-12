package com.example.javacodeagent.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;

/**
 * 记忆层 token 计数器。
 *
 * <p>动态 Token 窗口必须按真实 token 数分配预算，而不是按"消息条数"估算——
 * 一条四维分析报告可能有上千 token，按条数裁剪会瞬间撑爆上下文。
 * 这里复用知识库分块用的同一套 JTokkit 口径，保证全项目 token 统计一致。
 */
public final class MemoryTokenizer {

    private static final Encoding ENCODING;

    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODING = registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);
    }

    private MemoryTokenizer() {
    }

    public static int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        try {
            return ENCODING.encode(text).size();
        } catch (Exception e) {
            // 极端字符导致编码失败时退化为字符数估算，保证主流程不中断
            return Math.max(1, text.length() / 2);
        }
    }
}
