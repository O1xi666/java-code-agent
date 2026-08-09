package com.example.javacodeagent.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 包装底层聊天模型，统计单次分析请求中的工具调用轮数，
 * 超过上限后抛出 {@link ToolCallLimitExceededException}，避免模型无限循环调用工具。
 */
public class ToolCallGuardModel implements ChatLanguageModel {

    private static final Logger log = LoggerFactory.getLogger(ToolCallGuardModel.class);

    private final ChatLanguageModel delegate;
    private final int maxToolRounds;
    private final ThreadLocal<Integer> toolRounds = ThreadLocal.withInitial(() -> 1);

    public ToolCallGuardModel(ChatLanguageModel delegate, int maxToolRounds) {
        this.delegate = delegate;
        this.maxToolRounds = maxToolRounds;
    }

    public void beginRequest() {
        toolRounds.set(1);
    }

    public void endRequest() {
        toolRounds.remove();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        Response<AiMessage> response = delegate.generate(messages);
        AiMessage aiMessage = response == null ? null : response.content();
        if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
            int current = toolRounds.get();
            if (current >= maxToolRounds) {
                log.warn("工具调用轮数超过上限: {}", maxToolRounds);
                throw new ToolCallLimitExceededException("工具调用轮数超过上限 " + maxToolRounds);
            }
            toolRounds.set(current + 1);
        }
        return response;
    }
}
