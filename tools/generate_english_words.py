"""从ECDICT生成HarleyApp使用的5000词离线JSON资源。"""

from __future__ import annotations

import argparse
import csv
import json
import re
import subprocess
from pathlib import Path
from typing import Any


TARGET_WORD_COUNT = 5_000
DEFAULT_OUTPUT = Path("app/src/main/assets/english_words.json")
LEGACY_KOTLIN_PATH = "app/src/main/java/com/example/harleyapp/data/BundledEnglishWords.kt"
WORD_PATTERN = re.compile(r"^[A-Za-z][A-Za-z'-]*$")
LEGACY_WORD_PATTERN = re.compile(
    r'^\s*word\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)"\),?\s*$'
)
CORE_TAGS = {"zk", "gk", "cet4", "cet6", "ky", "ielts", "toefl", "gre"}


def parse_arguments() -> argparse.Namespace:
    """
    读取生成器命令行参数。

    使用方法：
    在项目根目录执行`python tools/generate_english_words.py C:\\tmp\\ecdict.csv`。如需输出到
    临时位置，可追加`--output`参数；正式资源默认写入Android的assets目录。

    @return 包含ECDICT CSV输入路径和JSON输出路径的参数对象。
    """

    parser = argparse.ArgumentParser(description="生成HarleyApp的5000词离线JSON词库")
    parser.add_argument("ecdict_csv", type=Path, help="ECDICT ecdict.csv文件路径")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="JSON输出路径")
    return parser.parse_args()


def load_curated_words(output_path: Path) -> list[dict[str, Any]]:
    """
    读取并保留首版122个经过人工整理的单词内容。

    使用方法：
    重新生成时优先从现有JSON读取`curated=true`项目；首次迁移且JSON尚不存在时，从Git当前
    HEAD中的旧Kotlin词库提取。这样升级前后的单词ID和中英例句保持一致，已有学习进度不会丢失。

    @param output_path 即将写入的词库JSON路径。
    @return 按原顺序排列的人工整理单词；无法找到来源时抛出异常停止生成。
    """

    if output_path.exists():
        root = json.loads(output_path.read_text(encoding="utf-8"))
        curated_words = [word for word in root.get("words", []) if word.get("curated") is True]
        if curated_words:
            return curated_words

    completed = subprocess.run(
        ["git", "show", f"HEAD:{LEGACY_KOTLIN_PATH}"],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    curated_words: list[dict[str, Any]] = []
    for line in completed.stdout.splitlines():
        match = LEGACY_WORD_PATTERN.match(line)
        if match is None:
            continue
        spelling, meaning_zh, example_en, example_zh = match.groups()
        curated_words.append(
            {
                "id": spelling.lower(),
                "word": spelling,
                "phonetic": "",
                "definitionEn": "",
                "meaningZh": meaning_zh,
                "exampleEn": example_en,
                "exampleZh": example_zh,
                "tags": [],
                "curated": True,
            }
        )

    if len(curated_words) != 122:
        raise RuntimeError(f"应提取122个人工单词，实际得到{len(curated_words)}个")
    return curated_words


def normalized_lines(value: str, max_length: int) -> str:
    """
    清理CSV释义中的空行和重复空白，并限制单条内容长度。

    @param value ECDICT提供的原始英文或中文释义。
    @param max_length 写入APK前允许保留的最大字符数。
    @return 使用换行分隔、适合手机详情页显示的文本；原内容为空时返回空字符串。
    """

    lines: list[str] = []
    for raw_line in value.replace("\r", "\n").split("\n"):
        line = " ".join(raw_line.split())
        if not line or line in lines:
            continue
        lines.append(line)
    result = "\n".join(lines)
    if len(result) <= max_length:
        return result
    return result[: max_length - 1].rstrip() + "…"


def positive_number(value: str, fallback: int = 9_999_999) -> int:
    """
    把ECDICT词频或星级字段转换为非负整数。

    @param value CSV中的数字文本。
    @param fallback 字段为空、非法或小于等于0时使用的兜底值。
    @return 可直接参与排序的整数。
    """

    try:
        number = int(value)
    except (TypeError, ValueError):
        return fallback
    return number if number > 0 else fallback


def row_priority(row: dict[str, str]) -> tuple[int, int, int, str]:
    """
    计算词条的学习推荐顺序。

    使用方法：
    先选择牛津核心、中高考、四六级等学习价值明确的词，再结合当代语料和BNC词频排序；
    柯林斯星级只作为同类词的辅助条件。该规则避免简单网页词频表中的网址、缩写和专名占据前列。

    @param row ECDICT的一行CSV词条。
    @return 可交给`sorted`使用的四段排序键，数值越小越优先。
    """

    tags = set(row.get("tag", "").lower().split())
    is_oxford = row.get("oxford", "") == "1"
    if is_oxford:
        group = 0
    elif tags.intersection({"zk", "gk"}):
        group = 1
    elif "cet4" in tags:
        group = 2
    elif "cet6" in tags or "ky" in tags:
        group = 3
    elif tags.intersection({"ielts", "toefl", "gre"}):
        group = 4
    else:
        group = 5

    frequency = min(positive_number(row.get("frq", "")), positive_number(row.get("bnc", "")))
    collins = positive_number(row.get("collins", ""), fallback=0)
    return group, frequency, -collins, row.get("word", "").lower()


def is_learning_candidate(row: dict[str, str]) -> bool:
    """
    判断ECDICT词条是否适合加入5000词学习列表。

    @param row ECDICT的一行CSV词条。
    @return 单词拼写、中文释义和词形均符合条件时返回True，否则返回False。
    """

    spelling = row.get("word", "").strip()
    if not WORD_PATTERN.fullmatch(spelling):
        return False
    if len(spelling) == 1 and spelling.lower() not in {"a", "i"}:
        return False
    if not row.get("translation", "").strip():
        return False

    # 带0:前缀的是其他词的时态、复数等派生形式，学习列表优先保留原形。
    exchange_parts = row.get("exchange", "").split("/")
    if any(part.startswith("0:") for part in exchange_parts):
        return False
    return True


def create_asset_word(row: dict[str, str]) -> dict[str, Any]:
    """
    把ECDICT的一行转换为App约定的紧凑JSON对象。

    @param row 已通过候选校验的ECDICT CSV词条。
    @return 包含稳定ID、音标、双语释义和学习标签的单词对象。
    """

    spelling = row["word"].strip()
    tags = [tag for tag in row.get("tag", "").lower().split() if tag in CORE_TAGS]
    if row.get("oxford", "") == "1":
        tags.insert(0, "oxford")
    return {
        "id": spelling.lower(),
        "word": spelling,
        "phonetic": normalized_lines(row.get("phonetic", ""), 100),
        "definitionEn": normalized_lines(row.get("definition", ""), 600),
        "meaningZh": normalized_lines(row.get("translation", ""), 600),
        "exampleEn": "",
        "exampleZh": "",
        "tags": list(dict.fromkeys(tags)),
        "curated": False,
    }


def generate_words(ecdict_csv: Path, curated_words: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """
    合并人工首版词条与ECDICT候选，生成恰好5000个不重复单词。

    @param ecdict_csv 完整ECDICT CSV文件路径。
    @param curated_words 必须原样保留并放在前部的人工词条。
    @return 按推荐顺序排列、ID不重复且总数为5000的单词列表。
    """

    with ecdict_csv.open("r", encoding="utf-8", newline="") as source:
        candidates = [row for row in csv.DictReader(source) if is_learning_candidate(row)]
    candidates.sort(key=row_priority)

    candidates_by_id = {
        row["word"].strip().lower(): row
        for row in candidates
    }
    result: list[dict[str, Any]] = []
    for original_word in curated_words:
        curated_word = dict(original_word)
        source_row = candidates_by_id.get(str(curated_word["id"]).lower())
        if source_row is not None:
            source_word = create_asset_word(source_row)
            if not curated_word.get("phonetic"):
                curated_word["phonetic"] = source_word["phonetic"]
            if not curated_word.get("definitionEn"):
                curated_word["definitionEn"] = source_word["definitionEn"]
            curated_word["tags"] = list(
                dict.fromkeys([*curated_word.get("tags", []), *source_word["tags"]])
            )
        result.append(curated_word)

    seen_ids = {str(word["id"]).lower() for word in result}
    for row in candidates:
        word = create_asset_word(row)
        if word["id"] in seen_ids:
            continue
        result.append(word)
        seen_ids.add(word["id"])
        if len(result) == TARGET_WORD_COUNT:
            break

    if len(result) != TARGET_WORD_COUNT:
        raise RuntimeError(f"无法生成{TARGET_WORD_COUNT}词，实际只有{len(result)}词")
    return result


def write_asset(output_path: Path, words: list[dict[str, Any]]) -> None:
    """
    以UTF-8格式写入Android assets词库，并附带可追溯的数据来源信息。

    @param output_path 最终JSON文件路径。
    @param words 已完成校验的5000词列表。
    @return 无返回值。
    """

    output_path.parent.mkdir(parents=True, exist_ok=True)
    root = {
        "schemaVersion": 1,
        "wordCount": len(words),
        "source": {
            "name": "ECDICT",
            "repository": "https://github.com/skywind3000/ECDICT",
            "license": "MIT",
        },
        "words": words,
    }
    output_path.write_text(
        json.dumps(root, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )


def main() -> None:
    """
    执行5000词生成、写入和关键搜索样例校验。

    使用方法：
    直接运行本脚本。结束前会确认总数、ID唯一性以及`good`确实存在，任一条件不满足都会抛出
    异常并返回非零退出码，避免把不完整词库打进APK。

    @return 无返回值。
    """

    arguments = parse_arguments()
    curated_words = load_curated_words(arguments.output)
    words = generate_words(arguments.ecdict_csv, curated_words)
    ids = [str(word["id"]) for word in words]
    if len(ids) != len(set(ids)):
        raise RuntimeError("生成结果存在重复单词ID")
    if "good" not in ids:
        raise RuntimeError("生成结果缺少搜索验收词good")

    write_asset(arguments.output, words)
    print(f"Generated {len(words)} words at {arguments.output}")


if __name__ == "__main__":
    main()
