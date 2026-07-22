package com.example.javacodeagent.controller;

import com.example.javacodeagent.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档管理 REST API
 *
 * 接口设计：
 * POST /api/documents/upload - 上传文档（PDF/DOCX/TXT）
 * GET /api/documents - 获取已上传文档列表
 * 技术亮点（面试关注点）：
 * 1. 文档上传即解析，上传完成即建立向量 + BM25 双索引，无需手动触发
 * 2. 统一的异常处理，避免前端实现复杂的状态码判断
 * 3. 返回结构化信息（文档ID、文件名、分块数），便于后续管理
*/
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 上传文档并建立 RAG 索引
     *
     * @param file   上传的文件（pdf/docx/txt）
     * @param source 可选来源名称
     * @return 上传结果
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", required = false, defaultValue = "") String source
    ) {
        log.info("文档上传请求: {}, size={}", file.getOriginalFilename(), file.getSize());

        try {
            DocumentService.DocumentInfo info = documentService.uploadDocument(file, source);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "documentId", info.id(),
                    "fileName", info.originalName(),
                    "fileSize", info.fileSize(),
                    "chunkCount", info.chunkCount(),
                    "message", "文档上传成功，已自动分块并建立向量索引"
            ));
        } catch (Exception e) {
            log.error("文档上传失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "文档上传失败：" + e.getMessage()
            ));
        }
    }
}

