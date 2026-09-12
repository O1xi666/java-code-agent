#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""事实一致性自校验离线评测脚本（仅使用 Python 标准库，无需 pip 安装）。

用法：
    python tools/eval/run_fact_eval.py --base-url http://localhost:8080
    python tools/eval/run_fact_eval.py --base-url http://localhost:8080 --limit 10
    python tools/eval/run_fact_eval.py --base-url http://localhost:8080 \
        --cases src/main/resources/eval/fact-consistency-cases.jsonl \
        --out tools/eval/report.json

脚本读取 JSONL 数据集，把每条用例 POST 到 {base_url}/api/diagnostics/fact-check，
将返回的 passed 字段与用例的 expected 字段比对，输出分类别统计与总体准确率，
并写出 JSON 报告。
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(os.path.dirname(SCRIPT_DIR))
DEFAULT_CASES = os.path.join(REPO_ROOT, "src", "main", "resources", "eval", "fact-consistency-cases.jsonl")
DEFAULT_OUT = os.path.join(REPO_ROOT, "tools", "eval", "report.json")


def load_cases(path):
    cases = []
    with open(path, "r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            line = line.strip()
            if not line:
                continue
            try:
                cases.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise SystemExit("数据集第 %d 行不是合法 JSON：%s" % (line_no, exc))
    return cases


def post_json(url, payload, timeout):
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read().decode("utf-8", "replace")
    return json.loads(body)


def parse_args():
    parser = argparse.ArgumentParser(description="事实一致性自校验离线评测")
    parser.add_argument("--base-url", required=True, help="应用地址，例如 http://localhost:8080")
    parser.add_argument("--cases", default=DEFAULT_CASES, help="JSONL 用例文件路径")
    parser.add_argument("--out", default=DEFAULT_OUT, help="JSON 报告输出路径")
    parser.add_argument("--limit", type=int, default=None, help="只评测前 N 条用例")
    parser.add_argument("--timeout", type=float, default=120.0, help="单次请求超时秒数")
    return parser.parse_args()


def evaluate(args):
    if not os.path.exists(args.cases):
        raise SystemExit("找不到用例文件：%s" % args.cases)

    cases = load_cases(args.cases)
    if args.limit is not None:
        cases = cases[: max(0, args.limit)]
    if not cases:
        raise SystemExit("没有可评测的用例，请检查 --cases / --limit 参数")

    endpoint = args.base_url.rstrip("/") + "/api/diagnostics/fact-check"

    per_category = {}
    mismatches = []
    correct = 0

    for case in cases:
        payload = {
            "question": case.get("question", ""),
            "knowledgeContext": case.get("knowledgeContext", ""),
            "toolObservations": case.get("toolObservations", []),
            "answer": case.get("answer", ""),
        }
        try:
            result = post_json(endpoint, payload, args.timeout)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            result = {"passed": None, "issues": ["HTTP %d: %s" % (exc.code, detail)]}
        except urllib.error.URLError as exc:
            raise SystemExit(
                "无法连接后端 %s：%s\n请先启动应用（mvn spring-boot:run），并确认 Ollama 已在运行。"
                % (args.base_url, exc.reason)
            )
        except (json.JSONDecodeError, ValueError) as exc:
            result = {"passed": None, "issues": ["响应不是合法 JSON：%s" % exc]}

        returned = result.get("passed")
        expected = case.get("expected")
        expected_bool = expected == "pass"
        category = case.get("category", "unknown")
        bucket = per_category.setdefault(category, {"total": 0, "correct": 0})
        bucket["total"] += 1

        ok = isinstance(returned, bool) and returned == expected_bool
        if ok:
            correct += 1
            bucket["correct"] += 1
        else:
            mismatches.append({
                "id": case.get("id"),
                "category": category,
                "expected": expected,
                "returnedPassed": returned,
                "issues": result.get("issues", []),
                "fixInstructions": result.get("fixInstructions", ""),
            })
        print("[%s] %-4s category=%-15s expected=%-4s returned=%s"
              % ("OK  " if ok else "FAIL", case.get("id"), category, expected, returned))

    total = len(cases)
    accuracy = correct / total if total else 0.0

    print("")
    print("按类别统计：")
    print("%-16s%6s%9s%11s" % ("category", "total", "correct", "accuracy"))
    for category in sorted(per_category):
        bucket = per_category[category]
        acc = bucket["correct"] / bucket["total"] if bucket["total"] else 0.0
        print("%-16s%6d%9d%10.1f%%" % (category, bucket["total"], bucket["correct"], acc * 100))
    print("%-16s%6d%9d%10.1f%%" % ("TOTAL", total, correct, accuracy * 100))
    print("")
    print("总体准确率：%d/%d = %.1f%%" % (correct, total, accuracy * 100))
    if mismatches:
        print("不一致用例：%s" % ", ".join(str(m["id"]) for m in mismatches))

    report = {
        "evaluatedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
        "baseUrl": args.base_url,
        "totalCases": total,
        "correct": correct,
        "accuracy": round(accuracy, 4),
        "perCategory": {
            category: {
                "total": bucket["total"],
                "correct": bucket["correct"],
                "accuracy": round(bucket["correct"] / bucket["total"], 4) if bucket["total"] else 0.0,
            }
            for category, bucket in sorted(per_category.items())
        },
        "mismatches": mismatches,
    }

    out_path = os.path.abspath(args.out)
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print("")
    print("报告已写入：%s" % out_path)

    return 0 if not mismatches else 1


def main():
    args = parse_args()
    sys.exit(evaluate(args))


if __name__ == "__main__":
    main()
