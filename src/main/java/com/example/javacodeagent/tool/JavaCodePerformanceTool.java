package com.example.javacodeagent.tool;

import dev.langchain4j.agent.tool.Tool; // ✅ 0.34.0 唯一正确导包，和你图2完全一致
import org.springframework.stereotype.Component;

/**
 * Java代码性能分析工具
 * 专门找代码性能问题（循环冗余、重复创建对象、无用消耗等）
 * 与JavaCodeTool形成工具库，让Agent自主选择
 */
@Component
public class JavaCodePerformanceTool {

    /**
     * 分析Java代码的性能问题
     * @param javaCode 待分析的Java代码
     * @return 性能分析报告
     */
    @Tool // ✅ 正确注解，零报红
    public String analyzeCodePerformance(String javaCode) {
        StringBuilder performanceReport = new StringBuilder();
        performanceReport.append("【Java代码性能分析报告】\n");

        // 检测1：多层for循环嵌套（时间复杂度飙升）
        if (countOccurrences(javaCode, "for") >= 2) {
            performanceReport.append("⚠️  发现多层for循环嵌套，时间复杂度O(n²)，建议优化逻辑或改用更高效算法\n");
        }

        // 检测2：循环内重复创建对象（GC压力大）
        if (javaCode.contains("for") && javaCode.contains("new ")) {
            performanceReport.append("⚠️  循环内部重复创建对象，产生大量临时变量，建议将对象创建移到循环外\n");
        }

        // 检测3：无限循环（无终止条件）
        if (javaCode.contains("while(true)") && !javaCode.contains("break")) {
            performanceReport.append("⚠️  发现无限空循环，持续占用CPU资源，必须添加break终止条件\n");
        }

        // 检测4：字符串拼接用+（循环内低效）
        if (javaCode.contains("for") && javaCode.contains("+=") && javaCode.contains("\"")) {
            performanceReport.append("⚠️  循环内使用+拼接字符串，建议改用StringBuilder提升性能\n");
        }

        // 无性能问题
        if (performanceReport.length() == "【Java代码性能分析报告】\n".length()) {
            performanceReport.append("✅  代码无明显性能问题，写法高效！");
        }

        return performanceReport.toString();
    }

    /**
     * 辅助方法：统计字符串中关键词出现次数
     */
    private int countOccurrences(String str, String keyword) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(keyword, idx)) != -1) {
            count++;
            idx += keyword.length();
        }
        return count;
    }
}