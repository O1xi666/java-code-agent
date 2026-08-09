package com.example.javacodeagent.service;

import com.example.javacodeagent.prompt.FactCheckPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class FactCheckService {

    private static final Logger log = LoggerFactory.getLogger(FactCheckService.class);

    private final ChatLanguageModel chatLanguageModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FactCheckService(ChatLanguageModel chatLanguageModel) {
        this.chatLanguageModel = chatLanguageModel;
    }

    public FactCheckReport check(String question, String knowledgeContext,
                                 List<String> toolObservations, String answer) {
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return FactCheckReport.passedReport();
        }
        String prompt = FactCheckPrompt.build(question, knowledgeContext, toolObservations, answer);
        try {
            List<ChatMessage> messages = List.of(UserMessage.from(prompt));
            Response<AiMessage> response = chatLanguageModel.generate(messages);
            String raw = response == null || response.content() == null ? "" : response.content().text();
            return parse(raw);
        } catch (Exception e) {
            log.warn("事实校验调用失败，默认放行: {}", e.getMessage());
            return FactCheckReport.passedReport();
        }
    }

    private FactCheckReport parse(String raw) {
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            boolean dataAccurate = bool(node, "dataAccurate");
            boolean targetMatched = bool(node, "targetMatched");
            boolean logicConsistent = bool(node, "logicConsistent");
            boolean passed = bool(node, "passed");

            List<String> issues = new ArrayList<>();
            JsonNode issuesNode = node.get("issues");
            if (issuesNode != null && issuesNode.isArray()) {
                issuesNode.forEach(item -> issues.add(item.asText("")));
            }
            String fixInstructions = text(node, "fixInstructions");
            return new FactCheckReport(dataAccurate, targetMatched, logicConsistent, passed,
                    issues, fixInstructions);
        } catch (Exception e) {
            log.warn("事实校验结果解析失败，按未通过处理: {}", e.getMessage());
            return FactCheckReport.failed("自校验输出格式无法解析");
        }
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isBoolean() && value.asBoolean();
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return "{}";
        return raw.substring(start, end + 1);
    }

    public record FactCheckReport(boolean dataAccurate, boolean targetMatched, boolean logicConsistent,
                                  boolean passed, List<String> issues, String fixInstructions) {

        public static FactCheckReport passedReport() {
            return new FactCheckReport(true, true, true, true, List.of(), "");
        }

        public static FactCheckReport failed(String reason) {
            return new FactCheckReport(false, false, false, false, List.of(reason),
                    "请重新生成回答，确保结论基于提供的原始数据");
        }
    }
}
