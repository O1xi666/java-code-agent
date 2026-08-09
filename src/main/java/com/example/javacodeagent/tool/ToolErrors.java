package com.example.javacodeagent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class ToolErrors {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolErrors() {
    }

    public static ToolError secidError(String tool, String paramValue, Exception e, String action) {
        String msg = message(e);
        if (containsAny(msg, "secid", "代码", "code", "无效", "不存在", "404", "not found", "格式")) {
            return new ToolError(tool, "secid", paramValue, "INVALID_SECID",
                    action + "失败：" + msg,
                    "请检查 secid 格式，应为 1.600519（沪市）或 0.000001（深市）；不确定代码时请先调用 resolveCode",
                    true, 0, 0);
        }
        return new ToolError(tool, "secid", paramValue, "NETWORK_ERROR",
                action + "失败：" + msg,
                "数据源暂时不可用，请稍后重试，或改用其他工具获取同类数据",
                true, 0, 0);
    }

    public static ToolError keywordError(String tool, String paramValue, Exception e, String action) {
        String msg = message(e);
        if (containsAny(msg, "keyword", "参数", "无效", "不存在", "404")) {
            return new ToolError(tool, "keyword", paramValue, "PARAMETER_INVALID",
                    action + "失败：" + msg,
                    "请使用更具体的股票名称或代码作为关键词，例如 贵州茅台 或 600519",
                    true, 0, 0);
        }
        return new ToolError(tool, "keyword", paramValue, "NETWORK_ERROR",
                action + "失败：" + msg,
                "资讯源暂时不可用，请稍后重试",
                true, 0, 0);
    }

    public static ToolError nameError(String tool, String paramValue, Exception e) {
        return new ToolError(tool, "stockName", paramValue, "STOCK_NOT_FOUND",
                "股票名称解析失败：" + message(e),
                "请确认股票中文名称是否正确，或改用 secid 直接查询",
                false, 0, 0);
    }

    public static ToolError unknown(String tool, String paramName, String paramValue, Exception e) {
        return new ToolError(tool, paramName, paramValue, "UNKNOWN",
                "工具执行失败：" + message(e),
                "请检查参数后重试，或换用其他工具",
                true, 0, 0);
    }

    public static ToolError duplicate(String tool, String paramName, String paramValue, int maxAttempts) {
        return new ToolError(tool, paramName, paramValue, "DUPLICATE_CALL",
                "已尝试过相同的参数",
                "请修正参数后重新调用，不要重复相同请求",
                false, 0, maxAttempts);
    }

    public static String render(ToolError error) {
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put(error.paramName(), error.paramValue());

            Map<String, Object> errorBody = new LinkedHashMap<>();
            errorBody.put("code", error.code());
            errorBody.put("message", error.message());
            errorBody.put("suggestion", error.suggestion());
            errorBody.put("retryable", error.retryable());
            errorBody.put("attempts", error.attempts());
            errorBody.put("maxAttempts", error.maxAttempts());

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tool", error.tool());
            body.put("request", request);
            body.put("error", errorBody);
            return MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            return "工具执行失败: " + e.getMessage();
        }
    }

    public static String message(Exception e) {
        if (e == null) return "unknown";
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static boolean containsAny(String source, String... keys) {
        String lower = source.toLowerCase(Locale.ROOT);
        for (String key : keys) {
            if (lower.contains(key.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
