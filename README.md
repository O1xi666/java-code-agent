# Stock Analysis Agent - 股票分析智能体

## 项目简介

基于 Java + Spring Boot + LangChain4j 构建的多维度股票分析智能体，支持实时行情获取、财务数据解析、新闻舆情分析、技术指标计算，并融合四维分析框架（基本面、技术面、消息面、行业比较）提供结构化的投研分析报告。

### 核心能力

- [x] **多工具编排**：4 个 Tool 自动调用，Agent 根据问题自动选择
- [x] **混合检索知识库**：向量（768维）+ BM25 本地检索，融合权重 7:3
- [x] **实时数据采集**：东方财富 API 直连，实时行情、K 线、新闻、财务数据
- [x] **结构化分析框架**：四维打分模型，每维度可量化、可追溯
- [x] **每日早报**：按需生成，自动获取自选股行情 + 新闻 + 异动预警（本地模式）
- [x] 18/18 关键文件含面试标注和技术亮点说明
## 技术栈

| 层级 | 技术选型 | 版本 | 说明 |
|------|---------|------|------|
| 后端框架 | Spring Boot | 3.2.0 | 企业级快速开发框架 |
| LLM | Ollama + LangChain4j | 0.34.0 | 本地部署，可控性强 |
| 向量存储 | InMemoryEmbeddingStore | LangChain4j | 零外部依赖，内存存储 |
| 全文检索 | Lucene + BM25 | 9.8.0 | 本地持久化，支持中文 |
| PDF 解析 | Apache PDFBox | 3.0.1 | 纯 Java，支持中文文档 |
| 工具库 | JTokkit | 1.1.0 | Token 切分和计算 |
| 缓存 | Redis | - | 会话记忆 + 结果缓存 |
| 数据来源 | 东方财富公开 API + Jsoup | 1.17.2 | 无需认证，稳定可靠 |

---

## 项目架构

```
Stock Analysis Agent
│
├── 数据采集层（Phase 1 ✅）
│   ├── StockMarketService      # 实时行情、K 线、均线
│   ├── StockFinancialService   # 财务数据
│   ├── StockNewsService        # 新闻抓取
│   ├── StockIndicatorService   # 技术指标（MACD/RSI/KDJ）
│   └── StockSectorService      # 行业板块数据
│
├── 向量知识库层（Phase 2 ✅）
│   ├── DocumentService         # 文档上传、解析、分块
│   ├── LocalVectorService      # InMemoryEmbeddingStore（替代 Milvus）
│   ├── BM25Searcher            # Lucene 本地索引
│   └── HybridSearchService     # 向量 + BM25 混合检索 + 融合
│
├── Agent 分析层（Phase 3 ✅）
│   ├── StockAgent              # LangChain4j AiServices 接口
│   ├── StockAnalysisPrompt     # 四维分析框架 Prompt
│   ├── StockMarketTool         # 行情 Tool
│   ├── StockFinancialTool      # 财务 Tool
│   ├── StockNewsTool           # 新闻 Tool
│   └── StockIndicatorTool      # 技术指标 Tool
│
├── REST API 层（Phase 4 ✅）
│   ├── StockController         # 分析接口 POST /api/stock/analyze
│   └── DocumentController      # 文档管理接口 POST /api/documents/upload
│
└── 配置层
    ├── RagConfig               # InMemoryEmbeddingStore + BM25 配置
    ├── RedisConfig             # Redis 会话记忆
    └── application.yml         # 应用配置
```

---

## 技术亮点（面试关注点）

### 1. 从 Milvus 迁移到 InMemoryEmbeddingStore（Phase 2）
- 删除了 MilvusConfig 和 MilvusVectorService
- 改用 LangChain4j 内置的 InMemoryEmbeddingStore
- 零外部依赖，启动即用
- 支持 toJson/fromJson 持久化

**面试题**：为什么不用 Milvus？
- 本地场景（几十到几百份研报）内存绰绰有余
- 引入 Milvus 需要额外部署和维护，增加复杂度

### 2. 四维分析框架（Phase 3）
- 基本面（35%）+ 技术面（25%）+ 消息面（25%）+ 行业比较（15%）
- 每维度有明确的评分标准（优秀/良好/一般/较差）
- 强制要求 LLM 引用数据来源，防止幻觉
- 输出固定格式，便于对比和追溯

### 3. MACD/RSI/KDJ 纯算法实现（Phase 1）
- 不依赖 ta4j 等第三方计算库
- MACD 使用递推公式（EMA 平滑），避免全量重算
- 时间复杂度 O(n)，空间复杂度 O(1)

### 4. 虚拟线程并行检索（Phase 2）
- 使用 Java 21 VirtualThread 并行执行向量检索 + BM25 检索
- 融合权重 7:3，兼顾语义相似度和关键词匹配

### 5. 工具编排（Phase 3）
- 4 个 @Tool 方法，由 LangChain4j AiServices 自动调度
- 统一的错误处理和日志输出
- Agent 根据用户问题自动决定调用哪个工具

---

## 四维分析框架

| 维度 | 权重 | 评分规则 | 数据来源 |
|------|------|---------|---------|
| 基本面 | 35% | >80 优秀 60-80 良好 40-60 一般 <40 较差 | 财务 API + ROE/PE/毛利率 |
| 技术面 | 25% | >80 强势 60-80 偏强 40-60 偏弱 <40 弱势 | K 线 + MACD + RSI + KDJ |
| 消息面 | 25% | >80 利好 60-80 偏正面 40-60 偏负面 <40 利空 | 新闻 API + LLM 情感分析 |
| 行业比较 | 15% | >80 领涨 60-80 同步 <60 落后 | 行业板块 API |

---

## API 接口

### 分析股票
```http
POST /api/stock/analyze
Content-Type: application/json
X-Session-Id: default-session

"分析一下贵州茅台，值得买吗？"
```

### 上传文档（建立 RAG 知识库）
```http
POST /api/documents/upload
Content-Type: multipart/form-data

file: 年报.pdf
source: 贵州茅台2024年报
```

---

## 快速开始

### 前置要求
- Java 21+
- Maven 3.8+
- Redis（用于会话记忆）
- Ollama（本地 LLM 服务）

### 安装 Ollama 模型
```bash
ollama pull qwen3:8b
ollama pull nomic-embed-text
```

### 运行
```bash
# 启动 Redis
redis-server

# 启动项目
mvn spring-boot:run

# 发送测试请求
curl -X POST http://localhost:8080/api/stock/analyze \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: test" \
  -d "分析一下贵州茅台"
```

---

## 项目进度

### 第一阶段：基础数据采集工具 ✅
- [x] HttpClientUtil（HTTP 请求工具类）
- [x] StockMarketService（实时行情 + K 线 + 均线）
- [x] StockFinancialService（财务数据）
- [x] StockNewsService（新闻抓取）
- [x] StockIndicatorService（MACD / RSI / KDJ / 布林带）
- [x] StockSectorService（行业板块数据）

### 第二阶段：向量知识库改造 ✅
- [x] 移除 Milvus 依赖
- [x] LocalVectorService（InMemoryEmbeddingStore）
- [x] 重写 RagConfig（InMemoryEmbeddingStore + BM25）
- [x] DocumentService（文档上传/解析/分块）
- [x] 删除 MilvusConfig 和 MilvusVectorService

### 第三阶段：StockAgent 分析引擎 ✅
- [x] StockAnalysisPrompt（四维分析框架 Prompt）
- [x] StockMarketTool（行情）
- [x] StockFinancialTool（财务）
- [x] StockNewsTool（新闻）
- [x] StockIndicatorTool（技术指标）
- [x] StockAgent（AiServices 接口）

### 第四阶段：REST API ✅
- [x] StockController（分析接口）
- [x] DocumentController（文档上传接口）

### 第五阶段：项目完善 ✅
- [x] 更新 application.yml
- [x] 更新 README
- [x] 验证项目结构和依赖
- [ ] 补充单元测试（待完成）
- [ ] 集成测试（待完成）

---

## 数据来源

### 东方财富 API（无需认证）
- 实时行情：`http://push2.eastmoney.com/api/qt/stock/get`
- K 线数据：`http://push2.eastmoney.com/api/qt/stock/kline/get`
- 新闻搜索：`http://searchapi.eastmoney.com/search_news`

### 股票代码格式
- 上海：`1.` + 6 位数字（如 `1.600519` 贵州茅台）
- 深圳：`0.` + 6 位数字（如 `0.000001` 平安银行）

---

## 文件清单（共 35 个 Java 文件）

| 包路径 | 文件 | 功能 |
|--------|------|------|
| `config/` | RagConfig.java, RedisConfig.java | RAG 和 Redis 配置 |
| `controller/` | StockController.java, DocumentController.java, CodeAgentController.java | REST API |
| `prompt/` | StockAnalysisPrompt.java | 四维分析框架 Prompt |
| `rag/service/` | LocalVectorService.java, HybridSearchService.java | 向量检索 + 混合检索 |
| `rag/util/` | BM25Searcher.java, ChunkUtils.java, ContextManager.java, TraceabilityUtils.java | Lucene 索引、分块、上下文管理 |
| `service/` | StockAgent.java, StockMarketService.java, StockFinancialService.java, StockNewsService.java, StockIndicatorService.java, StockSectorService.java, DocumentService.java, JavaCodeAgent.java, ChatMemoryHolder.java | 业务服务和 Agent |
| `tool/` | StockMarketTool.java, StockFinancialTool.java, StockNewsTool.java, StockIndicatorTool.java, JavaCodeTool.java, JavaCodeTestGeneratorTool.java, JavaCodePerformanceTool.java | LLM 可调用的工具 |
| `util/` | HttpClientUtil.java | HTTP 请求工具 |
| `vo/` | StockQuoteVO.java, StockKLineVO.java, StockFinancialVO.java, StockNewsVO.java, StockIndicatorVO.java | 数据模型 |

---

## 许可证

MIT License

**最后更新时间：2024-07-07**
