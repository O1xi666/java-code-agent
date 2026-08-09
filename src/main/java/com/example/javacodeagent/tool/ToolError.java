package com.example.javacodeagent.tool;

public record ToolError(
        String tool,
        String paramName,
        String paramValue,
        String code,
        String message,
        String suggestion,
        boolean retryable,
        int attempts,
        int maxAttempts) {
}
