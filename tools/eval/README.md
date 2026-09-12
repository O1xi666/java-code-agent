# 评测工具说明

本目录用于复现简历中两个量化指标，所有数字都由本地运行的应用实测产生，仓库内不预置任何结果。

## 一、事实一致性自校验（75% → 92%）

### 这是什么

`FactCheckService` 是 Agent 回答的“事后核查员”：给定【用户问题】【检索上下文】【工具原始返回】【待核查回答】，
它调用 LLM 从三个维度判断回答是否可信：

- `dataAccurate`：回答里的数字/指标能否在原始数据中找到；
- `targetMatched`：回答主体是否与问题中的股票一致，有没有串标；
- `logicConsistent`：结论是否由数据推导得出，有没有跳步或自相矛盾。

三者都通过时 `passed=true`。Agent 在 `agent.fact-check.enabled=true` 时会把未通过的回答退回重写。

为了能在**不运行完整 Agent**的情况下给回答打分，`DiagnosticsController` 暴露了：

```
POST /api/diagnostics/fact-check
Body: {question, knowledgeContext, toolObservations[], answer}
Resp: {dataAccurate, targetMatched, logicConsistent, passed, issues, fixInstructions}
```

评测脚本正是通过这个接口，把 `src/main/resources/eval/fact-consistency-cases.jsonl` 中的
43 条带标签用例（21 条 `expected:"pass"` / 22 条 `expected:"fail"`，其中 3 条为边界用例）逐条送检，
把返回的 `passed` 与 `expected` 比对，得到“自校验准确率”。

### 如何复现简历数字

简历声称自校验准确率从 **75% 提升到 92%**。这个 before/after 差值是**配置差异**带来的，必须分别启动应用各跑一次，
本仓库不提供、也不编造任何现成结果：

1. 前提：本地已启动 Ollama，且拉取了项目所用模型（见 `application.yml`）。
2. “前”（关闭事实校验）：

   ```bash
   mvn spring-boot:run -Dspring-boot.run.arguments=--agent.fact-check.enabled=false
   ```

3. “后”（开启事实校验，默认值）：

   ```bash
   mvn spring-boot:run
   ```

4. 在另一个终端运行评测：

   ```bash
   python tools/eval/run_fact_eval.py --base-url http://localhost:8080 --out tools/eval/report-before.json
   python tools/eval/run_fact_eval.py --base-url http://localhost:8080 --out tools/eval/report-after.json
   ```

   说明：`/api/diagnostics/fact-check` 直接调用 `FactCheckService`，只要应用在运行、Ollama 可用即可打分，
   与 `agent.fact-check.enabled` 是否开启无关。要得到真实的 before/after，需先让 Agent 在两种配置下分别生成回答，
   再用本脚本对同一批用例打分；两次运行的准确率之差，才是可写进简历的“提升幅度”。

### 命令与参数

```bash
# 全量评测
python tools/eval/run_fact_eval.py --base-url http://localhost:8080

# 只评测前 10 条，快速验证连通性
python tools/eval/run_fact_eval.py --base-url http://localhost:8080 --limit 10

# 自定义用例集与报告路径
python tools/eval/run_fact_eval.py --base-url http://localhost:8080 \
    --cases src/main/resources/eval/fact-consistency-cases.jsonl \
    --out tools/eval/report.json
```

参数：`--base-url`（必填）、`--cases`（默认上面的 JSONL）、`--out`（默认 `tools/eval/report.json`）、
`--limit N`、`--timeout 秒数`。脚本仅使用 Python 标准库，无需 `pip install`。

如果报“无法连接后端”，请先启动应用（`mvn spring-boot:run`）并确认 Ollama 已运行。

### 输出怎么看

终端会打印每条用例的 `expected` 与接口返回的 `passed`，随后是**分类别统计表**和**总体准确率**。
JSON 报告包含：

- `evaluatedAt` / `baseUrl` / `totalCases` / `correct` / `accuracy`；
- `perCategory`：每个类别（`consistent` / `data_accuracy` / `target_mismatch` / `logic_gap` / `edge`）的
  `total`、`correct`、`accuracy`；
- `mismatches`：判错的用例 id、期望值、实际 `passed` 与接口返回的 `issues`，便于定位提示词或模型问题。

退出码：全部一致为 `0`，存在不一致为 `1`，便于接入 CI。

## 二、单条行情查询耗时（秒级 → 毫秒级）

`DiagnosticsController` 还提供真实多级缓存路径的基准：

```
GET  /api/diagnostics/cache/stats
POST /api/diagnostics/cache/benchmark?secid=1.600519&rounds=20
```

`benchmark` 先调用一次 `StockMarketService.getQuote` 预热（冷路径回源网络），再对 `rounds` 次热路径调用计时，
返回 `coldPathMs`、`warmAvgMs`、`warmP50Ms`、`warmMaxMs`、`cacheHitRate` 与 `cacheEffective`。

```bash
curl -X POST "http://localhost:8080/api/diagnostics/cache/benchmark?secid=1.600519&rounds=20"
curl "http://localhost:8080/api/diagnostics/cache/stats"
```
