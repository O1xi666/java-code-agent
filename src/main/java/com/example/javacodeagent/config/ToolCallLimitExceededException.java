package com.example.javacodeagent.config;

public class ToolCallLimitExceededException extends RuntimeException {

    public ToolCallLimitExceededException(String message) {
        super(message);
    }
}
