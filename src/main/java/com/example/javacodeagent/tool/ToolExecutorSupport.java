package com.example.javacodeagent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

@Component
public class ToolExecutorSupport {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutorSupport.class);

    private final int maxRetries;
    private final int retryBaseMs;
    private final int retryJitterMs;
    private final Random random = new Random();
    private final ThreadLocal<Set<String>> seenCalls = ThreadLocal.withInitial(HashSet::new);

    public ToolExecutorSupport(
            @Value("${agent.tool.max-retries:2}") int maxRetries,
            @Value("${agent.tool.retry-base-ms:800}") int retryBaseMs,
            @Value("${agent.tool.retry-jitter-ms:1200}") int retryJitterMs) {
        this.maxRetries = maxRetries;
        this.retryBaseMs = retryBaseMs;
        this.retryJitterMs = retryJitterMs;
    }

    public void beginRequest() {
        seenCalls.get().clear();
    }

    public void endRequest() {
        seenCalls.remove();
    }

    /**
     * 统一执行工具调用：成功直接返回，失败最多重试 2 次，
     * 重试间隔带随机抖动，最终失败时返回结构化错误观察。
     */
    public String execute(String toolName, String paramName, String paramValue,
                          ToolAction action, Function<Exception, ToolError> errorMapper) {
        int maxAttempts = maxRetries + 1;
        String callKey = toolName + "|" + paramName + "=" + paramValue;
        if (!seenCalls.get().add(callKey)) {
            log.warn("工具重复调用被拦截: {}", callKey);
            return ToolErrors.render(ToolErrors.duplicate(toolName, paramName, paramValue, maxAttempts));
        }

        Exception last = null;
        int attempts = 0;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            attempts = attempt;
            try {
                return action.execute();
            } catch (Exception e) {
                last = e;
                if (attempt < maxAttempts) {
                    int delayMs = retryBaseMs + random.nextInt(retryJitterMs);
                    log.warn("工具 {} 第{}次尝试失败，{}ms 后重试: {}", toolName, attempt, delayMs, e.getMessage());
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        ToolError base = errorMapper.apply(last);
        ToolError filled = new ToolError(toolName, base.paramName(), paramValue,
                base.code(), base.message(), base.suggestion(), base.retryable(),
                attempts, maxAttempts);
        log.error("工具 {} 执行失败（尝试{}次）: {}", toolName, attempts, last == null ? "" : last.getMessage());
        return ToolErrors.render(filled);
    }

    @FunctionalInterface
    public interface ToolAction {
        String execute() throws Exception;
    }
}
