package com.example.javacodeagent.prompt;

import java.util.List;

public final class FactCheckPrompt {

    private FactCheckPrompt() {
    }

    public static String build(String question, String knowledgeContext,
                               List<String> toolObservations, String answer) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是金融投研回答的事实一致性核查员。");
        sb.append("你必须只基于【检索上下文】和【工具原始返回】核查回答，不得自行补充外部知识。\n\n");

        sb.append("【用户问题】\n").append(blankIfNull(question)).append("\n\n");
        sb.append("【检索上下文】\n").append(blankIfNull(knowledgeContext)).append("\n\n");
        sb.append("【工具原始返回】\n");
        if (toolObservations == null || toolObservations.isEmpty()) {
            sb.append("（无）\n");
        } else {
            for (String obs : toolObservations) {
                sb.append("- ").append(blankIfNull(obs)).append("\n");
            }
        }

        sb.append("\n【待核查回答】\n").append(blankIfNull(answer)).append("\n\n");

        sb.append("请从以下三个维度核查：\n");
        sb.append("1. 数据准确性：回答中的数字、指标、引用编号是否能在原始数据中找到；\n");
        sb.append("2. 标的匹配度：回答主体是否与用户问题中的股票名称/代码一致，是否存在串标；\n");
        sb.append("3. 逻辑一致性：结论是否由数据和推理路径推导，是否存在跳步或自相矛盾。\n\n");

        sb.append("只输出一个 JSON 对象，不要输出解释或代码块，格式：\n");
        sb.append("{\"dataAccurate\":true,\"targetMatched\":true,\"logicConsistent\":true,");
        sb.append("\"passed\":true,\"issues\":[\"问题1\"],\"fixInstructions\":\"修正指令\"}\n");
        return sb.toString();
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
