"""Generate deterministic, evidence-grounded QA splits for dataset-v1.0.

The generator uses only facts deliberately authored in the canonical corpus.  It
does not call an LLM, which keeps the golden labels reproducible and auditable.
"""
from collections import Counter
from pathlib import Path
import json
import random


ROOT = Path(__file__).resolve().parents[1] / "dataset-v1.0"
MANIFEST = ROOT / "manifest" / "documents.jsonl"
QA_DIR = ROOT / "qa"
SPLIT_SIZES = {"train": 40, "dev": 40, "test": 160}
RNG_SEED = 20260801
SPLIT_ALLOCATION = {
    "direct_fact": {"train": 13, "dev": 13, "test": 54},
    "paraphrase": {"train": 7, "dev": 7, "test": 31},
    "multi_condition": {"train": 6, "dev": 6, "test": 23},
    "numeric_fact": {"train": 4, "dev": 4, "test": 17},
    "cross_section": {"train": 3, "dev": 3, "test": 14},
    "similar_concept": {"train": 2, "dev": 2, "test": 11},
    "unanswerable": {"train": 5, "dev": 5, "test": 10},
}


def line_after(text: str, prefix: str) -> str:
    return next(line for line in text.splitlines() if line.startswith(prefix))


def question(question_id, question, answer, evidences, category, difficulty,
             must_contain):
    return {
        "id": question_id,
        "question": question,
        "answerable": True,
        "referenceAnswer": answer,
        "evidences": evidences,
        "expectedBehavior": "ANSWER",
        "category": category,
        "difficulty": difficulty,
        "mustContain": must_contain,
    }


def evidence(record, heading, quote):
    return {"documentKey": record["documentKey"], "heading": heading, "quote": quote}


def records_with_facts():
    records = [json.loads(line) for line in MANIFEST.read_text(encoding="utf-8").splitlines()]
    result = []
    for index, record in enumerate(records, start=1):
        canonical = ROOT / record["canonicalTextFile"]
        text = canonical.read_text(encoding="utf-8")
        code = f"{record['domain'].upper()}-{index:03d}"
        rule = line_after(text, "2. ")
        success = line_after(text, "3. ")
        boundary = line_after(text, "调用方负责")
        exception = line_after(text, "当依赖服务")
        result.append({
            **record,
            "code": code,
            "retry": index % 4 + 2,
            "active": line_after(text, "1. "),
            "rule": rule,
            "success": success,
            "boundary": boundary,
            "exception": exception,
        })
    return result


def answerable_questions(records):
    questions = []
    for index, item in enumerate(records, start=1):
        key = item["documentKey"]
        core = evidence(item, "核心规则", item["rule"])
        active = evidence(item, "核心规则", item["active"])
        success = evidence(item, "核心规则", item["success"])
        boundary = evidence(item, "责任边界", item["boundary"])

        questions.append(question(
            f"q-{key}-active", f"《{item['title']}》在什么租户状态下允许继续处理？",
            "只有所属租户状态为 ACTIVE 时才允许继续处理。", [active], "direct_fact", "easy", ["ACTIVE"]))
        questions.append(question(
            f"q-{key}-success", f"《{item['title']}》中的 SUCCESS 表示什么？",
            "SUCCESS 只描述本规则的处理结果，不能推导成员、文件或文档的其他状态。",
            [success], "direct_fact", "easy", ["SUCCESS", "处理结果"]))
        questions.append(question(
            f"q-{key}-ownership", f"在《{item['title']}》中，用户上传内容与系统生成内容应如何标记？",
            "用户直接上传的内容标记为 ORIGINAL，系统生成的内容标记为 DERIVED。",
            [boundary], "paraphrase", "medium", ["ORIGINAL", "DERIVED"]))
        if index <= 35:
            questions.append(question(
                f"q-{key}-window", f"规则 {item['code']} 的默认执行窗口和最大重试次数分别是什么？",
                f"默认执行窗口为每周三 02:17 至 02:47，最大重试次数为 {item['retry']} 次。",
                [core], "multi_condition", "medium", ["每周三", "02:17", "02:47", str(item["retry"]) ]))
        if index <= 25:
            questions.append(question(
                f"q-{key}-retry", f"{item['code']} 最多可以重试几次？",
                f"最多重试 {item['retry']} 次。", [core], "numeric_fact", "easy", [str(item["retry"]) ]))
        if index <= 5:
            questions.append(question(
                f"q-{key}-derived-rephrase", f"平台自动产生的数据和用户直接提交的数据，分别使用什么标记？",
                "系统生成的内容标记为 DERIVED，用户直接上传的内容标记为 ORIGINAL。",
                [boundary], "paraphrase", "medium", ["ORIGINAL", "DERIVED"]))

    # 20 deliberately multi-document questions: each requires evidence from two documents.
    for pair_no in range(20):
        left, right = records[pair_no * 2], records[pair_no * 2 + 1]
        questions.append(question(
            f"q-cross-{pair_no + 1:02d}-retries",
            f"比较规则 {left['code']} 与 {right['code']}：二者的最大重试次数分别是多少？",
            f"{left['code']} 最多重试 {left['retry']} 次；{right['code']} 最多重试 {right['retry']} 次。",
            [evidence(left, "核心规则", left["rule"]), evidence(right, "核心规则", right["rule"])],
            "cross_section", "hard", [left["code"], str(left["retry"]), right["code"], str(right["retry"])]))

    # 15 semantic-confusion questions: SUCCESS is intentionally not a synonym for ACTIVE.
    for item in records[:15]:
        questions.append(question(
            f"q-{item['documentKey']}-success-vs-active",
            f"《{item['title']}》中的 SUCCESS 是否意味着成员、文件或文档处于 ACTIVE？",
            "不意味着。SUCCESS 只描述本规则的处理结果，不能推导成员、文件或文档的其他状态。",
            [evidence(item, "核心规则", item["success"])], "similar_concept", "hard", ["不", "SUCCESS", "处理结果"]))
    return questions


def refusal_questions():
    prompts = [
        "系统是否支持图片 OCR？", "可以上传 PNG 图片作为知识文档吗？", "文档能否自动翻译为英文？",
        "是否提供人脸识别登录？", "系统是否支持视频内容解析？", "能否从网页 URL 自动抓取知识？",
        "是否内置企业邮箱发送能力？", "是否支持语音转写？", "能否导出用户的聊天记录？",
        "系统是否提供支付和订阅功能？", "是否支持 Excel 公式计算？", "能否部署到 Kubernetes？",
        "是否支持多模态图片问答？", "是否提供 Slack 消息同步？", "可否自动生成数据库迁移脚本？",
        "是否支持扫描件的手写文字识别？", "能否识别身份证信息？", "是否提供浏览器插件？",
        "是否支持实时协同编辑？", "系统是否能自动发送短信通知？",
    ]
    return [{
        "id": f"q-negative-{index:03d}", "question": prompt, "answerable": False,
        "referenceAnswer": None, "evidences": [], "expectedBehavior": "REFUSE",
        "category": "unanswerable", "difficulty": "medium", "mustContain": [],
    } for index, prompt in enumerate(prompts, start=1)]


def split_questions(questions):
    """Distribute each category round-robin after a deterministic shuffle."""
    groups = {}
    for item in questions:
        groups.setdefault(item["category"], []).append(item)
    splits = {name: [] for name in SPLIT_SIZES}
    for category, group in groups.items():
        random.Random(f"{RNG_SEED}:{category}").shuffle(group)
        targets = SPLIT_ALLOCATION[category]
        assert sum(targets.values()) == len(group), (category, len(group), targets)
        start = 0
        for split in ("train", "dev", "test"):
            splits[split].extend(group[start:start + targets[split]])
            start += targets[split]
    for split, items in splits.items():
        random.Random(f"{RNG_SEED}:{split}").shuffle(items)
        assert len(items) == SPLIT_SIZES[split], (split, len(items))
    return splits


def main():
    records = records_with_facts()
    questions = answerable_questions(records) + refusal_questions()
    assert len(questions) == 240
    assert len({item["id"] for item in questions}) == len(questions)
    splits = split_questions(questions)
    QA_DIR.mkdir(parents=True, exist_ok=True)
    for split, items in splits.items():
        path = QA_DIR / f"{split}.jsonl"
        path.write_text("\n".join(json.dumps(item, ensure_ascii=False) for item in items) + "\n", encoding="utf-8")
        print(f"{path.name}: {len(items)} questions, {dict(Counter(x['category'] for x in items))}")


if __name__ == "__main__":
    main()
