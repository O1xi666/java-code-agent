package com.example.javacodeagent.controller;

import com.example.javacodeagent.service.JavaCodeAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")  // 类上的基础路径
public class CodeAgentController {

    @Autowired
    private JavaCodeAgent javaCodeAgent;

    // 方法上只写 /code，拼接后就是 /api/agent/code
    @PostMapping("/code")
    public String analyzeCode(
            @RequestBody String userInput,
            @RequestHeader(value = "X-Session-Id", defaultValue = "default-session") String sessionId
    ) {
        return javaCodeAgent.analyzeCode(userInput, sessionId);
    }
}