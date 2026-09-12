# Stock Analysis Agent · 金融股票智能分析 Agent

![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-6DB33F?logo=springboot&logoColor=white)
![LangChain4j](https://img.shields.io/badge/LangChain4j-0.34.0-1C3C3C)
![Ollama](https://img.shields.io/badge/LLM-Ollama%20%2B%20qwen3%3A8b-000000?logo=ollama&logoColor=white)

面向个人投资者的股票分析智能体：用自然语言提问，Agent 自行决定调用哪些业务工具，
基于真实行情 / 财务 / 资讯 / 技术指标数据，输出可追溯、可复核的四维投研分析报告。

例如输入「分析一下贵州茅台，现在值不值得买入？」：

```
用户提问
  └─ 三级记忆组装（用户画像 + 历史结论 + 会话窗口）
       └─ 知识库混合检索（向量 0.7 + BM25 0.3）→ 注入【参考知识】
            └─ LLM 按五步推理路径决策
                 ├─ resolveCode("贵州茅台")  → 1.600519
                 ├─ getQuote("1.600519")      → 实时行情
                 ├─ getIndicators("1.600519") → MACD / RSI / KDJ / 布林带
                 ├─ getFinancial("1.600519")  → ROE / 毛利率 / PE
                 └─ getNews("贵州茅台")        → 资讯标题
                      └─ 四维打分输出 → 事实一致性自校验 → 不通过则带修正指令重生成
                           └─ 回写会话记忆 + 沉淀历史结论（通过准入才写入）
```

---

## 核心能力

| 能力 | 实现要点 |
|---|---|
| **工具自主编排** | 5 类 `@Tool` 业务工具，由 LangChain4j AiServices 以 Function Calling 驱动多轮调用循环，支持单工具调用与多工具串行组合；工具异常以结构化错误观察回传，引导模型修正参数重试，并设有调用轮数上限 |
| **三级记忆体系** | 会话层（重要性打分 + 动态 Token 窗口 + 无效交互过滤）／用户画像层（结构化 KV + 偏好冲突校验与确认更新）／历史结论层（写入准入 + 失效标记 + 归档治理） |
| **思维链 + 事实自校验** | Prompt 固化「明确问题→拆解维度→调用工具→推导结论→交叉校验」五步路径；回答生成后再从数据准确性 / 标的匹配度 / 逻辑一致性三个维度自动核查，不通过则触发重生成 |
| **双源降级 + 多级缓存** | 行情走新浪、降级东方财富；财务走 Selenium、降级东方财富 API；资讯走新浪财经、降级东方财富。Caffeine L1 + Redis L2 二级缓存，按数据类型区分 TTL |
| **全链路步骤级埋点** | 每次分析产生 traceId，按阶段输出 JSON Lines 日志，可完整还原「用户输入 → 工具调用 → 工具结果 → 最终输出 → 汇总」 |
| **RAG 知识库** | 向量检索（Ollama 嵌入）+ BM25（Lucene smartcn）并行混合检索，权重 7:3，可选 Rerank 精排；投研知识按标的与类别检索并带引用编号 |
| **异动监控** | 每 30 分钟轮询自选股，涨跌幅 / 量能 / MACD / RSI / 布林带五类规则触发预警，SSE 实时推送到前端面板 |

---

## 技术栈

| 层次 | 选型 | 版本 | 说明 |
|---|---|---|---|
| 语言 / 框架 | Java + Spring Boot | 21 / 3.2.0 | 虚拟线程并行检索 |
| LLM 编排 | LangChain4j | 0.34.0 | AiServices + `@Tool` Function Calling |
| 模型服务 | Ollama（qwen3:8b + bge-base-zh） | 本地部署 | 推理与嵌入 |
| 向量存储 | LangChain4j InMemoryEmbeddingStore | — | JSON 持久化，零外部依赖 |
| 全文检索 | Lucene + SmartChineseAnalyzer（BM25） | 9.8.0 | 中文分词 |
| 缓存 | Caffeine L1 + Redis L2 | — | 按数据类型区分 TTL |
| 持久化 | Spring Data JPA + MySQL | — | 自选股 |
| 浏览器自动化 | Selenium + WebDriverManager | 4.27.0 | 财务数据与新闻抓取 |
| 解析 | Jsoup / PDFBox / JTokkit / Jackson | — | HTML / PDF / Token 计量 / JSON |
| 前端 | 原生 HTML + ECharts | — | 4 个功能页签，SSE 实时预警 |

---

## 目录与分层

```
controller/     REST API（分析 / 自选股 / 知识库 / 监控 / 诊断）
service/        StockAgent 核心链路、三级记忆编排、数据采集、监控、埋点
memory/         三级记忆的存储与算法：打分、Token 窗口、画像、历史结论
tool/           5 类 LLM 可调用工具 + 结构化错误模型与统一执行器
prompt/         五步推理系统 Prompt、事实校验 Prompt
rag/            知识库服务、混合检索、精排、分块、BM25、SimHash 去重
config/         模型 / 向量库 / BM25 / Redis / 缓存策略 / 工具调用护栏
util/           多级缓存管理器、HTTP 客户端、Selenium 单例
vo/ model/ repository/   数据模型与持久化
```

---

## 关键实现

### 1. 工具编排：5 类业务工具 + 结构化错误回传

`tool/` 下共 5 个 `@Tool`：`resolveCode`（名称→代码）、`getQuote`（实时行情）、
`getIndicators`（MACD/RSI/KDJ/布林带）、`getFinancial`（财务）、`getNews`（资讯）。

工具失败时不会抛异常打断链路，而是返回结构化错误观察交给模型决策：

```json
{
  "tool": "getQuote", "paramName": "secid", "paramValue": "600519",
  "code": "INVALID_SECID", "message": "获取实时行情失败：...",
  "suggestion": "请检查 secid 格式，应为 1.600519（沪市）或 0.000001（深市）",
  "retryable": true, "attempts": 3, "maxAttempts": 3
}
```

三道护栏防止模型陷入循环：

- `ToolExecutorSupport`：同一次请求内拦截完全重复的调用（`DUPLICATE_CALL`），失败最多重试 2 次、间隔带随机抖动；
- `ToolCallGuardModel`：包装底层模型统计工具调用轮数，超过 `agent.tool.max-rounds`（默认 8）抛出 `ToolCallLimitExceededException`，上层返回友好提示；
- Prompt 中显式约定 `retryable` / `maxAttempts` / `DUPLICATE_CALL` 的处理方式，让模型知道何时该换参数、换工具或直接作答。

### 2. 三级记忆体系

代码位于 `memory/`，由 `service/AgentMemoryService` 统一编排。

| 层 | 解决的问题 | 关键机制 |
|---|---|---|
| **会话层** `SessionMemory` | 长对话上下文溢出、无效信息挤占预算 | 每条消息写入时按角色与内容打 0~10 重要性分（`ImportanceScorer`），并把工具报错 / 重试 / 重复调用 / 中间态提示标记为噪声；`DynamicTokenWindow` 按真实 token 预算裁剪：噪声直接丢弃 → 高重要性优先 → 低重要性按时间补位 → 最近一条用户提问作为锚点必留 |
| **用户画像层** `UserProfileMemory` | 用户业务规则被遗忘或被模型擅自改写 | 结构化 KV 存长期偏好（投资风格 / 风险偏好 / 持有周期 / 关注赛道 / 自定义规则），枚举值有白名单；新值与已有值冲突时**不直接覆盖**，写入待确认区并让模型先向用户确认，用户回复确认后才落库 |
| **历史结论层** `ConclusionMemory` | 跨会话串题、过时结论干扰 | 三道治理：① 写入准入（必须含四维评分、有工具观测支撑、本轮正常完成）；② 失效标记（含行情/技术面信号的结论 24h 过期，纯基本面 7 天；同一标的有新结论时旧的立即置为 `STALE`）；③ 归档治理（失效结论移入归档区限量保留，检索只读 `ACTIVE` 且按标的过滤） |

要点：检索历史结论时按股票代码过滤，因此宁德时代的旧结论不会被注入到贵州茅台的上下文里；
会话层只回灌用户原始提问与助手回答，不把拼接后的增强输入入库，避免知识库全文在每轮对话中重复膨胀。

相关配置（`application.yml`）：

```yaml
agent:
  memory:
    token-budget: 2400   # 会话窗口 token 预算
    max-records: 30      # 窗口消息条数上限
```

### 3. 五步推理路径 + 生成后事实一致性自校验

系统 Prompt（`prompt/StockAnalysisPrompt`）强制模型遵循固定推理路径，禁止跳步与主观臆断：

```
明确问题 → 拆解分析维度 → 调用工具获取数据 → 推导结论 → 交叉校验
```

回答生成后由 `FactCheckService` 复核，从三个维度输出 JSON：

| 维度 | 检查内容 |
|---|---|
| `dataAccurate` | 回答中的数字/指标能否在工具原始返回或检索上下文中找到 |
| `targetMatched` | 回答主体是否与提问标的一致（有无串标） |
| `logicConsistent` | 结论是否由数据推导而来（有无跳步、自相矛盾） |

未通过时把 `fixInstructions` 作为修正指令拼回 Prompt 重新生成，最多 `agent.fact-check.max-rounds`（默认 2）轮。
该环节可通过 `agent.fact-check.enabled` 开关，用于对照实验。

### 4. 双数据源故障降级 + 多级缓存

| 数据 | 主源 | 降级源 |
|---|---|---|
| 实时行情 / K 线 | 新浪财经 | 东方财富（`EastMoneyMarketClient`） |
| 财务数据 | Selenium 渲染 | 东方财富数据中心 API |
| 资讯 | 新浪财经 | 东方财富搜索 |

缓存读取顺序 `L1 Caffeine → L2 Redis → 真实数据源 → 回写`（`util/DataCacheManager`），TTL 按数据性质区分：

| 缓存策略 | TTL | 依据 |
|---|---|---|
| `STOCK_QUOTE` | 30s | 股价实时变动，概览场景 30s 足够 |
| `STOCK_KLINE` | 5min | 日 K 一天只新增一根 |
| `STOCK_FINANCIAL` | 1h | 财报按季度发布 |
| `STOCK_NEWS` | 10min | 资讯非实时 |
| `LLM_ANALYSIS` | 20min | 减少重复推理（仅在无历史会话时命中） |

### 5. 全链路步骤级埋点

`service/AgentTracerService` 为每次分析生成 traceId，按阶段输出 JSON Lines 到独立的 `AgentTrace` logger：

```
INPUT → TOOL_CALL → TOOL_RESULT / TOOL_ERROR → OUTPUT → SUMMARY
```

```json
{"ts":"...","stage":"TOOL_CALL","traceId":"78780086","tool":"getQuote","args":"1.603977"}
{"ts":"...","stage":"TOOL_RESULT","traceId":"78780086","tool":"getQuote","durationMs":"118","result":"股票：国泰集团 (603977) ..."}
{"ts":"...","stage":"SUMMARY","traceId":"78780086","totalDurationMs":"39564","toolCalls":"3"}
```

这些工具观测同时被事实自校验复用（校验回答是否与原始返回一致），并在失败时记录 `TOOL_ERROR` 阶段。

---

## API 一览

### 股票分析

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/stock/analyze` | POST | LLM 驱动的四维分析（Header `X-Session-Id` 区分会话） |
| `/api/stock/analyze/stream` | POST | SSE 流式分析 |
| `/api/stock/daily-report?codes=` | POST | 每日早报：读取自选股行情 + 资讯 + 异动提示（不经过 LLM；`codes` 为空时使用自选股表，为空则回退内置列表） |

### 自选股

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/watchlist/add` | POST | 添加自选股 |
| `/api/watchlist/list` | GET | 查询自选股 |
| `/api/watchlist/{id}` | PUT / DELETE | 修改 / 删除 |

### 知识库

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/knowledge/insert` | POST | 插入知识条目 |
| `/api/knowledge/import-csv` | POST | CSV 批量导入 |
| `/api/knowledge/retrieve` | GET | 检索（可按标的过滤） |
| `/api/knowledge/general-rule` | POST / GET / DELETE | 通用规则（每次分析自动附加） |
| `/api/knowledge/stats` · `/stocks` · `/clear` · `/rebuild` | GET / DELETE / POST | 统计、覆盖标的、清空、重建索引 |

### 异动监控

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/monitor/alerts/stream` | GET (SSE) | 实时预警推送 |
| `/api/monitor/alerts` | GET / DELETE | 查询 / 清除预警 |

### 诊断与评测（用于复现量化指标）

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/diagnostics/fact-check` | POST | 单条回答的事实一致性打分（供离线评测调用） |
| `/api/diagnostics/cache/stats` | GET | 缓存命中统计 |
| `/api/diagnostics/cache/benchmark?secid=&rounds=` | POST | 真实行情路径的冷/热耗时基准 |

---

## 快速开始

### 前置条件

- JDK 21+、Maven 3.8+
- MySQL 8+（自选股持久化，`application.yml` 中配置；`createDatabaseIfNotExist=true` 会自动建库）
- Redis（会话记忆、画像、历史结论、L2 缓存；不可用时记忆层自动降级为进程内存储）
- Ollama 与本地模型：

```bash
ollama pull qwen3:8b
ollama pull quentinz/bge-base-zh-v1.5:latest
```

- Edge 浏览器（Selenium 抓取财务/资讯用）。驱动无需手动放置：优先使用项目根目录的
  `msedgedriver.exe`，找不到时由 WebDriverManager 自动下载匹配版本。

### 启动

```bash
cd java-code-agent
mvn spring-boot:run
```

启动后访问 <http://localhost:8080>，前端包含 4 个页签：股票分析、知识管理、自选股、异动监控。

```bash
curl -X POST http://localhost:8080/api/stock/analyze \
  -H "Content-Type: application/json" \
  -H "X-Session-Id: demo" \
  -d "分析一下贵州茅台，现在值不值得买入？"
```

---

## 测试与量化验证

### 单元测试

```bash
mvn test
```

覆盖三级记忆（打分 / Token 窗口 / 画像冲突 / 结论准入与失效）、技术指标算法、
行情降级解析、缓存命中与 TTL、SimHash 去重、控制器接口等，无需外部依赖即可运行。

### 复现两个量化指标

仓库提供可运行的评测与基准工具，**不预置任何结果数字**，数字需本地实测产生：

```bash
# 事实一致性自校验准确率（43 条带标签用例，三维度校验 21 pass / 22 fail）
python tools/eval/run_fact_eval.py --base-url http://localhost:8080 --out tools/eval/report.json

# 真实行情查询冷/热耗时（冷路径回源网络，热路径走 L1+L2 缓存）
curl -X POST "http://localhost:8080/api/diagnostics/cache/benchmark?secid=1.600519&rounds=20"
```

详见 [`tools/eval/README.md`](tools/eval/README.md)。

---

## 已知限制

- 数据来自公开接口抓取（新浪财经 / 东方财富），非授权行情源，接口变更可能导致取数失败；降级路径可兜住单点故障，但两源同时不可用时仍会失败。
- 新浪 K 线为不复权、东方财富降级路径为前复权，两者历史价格可能存在差异；技术指标注明「基于不复权 K 线计算」。
- 事实自校验依赖本地 LLM 判断，非确定性程序校验；其准确率需通过 `tools/eval` 实测，不要直接引用未经复现的数字。
- 监控预警保存在内存中，重启后丢失；去重窗口为 30 分钟。
- 用户标识当前直接复用会话 ID（`X-Session-Id`），接入登录体系后应替换为真实用户 ID。
- 项目为本地运行的演示/学习用途，未做鉴权、限流与多实例部署。

## License

[MIT](LICENSE)
