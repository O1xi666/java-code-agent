package com.example.javacodeagent.tool;

import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Component
public class JavaCodeTool {

    @Tool("编译并运行输入的Java代码，返回执行结果或错误信息")
    public String runJavaCode(String javaCode) {
        File tempDir = null;
        File tempFile = null;

        try {
            // 1. 创建临时目录，生成固定文件名Test.java（解决public类名=文件名问题）
            tempDir = Files.createTempDirectory("java_code_tool").toFile();
            tempFile = new File(tempDir, "Test.java");

            // 2. 写入代码（UTF-8编码，避免乱码）
            try (FileWriter writer = new FileWriter(tempFile, StandardCharsets.UTF_8)) {
                writer.write(javaCode);
            }

            // 3. 用系统javac命令编译（彻底绕开JavaCompiler API兼容问题）
            Process compileProcess = new ProcessBuilder(
                    "javac",
                    "-encoding", "UTF-8",
                    "-classpath", System.getProperty("java.class.path"),
                    tempFile.getAbsolutePath()
            )
                    .directory(tempDir)
                    .redirectErrorStream(true) // 合并错误流到输入流，统一读取
                    .start();

            // 读取编译输出/错误
            String compileOutput = new String(compileProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int compileExitCode = compileProcess.waitFor();

            // 编译失败：直接返回错误信息
            if (compileExitCode != 0) {
                return "编译失败！\n错误详情：\n" + compileOutput + "\n待编译代码：\n" + javaCode;
            }

            // 4. 用系统java命令运行编译后的class文件
            Process runProcess = new ProcessBuilder(
                    "java",
                    "-Dfile.encoding=UTF-8",
                    "Test"
            )
                    .directory(tempDir)
                    .redirectErrorStream(true)
                    .start();

            // 读取运行输出/错误
            String runOutput = new String(runProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int runExitCode = runProcess.waitFor();

            // 5. 返回结果
            if (runExitCode != 0) {
                return "运行失败！\n错误详情：\n" + runOutput;
            } else {
                return "运行成功！输出结果：\n" + runOutput;
            }

        } catch (Exception e) {
            return "工具执行异常：" + e.getMessage();
        } finally {
            // 6. 彻底清理临时文件（无论成功失败都执行）
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            if (tempDir != null && tempDir.exists()) {
                deleteDirectory(tempDir);
            }
        }
    }

    // 辅助方法：递归删除目录，彻底清理缓存
    private void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
}