package com.example.javacodeagent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agent 调用链追踪服务
 *
 * 每条请求生成唯一 traceId，记录完整调用链路：
 * INPUT → TOOL_CALL → TOOL_RESULT → OUTPUT → SUMMARY
 *
 * 使用 ThreadLocal 传递 traceId，无需修改方法签名
 * 输出 JSON Lines 格式到 AgentTrace 日志通道
 */
@Service
public class AgentTracerService {

    private static final Logger traceLog = LoggerFactory.getLogger("AgentTrace");

    private final ThreadLocal<TraceContext> contextHolder = new ThreadLocal<>();

    /**
     * 开始追踪：生成 traceId，记录用户输入
     */
    public String startTrace(String sessionId, String userInput) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        contextHolder.set(new TraceContext(traceId, System.currentTimeMillis()));

        emit("INPUT",
                "traceId", traceId,
                "sessionId", sessionId,
                "input", truncate(userInput, 500));
        return traceId;
    }

    /**
     * 包裹工具调用：记录 TOOL_CALL + TOOL_RESULT / TOOL_ERROR + 耗时
     */
    @SuppressWarnings("unchecked")
    public <T> T traceToolCall(String toolName, String args, Supplier<T> fn) {
        TraceContext ctx = contextHolder.get();
        if (ctx == null) return fn.get();

        ctx.toolCallCount++;

        emit("TOOL_CALL",
                "traceId", ctx.traceId,
                "tool", toolName,
                "args", truncate(args, 300));

        long start = System.currentTimeMillis();
        try {
            T result = fn.get();
            long duration = System.currentTimeMillis() - start;

            emit("TOOL_RESULT",
                    "traceId", ctx.traceId,
                    "tool", toolName,
                    "durationMs", String.valueOf(duration),
                    "result", truncate(String.valueOf(result), 500));
            ctx.toolObservations.add(new ToolObservation(
                    toolName, truncate(args, 300), truncate(String.valueOf(result), 500)));
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            emit("TOOL_ERROR",
                    "traceId", ctx.traceId,
                    "tool", toolName,
                    "durationMs", String.valueOf(duration),
                    "error", truncate(e.getMessage(), 300));
            ctx.toolObservations.add(new ToolObservation(
                    toolName, truncate(args, 300), "ERROR: " + truncate(e.getMessage(), 300)));
            throw e;
        }
    }

    /**
     * 获取当前请求已收集的工具观测，供事实自校验使用。
     */
    public List<ToolObservation> getToolObservations() {
        TraceContext ctx = contextHolder.get();
        return ctx == null ? List.of() : List.copyOf(ctx.toolObservations);
    }

    /**
     * 记录 LLM 最终输出
     */
    public void logOutput(String traceId, String output) {
        emit("OUTPUT",
                "traceId", traceId,
                "result", truncate(output, 500));
    }

    /**
     * 结束追踪：记录汇总 + 清理 ThreadLocal
     */
    public void endTrace(String traceId) {
        TraceContext ctx = contextHolder.get();
        if (ctx == null) return;

        long totalDuration = System.currentTimeMillis() - ctx.startTime;
        emit("SUMMARY",
                "traceId", traceId,
                "totalDurationMs", String.valueOf(totalDuration),
                "toolCalls", String.valueOf(ctx.toolCallCount));

        contextHolder.remove();
    }

    // ========== 内部 ==========

    private void emit(String stage, String... kv) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ts\":\"").append(Instant.now()).append('"');
        sb.append(",\"stage\":\"").append(stage).append('"');
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(",\"").append(escape(kv[i])).append("\":\"");
            sb.append(escape(kv[i + 1] != null ? kv[i + 1] : "")).append('"');
        }
        sb.append('}');
        traceLog.info(sb.toString());
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /** 每个 trace 的上下文，用 ThreadLocal 传递 */
    private static class TraceContext {
        final String traceId;
        final long startTime;
        int toolCallCount;
        final List<ToolObservation> toolObservations = new ArrayList<>();

        TraceContext(String traceId, long startTime) {
            this.traceId = traceId;
            this.startTime = startTime;
            this.toolCallCount = 0;
        }
    }

    /**
     * 工具调用观测：工具名、入参、返回结果（失败时为错误信息）。
     */
    public record ToolObservation(String tool, String arguments, String result) {
    }
}
