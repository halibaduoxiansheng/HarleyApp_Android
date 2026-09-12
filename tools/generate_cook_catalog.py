#!/usr/bin/env python3
"""把 HowToCook 固定版本转换为 HarleyApp 可离线读取的菜谱目录。"""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
import subprocess
import unicodedata
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import quote

from PIL import Image, ImageOps


CATEGORY_DEFINITIONS = (
    ("vegetable_dish", "素菜", "素"),
    ("meat_dish", "荤菜", "肉"),
    ("aquatic", "水产", "鲜"),
    ("breakfast", "早餐", "早"),
    ("staple", "主食", "饭"),
    ("semi-finished", "半成品加工", "简"),
    ("soup", "汤与粥", "汤"),
    ("drink", "饮料", "饮"),
    ("condiment", "酱料和其它材料", "酱"),
    ("dessert", "甜品", "甜"),
)

CATEGORY_LOOKUP = {
    category_id: {
        "id": category_id,
        "displayName": title,
        "description": f"HowToCook {title}菜谱",
        "sortOrder": sort_order,
        "symbol": symbol,
    }
    for sort_order, (category_id, title, symbol) in enumerate(CATEGORY_DEFINITIONS)
}

IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp"}
MARKDOWN_LINK_PATTERN = re.compile(r"\[([^]]+)]\(([^)]+)\)")
HEADING_PATTERN = re.compile(r"^(#{1,6})\s+(.+?)\s*$")
BULLET_PATTERN = re.compile(r"^\s*[-+*]\s+(.+?)\s*$")
NUMBERED_PATTERN = re.compile(r"^\s*\d+[.)、]\s*(.+?)\s*$")
TABLE_SEPARATOR_PATTERN = re.compile(r"^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*$")

TOOL_WORDS = (
    "砧板",
    "案板",
    "菜刀",
    "水果刀",
    "炒锅",
    "平底锅",
    "汤锅",
    "蒸锅",
    "砂锅",
    "高压锅",
    "压力锅",
    "不粘锅",
    "漏勺",
    "汤勺",
    "锅铲",
    "筷子",
    "烤箱",
    "空气炸锅",
    "微波炉",
    "电饭煲",
    "料理机",
    "打蛋器",
    "模具",
    "保鲜膜",
    "锡纸",
    "滤网",
    "厨房纸",
    "牙签",
    "容器",
    "杯子",
    "洗菜盆",
    "小锅",
    "煮锅",
    "灶台",
    "雪克杯",
    "榨汁机",
    "压汁器",
    "搅拌机",
    "搅拌器",
    "筛网",
    "克数称",
    "电子秤",
    "厨房秤",
    "冰箱",
    "蒸箱",
    "烤盘",
    "烤网",
    "铝箔纸",
    "锡箔纸",
    "塑料簸箕",
    "吸油纸",
    "烧烤炉",
    "燃气灶",
    "密封袋",
    "削皮刀",
    "防烫盘夹",
    "厨房用夹",
    "面包机",
    "轻食机",
    "定时器",
    "圆碟子",
    "吧勺",
    "打火机",
    "调理机",
    "果汁机",
    "量酒器",
    "吸管",
    "蘸料碟",
    "小刀",
    "刨丝器",
    "捣药罐",
    "擀面杖",
    "铲子",
    "刷子",
    "蒸架",
    "蒸笼垫",
    "搅拌棒",
    "雪克瓶",
    "搅拌工具",
)

SEASONING_WORDS = (
    "水",
    "盐",
    "糖",
    "油",
    "醋",
    "酱油",
    "生抽",
    "老抽",
    "料酒",
    "淀粉",
    "胡椒",
    "味精",
    "鸡精",
    "蚝油",
    "豆瓣酱",
    "辣椒粉",
    "孜然",
    "花椒",
    "香油",
)

TOOL_NAME_SUFFIXES = (
    "锅",
    "盆",
    "碗",
    "杯",
    "杯子",
    "纱布",
    "手套",
    "温度计",
    "过滤袋",
    "烤架",
    "称",
    "锅盖",
    "勺子",
    "盘子",
)

INGREDIENT_ALIASES = {
    "油": "食用油",
    "植物油": "食用油",
    "食用植物油": "食用油",
    "炒菜油": "食用油",
    "食盐": "盐",
    "食用盐": "盐",
    "糖": "白糖",
    "白砂糖": "白糖",
    "细砂糖": "白糖",
    "砂糖": "白糖",
    "番茄": "西红柿",
    "马铃薯": "土豆",
    "洋芋": "土豆",
    "生姜": "姜",
    "姜片": "姜",
    "姜丝": "姜",
    "姜末": "姜",
    "蒜瓣": "蒜",
    "大蒜": "蒜",
    "蒜末": "蒜",
    "蒜蓉": "蒜",
    "鸡蛋的鸡蛋清": "鸡蛋清",
    "清水": "水",
    "凉水": "水",
    "冷水": "水",
    "温水": "水",
    "开水": "水",
    "普通食用油": "食用油",
    "高筋": "高筋面粉",
    "爪猪前肘": "猪前肘",
    "带脚爪猪前肘": "猪前肘",
}

REQUIREMENT_QUALIFIER_ONLY_NAMES = {
    "带皮",
    "带脚",
}

REQUIREMENT_GROUP_KINDS = {
    "原料": "ingredient",
    "材料": "ingredient",
    "主料": "ingredient",
    "配料": "ingredient",
    "辅料": "seasoning",
    "调料": "seasoning",
    "调味料": "seasoning",
    "香料": "seasoning",
    "工具": "tool",
    "厨具": "tool",
    "设备": "tool",
}

REQUIREMENT_NOTE_PREFIXES = (
    "注:",
    "注：",
    "注意:",
    "注意：",
    "说明:",
    "说明：",
    "原则:",
    "原则：",
    "提示:",
    "提示：",
    "建议购买",
    "炒糖色过程",
    "参见",
    "能够",
)


def parse_args() -> argparse.Namespace:
    """解析命令行参数。

    使用方法：
    运行脚本时传入 HowToCook 仓库根目录与 HarleyApp 的 cook assets 输出目录；可选参数用于
    固定上游提交和控制图片最大边长、WebP 质量。脚本不会联网，也不会删除输出目录中的旧文件。

    @return 包含源目录、输出目录、提交号和图片压缩参数的命令行命名空间。
    """

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="HowToCook repository root")
    parser.add_argument("--output", type=Path, required=True, help="HarleyApp cook asset directory")
    parser.add_argument("--commit", default="", help="Pinned HowToCook commit SHA")
    parser.add_argument("--max-image-edge", type=int, default=1280)
    parser.add_argument("--webp-quality", type=int, default=78)
    parser.add_argument(
        "--strict-images",
        action="store_true",
        help="Fail when a recipe references a missing local image",
    )
    return parser.parse_args()


def resolve_commit(source: Path, configured_commit: str) -> str:
    """取得写入目录的上游提交号。

    使用方法：
    生成固定快照时优先传入已经核对的完整提交号；未传入时，本函数会从源目录的本地 Git 元数据
    读取 HEAD。函数不会执行 fetch、pull 等联网操作。

    @param source HowToCook 本地仓库根目录。
    @param configured_commit 调用者显式指定的提交号，允许为空。
    @return 40 位小写十六进制提交号。
    """

    commit = configured_commit.strip().lower()
    if not commit:
        completed = subprocess.run(
            ["git", "-C", str(source), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        )
        commit = completed.stdout.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError(f"Invalid commit SHA: {commit!r}")
    return commit


def markdown_images(value: str) -> list[tuple[int, int, str, str]]:
    """扫描一行中的 Markdown 图片，并正确处理目标路径内的成对括号。

    使用方法：
    HowToCook 存在 `石凉粉(冰粉)成品1.jpg` 这类文件名，普通的“匹配到第一个右括号”正则会截断
    路径。本函数从 `](` 后逐字符计算括号层级，并保留转义字符，确保本地图片不会静默遗漏。

    @param value 可能包含零到多张 Markdown 图片的一行文字。
    @return `(起始索引, 结束索引, 替代文字, 目标地址)`列表，结束索引指向语法后的首字符。
    """

    matches: list[tuple[int, int, str, str]] = []
    cursor = 0
    while True:
        start = value.find("![", cursor)
        if start < 0:
            break
        alt_end = value.find("](", start + 2)
        if alt_end < 0:
            break
        target_start = alt_end + 2
        depth = 1
        index = target_start
        while index < len(value):
            character = value[index]
            if character == "\\" and index + 1 < len(value):
                index += 2
                continue
            if character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
                if depth == 0:
                    matches.append(
                        (start, index + 1, value[start + 2 : alt_end], value[target_start:index])
                    )
                    cursor = index + 1
                    break
            index += 1
        else:
            break
    return matches


def strip_markdown_images(value: str) -> str:
    """删除一行中的完整 Markdown 图片语法。

    @param value 原始行。
    @return 保留图片前后普通文字的结果。
    """

    matches = markdown_images(value)
    if not matches:
        return value
    pieces: list[str] = []
    cursor = 0
    for start, end, _, _ in matches:
        pieces.append(value[cursor:start])
        cursor = end
    pieces.append(value[cursor:])
    return "".join(pieces)


def clean_markdown_text(value: str) -> str:
    """把一段 Markdown 行内语法转换为适合原生文本组件显示的文字。

    @param value 原始 Markdown 文字，可包含强调、链接、HTML 标签和转义字符。
    @return 去除图片、链接地址和常见行内标记后的可读文字。
    """

    value = strip_markdown_images(value)
    value = MARKDOWN_LINK_PATTERN.sub(lambda match: match.group(1), value)
    value = re.sub(r"<br\s*/?>", "\n", value, flags=re.IGNORECASE)
    value = re.sub(r"<[^>]+>", "", value)
    value = value.replace("**", "").replace("__", "").replace("`", "")
    value = value.replace("\\*", "*").replace("\\_", "_")
    value = value.replace("\\times", "×").replace("\\pm", "±")
    value = re.sub(r"\$([^$]+)\$", r"\1", value)
    value = html.unescape(value)
    return re.sub(r"[ \t]+", " ", value).strip()


def normalize_title(raw_title: str, fallback: str) -> str:
    """从一级标题中恢复简洁菜名。

    @param raw_title Markdown 一级标题文字。
    @param fallback 一级标题缺失时使用的文件名。
    @return 去掉“的做法”等固定后缀后的菜名。
    """

    title = clean_markdown_text(raw_title).strip() or fallback
    for suffix in ("的做法", "做法"):
        if title.endswith(suffix) and len(title) > len(suffix):
            return title[: -len(suffix)].strip()
    return title


def section_lines(lines: list[str], section_title: str) -> list[str]:
    """提取指定二级标题下、下一个二级标题之前的原始行。

    @param lines 一篇菜谱按行拆分后的全部内容。
    @param section_title 需要查找的二级标题文字。
    @return 对应章节的原始行；章节不存在时返回空列表。
    """

    collecting = False
    collected: list[str] = []
    for line in lines:
        heading = HEADING_PATTERN.match(line)
        if heading and len(heading.group(1)) == 2:
            title = clean_markdown_text(heading.group(2))
            if collecting:
                break
            collecting = title == section_title
            continue
        if collecting:
            collected.append(line)
    return collected


def normalize_ingredient_id(value: str) -> str:
    """生成跨菜谱可复用的食材标识。

    使用方法：
    导入“必备原料和工具”条目及其“或”“/”候选项时调用。函数只做保守的符号、数量、括号说明
    清理和少量明确同义词归一化，不会把用途不同的生抽、老抽等材料擅自视为等价。

    @param value 原始食材名称或候选名称。
    @return 用于选材与匹配的稳定中文标识；无法得到有效名称时返回空字符串。
    """

    normalized = unicodedata.normalize("NFKC", clean_markdown_text(value)).lower()
    normalized = re.sub(
        r"^(?:\[\s*可选\s*\]|【\s*可选\s*】|[（(]\s*可选\s*[）)])\s*",
        "",
        normalized,
    )
    normalized = re.sub(r"^未过期的", "", normalized)
    while re.search(r"（[^（）]*）|\([^()]*\)", normalized):
        normalized = re.sub(r"（[^（）]*）|\([^()]*\)", "", normalized)
    normalized = re.sub(
        r"^(?:原料|材料|主料|配料|辅料|调料|调味料|香料|工具|厨具|设备|必备|可选)\s*[:：]\s*",
        "",
        normalized,
    )
    normalized = re.split(r"(?:——|—|，?参见|，?详见)", normalized, maxsplit=1)[0]
    normalized = re.sub(r"^(?:可以是|也可以是|或者是|或是|例如|比如|推荐|切块的)", "", normalized)
    quantity = r"(?:\d+(?:\.\d+)?(?:\s*[-~～至]\s*\d+(?:\.\d+)?)?|[一二两三四五六七八九十半]+)"
    unit_pattern = r"(?:克|千克|斤|两|毫升|升|个|只|颗|粒|枚|片|瓣|根|段|条|头|勺|匙|杯|份|盒|袋|包|瓶|罐|块|把|张|双|碗)"
    normalized = re.sub(
        r"^\d+(?:\.\d+)?\s*(?:cm|mm|m)(?![a-z])\s*(?:以上|以下)?(?:的)?",
        "",
        normalized,
    )
    normalized = re.sub(rf"^{quantity}\s*(?:大|小)?{unit_pattern}\s*", "", normalized)
    normalized = re.sub(r"^\d+(?:\.\d+)?\s*(?:kg|ml|g|l)\b\s*", "", normalized)
    normalized = re.sub(rf"^(.+?)\s*{quantity}\s*(?:大|小)?{unit_pattern}(?:\s*.*)?$", r"\1", normalized)
    normalized = re.sub(
        r"^(.+?)\s*\d+(?:\.\d+)?(?:\s*[-~～至]\s*\d+(?:\.\d+)?)?\s*(?:kg|ml|g|l)\b.*$",
        r"\1",
        normalized,
    )
    normalized = re.sub(r"^(?:适量|少量|若干|少许)", "", normalized)
    normalized = re.sub(r"(?:适量|少量|若干|少许)$", "", normalized)
    normalized = re.sub(r"(?:二选一|任选其一|任选一种|任意一种)$", "", normalized)
    normalized = re.sub(r"等(?:原料|材料|配料|调料|肉类|蛋类|豆制品类|蔬菜类)?$", "", normalized)
    normalized = re.sub(r"(?:品牌不限|均可|也行|各)$", "", normalized)
    normalized = re.sub(r"[\s·,，。;；:：、]+", "", normalized)
    normalized = re.sub(r"[^0-9a-z\u3400-\u9fff-]", "", normalized)
    for tool_word in sorted(TOOL_WORDS, key=len, reverse=True):
        if tool_word in normalized:
            return tool_word
    return INGREDIENT_ALIASES.get(normalized, normalized)


def classify_requirement(name: str, subsection: str, forced_kind: str = "") -> str:
    """判断必备项属于食材、调料还是工具。

    @param name 清理后的必备项名称。
    @param subsection 当前三级标题，用于识别上游明确标出的工具分组。
    @param forced_kind 由“工具：”“调味料：”等明确分组给出的类型；为空时完全自动判断。
    @return `tool`、`seasoning` 或 `ingredient`。
    """

    if forced_kind == "tool" or any(word in subsection for word in ("工具", "厨具", "设备")):
        return "tool"
    normalized_name = normalize_ingredient_id(name)
    normalized_alternatives = {
        normalize_ingredient_id(part)
        for part in re.split(r"\s*(?:或者|或是|或|/|／|\bor\b)\s*", name, flags=re.IGNORECASE)
    }
    if any(word in name for word in TOOL_WORDS) or normalized_name.endswith(TOOL_NAME_SUFFIXES):
        return "tool"
    if forced_kind == "seasoning":
        return "seasoning"
    if (
        normalized_name in SEASONING_WORDS
        or any(candidate in SEASONING_WORDS for candidate in normalized_alternatives)
        or normalized_name.endswith(
        ("油", "盐", "糖", "醋", "酱", "酱油", "生抽", "老抽", "料酒", "淀粉", "胡椒粉", "辣椒粉", "孜然粉", "花椒", "香油", "调味料", "香料", "味精", "鸡精", "蚝油")
        )
    ):
        return "seasoning"
    return "ingredient"


def remove_parenthetical_notes(value: str) -> str:
    """删除食材名中的括号说明，同时兼容连续或嵌套一层的括号。

    @param value 已清理行内Markdown的需求文字。
    @return 仅保留括号外主体的文字。
    """

    result = value
    previous = None
    while result != previous:
        previous = result
        result = re.sub(r"（[^（）]*）|\([^()]*\)", "", result)
    return result.strip()


def parenthetical_contents(value: str) -> list[str]:
    """提取中英文圆括号中的文字，并兼容嵌套括号。

    使用方法：
    在删除括号说明前调用，用于保留“（或高筋面粉）”“（可用料酒代替）”这类确实影响
    食材匹配的替代信息。普通品牌、口味和操作说明仍由后续的保守过滤规则排除。

    @param value 可能含中英文圆括号及嵌套括号的原始需求文字。
    @return 按右括号出现顺序排列的括号内容；未闭合括号不会产生结果。
    """

    pairs = {")": "(", "）": "（"}
    stack: list[tuple[str, int]] = []
    contents: list[str] = []
    for index, character in enumerate(value):
        if character in "(（":
            stack.append((character, index))
            continue
        expected_opening = pairs.get(character)
        if expected_opening is None or not stack:
            continue
        opening, opening_index = stack[-1]
        if opening != expected_opening:
            continue
        stack.pop()
        content = value[opening_index + 1 : index].strip()
        if content:
            contents.append(content)
    return contents


def explicit_alternative_ids(
    value: str,
    allow_bare_choices: bool = True,
) -> list[str]:
    """从括号或“可用某物替代”文字中提取简单的一选一候选食材。

    使用方法：
    传入必备项或其“计算”用量行。本函数只接受单个候选或由“或、顿号”连接的并列候选；
    出现加号、“和/与/以及”等联合配方时直接放弃，避免把“蚝油+白糖”错误理解为任一项即可。

    @param value 含替代说明的原始需求或用量文字。
    @param allow_bare_choices true时允许“玉米淀粉或土豆淀粉”这种无提示词的简短候选；解析
    计算公式时应传false，避免把除号、操作方式或数值区间误识别成食材。
    @return 去重后的规范食材ID；只有描述、做法或联合替代配方时返回空列表。
    """

    cleaned_value = clean_markdown_text(value)
    candidate_sources = parenthetical_contents(cleaned_value)
    text_without_parentheses = remove_parenthetical_notes(cleaned_value)
    if any(word in text_without_parentheses for word in ("替代", "代替", "替换")):
        candidate_sources.append(cleaned_value)

    ignored_candidate_words = (
        "工具",
        "市场",
        "超市",
        "朋友",
        "锻炼",
        "去除",
        "用于",
        "喷涂",
        "刷涂",
        "使用方法",
        "情况",
        "常温冷冻",
        "做成",
        "切碎",
        "切片",
        "切段",
        "长度",
        "装得下",
        "不管",
        "份数",
        "张数",
        "向下取整",
    )
    ignored_candidate_names = {
        "含糖",
        "无糖",
        "鲜榨",
        "浓缩",
        "冷萃",
        "nfc",
        "即食",
        "自制",
        "市售",
        "原切",
    }
    identifiers: list[str] = []
    for raw_source in candidate_sources:
        source_text = clean_markdown_text(raw_source).strip(" \t。；;，,")
        if not source_text or source_text.startswith(("推荐用", "推荐使用")):
            continue

        expression = ""
        replacement = re.search(r"(?:替代|代替|替换)", source_text)
        if replacement:
            after_replacement = source_text[replacement.end() :].strip(" \t—-：:")
            if after_replacement.startswith(("成", "为")):
                expression = after_replacement[1:].strip()
                expression = re.split(r"[，,。；;]", expression, maxsplit=1)[0]
            else:
                before_replacement = source_text[: replacement.start()].strip(" \t—-：:")
                if not before_replacement:
                    continue
                cue_positions = [
                    before_replacement.rfind(cue)
                    for cue in ("可用", "可以用", "也可用", "也可以用", "使用", "用")
                ]
                cue_position = max(cue_positions)
                if cue_position >= 0:
                    matched_cue = next(
                        cue
                        for cue in sorted(
                            ("可用", "可以用", "也可用", "也可以用", "使用", "用"),
                            key=len,
                            reverse=True,
                        )
                        if before_replacement.startswith(cue, cue_position)
                    )
                    expression = before_replacement[cue_position + len(matched_cue) :]
                else:
                    expression = re.split(r"[，,；;]", before_replacement)[-1]
                    expression = expression.split("—")[-1].strip()
        elif re.match(r"^(?:或者|或是|或)", source_text):
            expression = re.sub(r"^(?:或者|或是|或)\s*", "", source_text)
            expression = re.split(r"[，,；;]", expression, maxsplit=1)[0]
        elif re.match(r"^(?:也)?可以是", source_text):
            expression = re.sub(r"^(?:也)?可以是\s*", "", source_text)
        elif re.search(r"(?:均可|皆可|都可以|都可)\s*$", source_text):
            expression = re.sub(r"(?:均可|皆可|都可以|都可)\s*$", "", source_text)
        elif re.search(r"(?:也可以|也可)\s*(?:[，,。；;]|$)", source_text):
            expression = re.split(r"(?:也可以|也可)\s*(?:[，,。；;]|$)", source_text, maxsplit=1)[0]
            expression = re.split(r"[，,；;]", expression)[-1]
        elif allow_bare_choices and re.fullmatch(
            r"[^，,。；;]{1,18}(?:或者|或是|或)[^，,。；;]{1,18}",
            source_text,
        ):
            expression = source_text
        else:
            continue

        expression = expression.strip(" \t。；;，,:：—-")
        expression = re.sub(
            r"^(?:没有(?:就)?|也可以用|也可用|可以用|可用|可选|也可以|也可|可以|可|选择|选用|用|推荐|普通|其他|同一个|稍微肥一点的|不辣的|后面介绍以)\s*",
            "",
            expression,
        )
        expression = re.split(r"[，,；;]|但", expression, maxsplit=1)[0].strip()
        expression = re.sub(
            r"(?:均可|皆可|都可以|都可|也可以|也可|可选|调味用|泡发|可|也)\s*$",
            "",
            expression,
        ).strip()
        if (
            not expression
            or any(symbol in expression for symbol in ("+", "＋"))
            or any(word in expression for word in ("以及", "并且", "混合后", "搭配"))
            or any(word in expression for word in ("可不放", "不吃辣", "不用"))
            or re.search(r"\s(?:和|与)\s|[、，,](?:和|与)", expression)
            or any(word in expression for word in ignored_candidate_words)
        ):
            continue

        raw_candidates: list[str] = []
        for enumerated in split_top_level_enumeration(expression):
            raw_candidates.extend(
                re.split(r"\s*(?:或者|或是|或|/|／|\bor\b)\s*", enumerated, flags=re.IGNORECASE)
            )
        for raw_candidate in raw_candidates:
            candidate = raw_candidate.strip(" \t。；;，,:：—-")
            candidate = re.sub(
                r"^(?:也可以用|也可用|可以用|可用|可选|也可以|也可|可以|可|选用|选择|用|推荐|普通|其他|同一个|不辣的|后面介绍以)\s*",
                "",
                candidate,
            )
            candidate = re.sub(
                r"(?:均可|皆可|都可以|都可|也可以|也可|可选|泡发|可|也)\s*$",
                "",
                candidate,
            )
            identifier = normalize_ingredient_id(candidate)
            expanded_compact_ids = {
                "大葱小葱": ("大葱", "小葱"),
            }.get(identifier, (identifier,))
            for expanded_identifier in expanded_compact_ids:
                if (
                    not expanded_identifier
                    or expanded_identifier in ignored_candidate_names
                    or len(expanded_identifier) > 24
                    or expanded_identifier in identifiers
                ):
                    continue
                identifiers.append(expanded_identifier)
    return identifiers


def requirement_is_optional(value: str, forced_optional: bool = False) -> bool:
    """判断需求文字是否明确表示整项可以省略。

    @param value 必备项、用量行或分组说明文字。
    @param forced_optional 外层“可选原料”等分组已经确认可选时传true。
    @return 整项可省略时返回true；“可选用某品种”等仅表示选择类型时返回false。
    """

    if forced_optional:
        return True
    text = clean_markdown_text(value)
    standalone_optional = re.search(
        r"(?:^|[\s（(\[【:：,，])可选(?:项|材料|原料)?(?=$|[\s,，。；;）)\]】:：])",
        text,
    )
    return bool(
        standalone_optional
        or re.search(r"非必需|非必须|按需|可省略|自行添加", text)
        or re.search(r"没有(?:也)?可以?不放|没有可不放|不放也可以|可以考虑不放", text)
        or re.search(r"不喜欢.*可以不放|不吃辣.*可以不用", text)
        or re.search(r"(?:可以|可)不加|无需添加", text)
        or "个人喜好" in text
        or "可根据口味选择增加" in text
    )


def optional_group_statement(value: str) -> bool:
    """识别会影响后续列表项的独立可选分组说明。

    @param value “额外用作点缀的食材，可选”等非标题说明行。
    @return 后续同组列表均为可选时返回true，否则返回false。
    """

    text = clean_markdown_text(value).strip()
    return bool(
        re.search(r"^(?:可选|可额外|下列.*选配|额外.*可选)", text)
        or ("下面" in text and "自行选择" in text)
    )


def explicit_group_kind(value: str) -> str:
    """从短标题或独立分组标签推导需求类型。

    @param value 三级标题或“原料：”“工具”等独立文字。
    @return `ingredient`、`seasoning`、`tool`之一；无法确认时返回空字符串。
    """

    text = clean_markdown_text(value).strip(" \t。；;，,:：")
    for label in ("工具", "厨具", "设备"):
        if label in text:
            return "tool"
    for label in ("调味料", "调料", "辅料", "香料"):
        if label in text:
            return "seasoning"
    for label in ("原料", "材料", "主料", "配料"):
        if label in text:
            return "ingredient"
    return ""


def split_top_level_enumeration(value: str) -> list[str]:
    """按顶层顿号、逗号和分号拆分并列项目，括号内说明保持原样。

    使用方法：
    处理“鸡肉（鸡腿肉最好），土豆，青椒”时调用。函数不会拆分括号内的品牌、口味或解释，
    也不会按“或”拆分，因为“或”需要由同一需求的候选ID表达。

    @param value 可能含多个并列食材的原始需求文字。
    @return 去除首尾空白的顶层项目列表；没有分隔符时返回只含原文的列表。
    """

    parts: list[str] = []
    current: list[str] = []
    depth = 0
    for character in value:
        if character in "（([【":
            depth += 1
        elif character in "）)]】" and depth > 0:
            depth -= 1
        if depth == 0 and character in "、，,；;+":
            part = "".join(current).strip()
            if part:
                parts.append(part)
            current.clear()
            continue
        current.append(character)
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


def requirement_group_prefix(value: str) -> tuple[str, str]:
    """识别需求项开头的“原料：”“调味料：”“工具：”等明确分组。

    @param value 一条完整需求文字。
    @return `(去掉分组前缀后的文字, 强制类型)`；没有已知前缀时原样返回且类型为空。
    """

    match = re.match(
        r"^(原料|材料|主料|配料|辅料|调料|调味料|香料|工具|厨具|设备|必备|可选)\s*[:：]\s*(.*)$",
        value,
    )
    if not match:
        return value, ""
    label = match.group(1)
    forced_kind = REQUIREMENT_GROUP_KINDS.get(label, "")
    return match.group(2).strip(), forced_kind


def concise_requirement_name(value: str) -> str:
    """从含品牌、份量或解释的需求原文中得到适合卡片展示的短名称。

    @param value 单个、未按并列符拆分的需求文字。
    @return 保留替代关系但去掉括号说明和明显解释的名称；无法识别时返回空字符串。
    """

    name = clean_markdown_text(value).strip(" \t。；;，,")
    if not name or name.startswith(REQUIREMENT_NOTE_PREFIXES):
        return ""
    name = re.split(r"(?:——|—|，?参见|，?详见)", name, maxsplit=1)[0].strip()
    name = remove_parenthetical_notes(name)
    if "：" in name or ":" in name:
        separator = "：" if "：" in name else ":"
        left, right = name.split(separator, 1)
        if left.strip() and len(normalize_ingredient_id(left)) <= 16 and right.strip():
            name = left.strip()
    name = re.split(r"(?:可依据|可根据|根据个人|按个人)", name, maxsplit=1)[0].strip()
    name = re.sub(r"^(?:可以是|也可以是|或者是|或是|例如|比如|如|推荐|切块的|喜欢的)", "", name)
    name = re.sub(r"(?:二选一|任选其一|任选一种|任意一种)$", "", name)
    name = name.strip(" \t。；;，,:：")
    if name.startswith(("如果", "市场上", "市场直接", "选择自己", "其他任何", "火候")):
        return ""
    if name.startswith("其余配菜"):
        return ""
    return name


def compact_example_ids(value: str) -> list[str]:
    """展开少数没有分隔符的连续示例名称。

    HowToCook 的“汤面”把“鸡蛋鸭蛋鹅蛋鸵鸟蛋”等写成连续字符串。这里仅对已明确枚举的固定
    表达做保守展开，避免使用任意分词推测未知食材。

    @param value 一个示例列表项。
    @return 去重后的规范食材ID；普通文字按常规候选拆分处理。
    """

    cleaned = remove_parenthetical_notes(clean_markdown_text(value)).strip("。")
    known_examples = {
        "牛羊鱼虾等肉类": ("牛肉", "羊肉", "鱼", "虾"),
        "鸡蛋鸭蛋鹅蛋鸵鸟蛋等蛋类": ("鸡蛋", "鸭蛋", "鹅蛋", "鸵鸟蛋"),
        "豆块豆筋豆腐皮等豆制品类": ("豆块", "豆筋", "豆腐皮"),
        "生菜菠菜油麦菜": ("生菜", "菠菜", "油麦菜"),
        "青椒番茄胡萝卜等蔬菜类": ("青椒", "番茄", "胡萝卜"),
    }
    candidates = known_examples.get(cleaned, ())
    identifiers: list[str] = []
    for candidate in candidates:
        identifier = normalize_ingredient_id(candidate)
        if identifier and identifier not in identifiers:
            identifiers.append(identifier)
    return identifiers


def requirement_amount(display_name: str, calculation_lines: list[str]) -> str:
    """从“计算”章节中寻找某项原料对应的原始用量行。

    @param display_name “必备原料和工具”章节中的显示名称。
    @param calculation_lines “计算”章节的原始行。
    @return 去掉列表符号后的完整用量文字；无法可靠关联时返回空字符串。
    """

    accepted_ids = split_requirement_alternatives(display_name)
    if not accepted_ids:
        return ""
    for line in calculation_lines:
        candidate = clean_markdown_text(line)
        candidate = re.sub(r"^\s*(?:[-+*]|\d+[.)、])\s*", "", candidate)
        normalized_candidate = normalize_ingredient_id(candidate)
        if any(
            ingredient_id and (
                ingredient_id in normalized_candidate or
                ingredient_id in normalize_ingredient_id(candidate.split(" ", 1)[0])
            )
            for ingredient_id in accepted_ids
        ):
            return candidate
    return ""


def split_requirement_alternatives(name: str) -> list[str]:
    """拆分一条明确写成“甲或乙”“甲/乙”的可替代材料。

    @param name 清理后的必备项显示名称。
    @return 去重后的候选食材标识；没有明确替代关系时只含主标识。
    """

    without_note = remove_parenthetical_notes(name)
    without_note = re.sub(r"(?:二选一|任选其一|任选一种|任意一种)$", "", without_note)
    parts = re.split(r"\s*(?:或者|或是|或|/|／|\bor\b)\s*", without_note, flags=re.IGNORECASE)
    identifiers: list[str] = []
    for part in parts:
        identifier = normalize_ingredient_id(part)
        if (
            identifier
            and identifier not in REQUIREMENT_QUALIFIER_ONLY_NAMES
            and identifier not in identifiers
        ):
            identifiers.append(identifier)
    for identifier in explicit_alternative_ids(name):
        if (
            identifier not in REQUIREMENT_QUALIFIER_ONLY_NAMES
            and identifier not in identifiers
        ):
            identifiers.append(identifier)
    return identifiers


def build_atomic_requirements(
    raw_name: str,
    calculation_lines: list[str],
    subsection: str,
    forced_kind: str = "",
    forced_optional: bool = False,
) -> list[dict[str, Any]]:
    """把一个列表项转换成一个或多个独立需求。

    使用方法：
    普通项目直接调用；“辅料：油、盐、生抽”会拆成三个需求，而“牛奶或淡奶油”仍保留为一个
    可替代需求。函数只输出可解释的短名称，原始完整文字仍由菜谱章节内容块保留。

    @param raw_name 列表项中去掉项目符号后的原始文字。
    @param calculation_lines 当前菜谱“计算”章节的行，用于关联份量原文。
    @param subsection 当前小节标题。
    @param forced_kind 外层分组明确给出的需求类型。
    @param forced_optional 外层“可选原料”等分组是否明确表示整组可省略。
    @return 结构化需求列表；说明性项目返回空列表。
    """

    original = clean_markdown_text(raw_name).strip()
    if not original or original.startswith(REQUIREMENT_NOTE_PREFIXES):
        return []
    parent_optional = forced_optional or bool(
        re.match(
            r"^(?:\[\s*可选\s*\]|【\s*可选\s*】|[（(]\s*可选\s*[）)]|可选\s*[:：]|可根据口味选择增加)",
            original,
        )
    )
    original = re.sub(
        r"^(?:\[\s*可选\s*\]|【\s*可选\s*】|[（(]\s*可选\s*[）)])\s*[:：]?\s*",
        "",
        original,
    )
    if original.startswith("其余配菜例如"):
        original = original.removeprefix("其余配菜例如").strip()
        parent_optional = True
    stripped, prefix_kind = requirement_group_prefix(original)
    stripped = re.sub(r"^[（(]\s*可选\s*[）)]\s*[:：]\s*", "", stripped)
    effective_kind = prefix_kind or forced_kind
    if not stripped:
        return []

    if stripped.startswith("面食材料") and ("可以是" in stripped or "：" in stripped):
        body = stripped.split("：", 1)[-1]
        body = body.replace("也可以是", "").replace("可以是", "")
        body = body.replace("或者是其他任何自己所喜欢的面食形式。", "")
        identifiers = [
            normalize_ingredient_id(item.replace("各类规格", ""))
            for item in split_top_level_enumeration(body)
        ]
        identifiers = list(dict.fromkeys(identifier for identifier in identifiers if identifier))
        if identifiers:
            return [
                {
                    "displayName": "面条或粉丝等面食",
                    "acceptedIngredientIds": identifiers,
                    "amount": requirement_amount("面食材料", calculation_lines),
                    "optional": parent_optional,
                    "kind": "ingredient",
                }
            ]

    item_source = stripped
    colon_source = remove_parenthetical_notes(item_source)
    if "：" in colon_source or ":" in colon_source:
        separator = "：" if "：" in colon_source else ":"
        left, right = colon_source.split(separator, 1)
        if left.strip() and right.strip() and len(normalize_ingredient_id(left)) <= 16:
            item_source = left.strip()

    enumerated_items: list[str] = []
    for item in split_top_level_enumeration(item_source):
        conjunction = re.fullmatch(r"([^和]{1,8})和([^和]{1,8})", remove_parenthetical_notes(item))
        if conjunction:
            enumerated_items.extend((conjunction.group(1), conjunction.group(2)))
        else:
            enumerated_items.append(item)

    requirements: list[dict[str, Any]] = []
    for item in enumerated_items:
        name = concise_requirement_name(item)
        if not name:
            continue
        identifiers = split_requirement_alternatives(name)
        for identifier in explicit_alternative_ids(item):
            if identifier not in identifiers:
                identifiers.append(identifier)
        identifiers = [identifier for identifier in identifiers if 0 < len(identifier) <= 24]
        if not identifiers:
            continue
        amount = requirement_amount(name, calculation_lines)
        for identifier in explicit_alternative_ids(amount, allow_bare_choices=False):
            if (
                identifier not in REQUIREMENT_QUALIFIER_ONLY_NAMES
                and identifier not in identifiers
            ):
                identifiers.append(identifier)
        if len(identifiers) == 1 and (
            re.match(r"^\d", name)
            or re.search(r"\d\s*(?:[-~～至]\s*\d+\s*)?(?:克|千克|斤|两|毫升|升|个|只|颗|粒|枚|片|瓣|根|段|条|勺|匙|杯|份|盒|袋|包|瓶|罐|块|把|张|双|碗|kg|ml|g|l)\b", name, flags=re.IGNORECASE)
        ):
            name = identifiers[0]
        optional = (
            parent_optional
            or requirement_is_optional(item)
            or requirement_is_optional(amount)
        )
        requirements.append(
            {
                "displayName": name,
                "acceptedIngredientIds": identifiers,
                "amount": amount,
                "optional": optional,
                "kind": classify_requirement(name, subsection, effective_kind),
            }
        )
    return requirements


def stable_deduplicate_requirements(
    requirements: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    """按完整结构稳定去除同一菜谱内重复的需求项。

    使用方法：
    在一篇菜谱的所有必备项提取完成后调用。只有显示名、候选食材ID、可选状态、类型和用量
    全部相同的项目才会合并；同名但用量或用途不同的分组件需求仍按上游顺序保留。

    @param requirements 按上游出现顺序提取出的结构化需求项。
    @return 首次出现顺序不变且没有完全重复项的新列表。
    """

    unique_requirements: list[dict[str, Any]] = []
    seen_signatures: set[tuple[Any, ...]] = set()
    for requirement in requirements:
        signature = (
            requirement["displayName"],
            tuple(requirement["acceptedIngredientIds"]),
            requirement["optional"],
            requirement["kind"],
            requirement["amount"],
        )
        if signature in seen_signatures:
            continue
        seen_signatures.add(signature)
        unique_requirements.append(requirement)
    return unique_requirements


def merged_alternative_requirement(
    display_name: str,
    child_names: list[str],
    calculation_lines: list[str],
    subsection: str,
    forced_kind: str = "",
    forced_optional: bool = False,
) -> dict[str, Any] | None:
    """把一组明确标注“任选”的子项目合并为单个替代需求。

    @param display_name 面向用户显示的组名。
    @param child_names 该组所有候选子项目原文。
    @param calculation_lines 当前菜谱“计算”章节行。
    @param subsection 当前小节标题。
    @param forced_kind 外层明确给出的需求类型。
    @param forced_optional 外层分组是否明确为可选。
    @return 合并后的需求；没有可用候选ID时返回None。
    """

    identifiers: list[str] = []
    for child_name in child_names:
        expanded_ids = compact_example_ids(child_name)
        for identifier in expanded_ids:
            if identifier and identifier not in identifiers:
                identifiers.append(identifier)
        if expanded_ids:
            continue
        child_requirements = build_atomic_requirements(
            child_name,
            calculation_lines,
            subsection,
            forced_kind,
            forced_optional,
        )
        for requirement in child_requirements:
            for identifier in requirement["acceptedIngredientIds"]:
                if identifier not in identifiers:
                    identifiers.append(identifier)
    if not identifiers:
        return None
    amount = requirement_amount(display_name, calculation_lines)
    for identifier in explicit_alternative_ids(amount, allow_bare_choices=False):
        if identifier not in identifiers:
            identifiers.append(identifier)
    return {
        "displayName": display_name,
        "acceptedIngredientIds": identifiers,
        "amount": amount,
        "optional": requirement_is_optional(display_name, forced_optional) or requirement_is_optional(amount),
        "kind": classify_requirement(display_name, subsection, forced_kind),
    }


def extract_requirements(lines: list[str]) -> list[dict[str, Any]]:
    """提取一篇菜谱的必备原料、调料和工具。

    使用方法：
    传入完整 Markdown 行列表。本函数仅读取“必备原料和工具”章节中的列表项，保留原显示名，
    并为食材推导受控候选标识；标有“可选”“按需”等文字的条目不会阻止“可以直接做”判断。

    @param lines 菜谱完整 Markdown 行列表。
    @return 按上游出现顺序排列的需求项字典列表。
    """

    mandatory_lines = section_lines(lines, "必备原料和工具")
    calculation_lines = section_lines(lines, "计算")
    requirements: list[dict[str, Any]] = []
    subsection = ""
    subsection_kind = ""
    subsection_optional = False
    alternative_subsection_names: list[str] = []
    alternative_subsection_display = ""

    def flush_alternative_subsection() -> None:
        """把“容器任选”“馅料任选”等加粗小节累计为一个替代需求。"""

        nonlocal alternative_subsection_names, alternative_subsection_display
        if alternative_subsection_names:
            requirement = merged_alternative_requirement(
                display_name=alternative_subsection_display,
                child_names=alternative_subsection_names,
                calculation_lines=calculation_lines,
                subsection=subsection,
                forced_kind=subsection_kind,
                forced_optional=subsection_optional,
            )
            if requirement is not None:
                if alternative_subsection_display.startswith("可酿制容器"):
                    requirement["kind"] = "ingredient"
                requirements.append(requirement)
        alternative_subsection_names = []
        alternative_subsection_display = ""

    index = 0
    while index < len(mandatory_lines):
        line = mandatory_lines[index]
        heading = HEADING_PATTERN.match(line)
        bold_subsection = re.match(r"^\s*\*\*(.+?)\*\*\s*$", line)
        if heading or bold_subsection:
            flush_alternative_subsection()
            subsection = clean_markdown_text(
                heading.group(2) if heading else bold_subsection.group(1)
            )
            subsection_kind = explicit_group_kind(subsection)
            subsection_optional = optional_group_statement(subsection)
            if "容器" in subsection:
                alternative_subsection_display = "可酿制容器（任选其一）"
            elif "馅料" in subsection:
                alternative_subsection_display = "馅料（任选其一）"
            index += 1
            continue

        bullet_match = re.match(r"^(\s*)[-+*]\s+(.+?)\s*$", line)
        if not bullet_match:
            plain_text = clean_markdown_text(line).strip()
            plain_group_match = re.fullmatch(
                r"(?:可选|必须|必备)?(?:原料|材料|主料|配料|辅料|调料|调味料|香料|工具|厨具|设备)\s*[:：]?",
                plain_text,
            )
            if plain_group_match:
                subsection = plain_text
                subsection_kind = explicit_group_kind(plain_text)
                subsection_optional = optional_group_statement(plain_text)
            elif optional_group_statement(plain_text):
                subsection_optional = True
            index += 1
            continue
        indent = len(bullet_match.group(1).replace("\t", "    "))
        raw_name = clean_markdown_text(bullet_match.group(2))
        child_lines: list[str] = []
        child_index = index + 1
        while child_index < len(mandatory_lines):
            child_match = re.match(r"^(\s*)[-+*]\s+(.+?)\s*$", mandatory_lines[child_index])
            if not child_match:
                if mandatory_lines[child_index].strip():
                    break
                child_index += 1
                continue
            child_indent = len(child_match.group(1).replace("\t", "    "))
            if child_indent <= indent:
                break
            child_lines.append(clean_markdown_text(child_match.group(2)))
            child_index += 1

        if alternative_subsection_display:
            if not raw_name.startswith(REQUIREMENT_NOTE_PREFIXES):
                alternative_subsection_names.append(raw_name)
            index = max(index + 1, child_index if child_lines else index + 1)
            continue

        if child_lines:
            normalized_parent = remove_parenthetical_notes(raw_name).rstrip("：:").strip()
            parent_label = normalized_parent.split("：", 1)[0].split(":", 1)[0].strip()
            if parent_label in REQUIREMENT_GROUP_KINDS:
                child_kind = REQUIREMENT_GROUP_KINDS[parent_label]
                for child_name in child_lines:
                    requirements.extend(
                        build_atomic_requirements(
                            child_name,
                            calculation_lines,
                            subsection=parent_label,
                            forced_kind=child_kind,
                            forced_optional=(subsection_optional or parent_label == "可选"),
                        )
                    )
            elif "任选" in raw_name:
                requirement = merged_alternative_requirement(
                    display_name=concise_requirement_name(raw_name) or "任选材料",
                    child_names=child_lines,
                    calculation_lines=calculation_lines,
                    subsection=subsection,
                    forced_kind=subsection_kind,
                    forced_optional=subsection_optional,
                )
                if requirement is not None:
                    requirements.append(requirement)
            elif raw_name.startswith("菜类材料"):
                requirement = merged_alternative_requirement(
                    display_name="菜类配料（任选其一）",
                    child_names=child_lines,
                    calculation_lines=calculation_lines,
                    subsection=subsection,
                    forced_kind=subsection_kind,
                    forced_optional=subsection_optional,
                )
                if requirement is not None:
                    requirements.append(requirement)
            elif raw_name.startswith("袋装螺蛳粉"):
                requirements.extend(
                    build_atomic_requirements(
                        raw_name.split("，", 1)[0],
                        calculation_lines,
                        subsection,
                        subsection_kind,
                        subsection_optional,
                    )
                )
            else:
                requirements.extend(
                    build_atomic_requirements(
                        raw_name,
                        calculation_lines,
                        subsection,
                        subsection_kind,
                        subsection_optional,
                    )
                )
                for child_name in child_lines:
                    requirements.extend(
                        build_atomic_requirements(
                            child_name,
                            calculation_lines,
                            subsection,
                            subsection_kind,
                            subsection_optional,
                        )
                    )
            index = child_index
            continue

        requirements.extend(
            build_atomic_requirements(
                raw_name,
                calculation_lines,
                subsection,
                subsection_kind,
                subsection_optional,
            )
        )
        index += 1

    flush_alternative_subsection()
    return stable_deduplicate_requirements(requirements)


def extract_summary(lines: list[str]) -> str:
    """提取菜谱标题后的首段简介。

    @param lines 菜谱完整 Markdown 行列表。
    @return 最多 180 个字符的简介；原文没有简介时返回空字符串。
    """

    paragraphs: list[str] = []
    collecting = False
    for line in lines:
        heading = HEADING_PATTERN.match(line)
        if heading:
            if len(heading.group(1)) == 1:
                collecting = True
                continue
            if collecting:
                break
        stripped_line = line.strip()
        only_images = bool(markdown_images(stripped_line)) and not strip_markdown_images(stripped_line).strip()
        if not collecting or not stripped_line or only_images:
            continue
        text = clean_markdown_text(line)
        if not text or text.startswith(("预估烹饪难度", "预估卡路里")):
            continue
        paragraphs.append(text)
        if sum(len(item) for item in paragraphs) >= 180:
            break
    return " ".join(paragraphs)[:180]


def extract_metadata(lines: list[str]) -> tuple[str, str]:
    """提取难度和卡路里展示文字。

    @param lines 菜谱完整 Markdown 行列表。
    @return `(difficulty, calories)` 二元组，原文没有对应字段时该项为空字符串。
    """

    difficulty = ""
    calories = ""
    for line in lines[:30]:
        text = clean_markdown_text(line)
        if text.startswith("预估烹饪难度"):
            difficulty = text.partition("：")[2].strip() or text.partition(":")[2].strip()
        elif text.startswith("预估卡路里"):
            calories = text.partition("：")[2].strip() or text.partition(":")[2].strip()
    return difficulty, calories


def resolve_local_image(markdown_file: Path, raw_target: str, source: Path) -> Path | None:
    """安全解析 Markdown 中的本地图片路径。

    @param markdown_file 当前菜谱文件。
    @param raw_target Markdown 图片圆括号中的目标地址。
    @param source HowToCook 仓库根目录。
    @return 位于仓库内的绝对图片路径；外链、锚点、越界或非图片目标返回 `None`。
    """

    target = raw_target.strip().strip("<>").split("#", 1)[0]
    if re.match(r"^[a-z][a-z0-9+.-]*://", target, flags=re.IGNORECASE):
        return None
    target = target.replace("%20", " ")
    candidate = (markdown_file.parent / target).resolve()
    source_root = source.resolve()
    try:
        candidate.relative_to(source_root)
    except ValueError:
        return None
    return candidate if candidate.suffix.lower() in IMAGE_SUFFIXES else None


def source_image_url(source_relative_path: str, commit: str) -> str:
    """构造固定到提交号的 GitHub Raw 图片地址。

    @param source_relative_path 图片相对 HowToCook 根目录的 POSIX 路径。
    @param commit 固定的上游提交号。
    @return 可用于来源追踪或本地图片缺失兜底的 HTTPS 地址。
    """

    encoded_path = "/".join(quote(part, safe="") for part in source_relative_path.split("/"))
    return f"https://raw.githubusercontent.com/Anduin2017/HowToCook/{commit}/{encoded_path}"


def optimize_image(source_image: Path, output_image: Path, max_edge: int, quality: int) -> None:
    """按手机阅读场景缩放并转换一张上游图片为 WebP。

    @param source_image 上游原图绝对路径。
    @param output_image 目标 WebP 绝对路径。
    @param max_edge 输出图片最长边上限。
    @param quality 有损 WebP 质量，范围 1 到 100。
    @return 无返回值；图片无法解码时直接抛出异常并中止快照生成。
    """

    output_image.parent.mkdir(parents=True, exist_ok=True)
    with Image.open(source_image) as opened:
        image = ImageOps.exif_transpose(opened)
        if getattr(image, "is_animated", False):
            image.seek(0)
        image = image.convert("RGBA" if "A" in image.getbands() else "RGB")
        image.thumbnail((max_edge, max_edge), Image.Resampling.LANCZOS)
        image.save(output_image, "WEBP", quality=quality, method=6)


def generated_image_is_valid(output_image: Path, max_edge: int) -> bool:
    """检查已存在的生成图片能否安全复用。

    @param output_image 预期的 WebP 输出路径。
    @param max_edge 本次生成允许的最长边。
    @return 文件可完整解码、格式为WebP且尺寸未超过当前上限时返回true，否则返回false。
    """

    if not output_image.is_file():
        return False
    try:
        with Image.open(output_image) as image:
            image.verify()
        with Image.open(output_image) as image:
            return image.format == "WEBP" and max(image.size) <= max_edge
    except (OSError, ValueError):
        return False


def image_descriptor(
    markdown_file: Path,
    raw_target: str,
    alt_text: str,
    source: Path,
    output: Path,
    commit: str,
    max_edge: int,
    quality: int,
    strict_images: bool,
    converted_images: dict[str, str],
) -> dict[str, str] | None:
    """把一个 Markdown 图片引用转换为目录图片描述。

    @param markdown_file 当前菜谱文件。
    @param raw_target Markdown 中的图片目标。
    @param alt_text 图片替代文字。
    @param source HowToCook 根目录。
    @param output cook assets 根目录。
    @param commit 固定上游提交号。
    @param max_edge 本地 WebP 最长边。
    @param quality 本地 WebP 质量。
    @param strict_images 本地引用缺失时是否立即失败。
    @param converted_images 已转换源路径到 asset 路径的缓存。
    @return 包含本地 asset、固定来源 URL 和替代文字的字典；不支持的目标返回 `None`。
    """

    target = raw_target.strip().strip("<>")
    if re.match(r"^https?://", target, flags=re.IGNORECASE):
        return {"assetPath": "", "sourceUrl": target, "alt": clean_markdown_text(alt_text)}

    local_image = resolve_local_image(markdown_file, target, source)
    if local_image is None:
        return None
    relative = local_image.relative_to(source.resolve()).as_posix()
    asset_path = converted_images.get(relative, "")
    if local_image.is_file() and not asset_path:
        digest = hashlib.sha256(relative.encode("utf-8")).hexdigest()[:20]
        asset_path = f"cook/images/{digest}.webp"
        output_image = output.parent / asset_path
        if not generated_image_is_valid(output_image, max_edge):
            optimize_image(local_image, output_image, max_edge, quality)
        converted_images[relative] = asset_path
    elif strict_images and not local_image.is_file():
        raise FileNotFoundError(f"Missing referenced image: {relative}")
    return {
        "assetPath": asset_path,
        "sourceUrl": source_image_url(relative, commit),
        "alt": clean_markdown_text(alt_text),
    }


def flush_paragraph(blocks: list[dict[str, Any]], paragraph_lines: list[str]) -> None:
    """把暂存的普通文字行合并为一个段落内容块。

    @param blocks 已解析内容块列表，会在有有效文字时原地追加。
    @param paragraph_lines 连续普通文字行，会在处理后原地清空。
    @return 无返回值。
    """

    text = "\n".join(line for line in paragraph_lines if line).strip()
    paragraph_lines.clear()
    if text:
        blocks.append({"type": "paragraph", "text": text})


def table_block(table_lines: list[str]) -> dict[str, Any]:
    """把暂存的 Markdown 表格行转换为结构化表头和数据行。

    @param table_lines 已去除对齐分隔行、且单元格用竖线分隔的表格文字。
    @return `type=table`的内容块；首行作为表头，其余行按表头列数补齐或截断。
    """

    parsed_rows = [
        [cell.strip() for cell in line.split("|")]
        for line in table_lines
        if line.strip()
    ]
    if not parsed_rows:
        return {"type": "table", "headers": [], "rows": []}
    column_count = len(parsed_rows[0])
    headers = parsed_rows[0]
    rows = [
        (row + [""] * column_count)[:column_count]
        for row in parsed_rows[1:]
    ]
    return {"type": "table", "headers": headers, "rows": rows}


def group_recipe_sections(blocks: list[dict[str, Any]], recipe_id: str) -> list[dict[str, Any]]:
    """把扁平内容块按二级标题整理为页面章节，并合并连续列表项。

    @param blocks 按 Markdown 原始顺序生成的扁平内容块。
    @param recipe_id 当前菜谱稳定ID，用于生成章节内稳定标识。
    @return 以“简介”和各二级标题命名的章节列表。
    """

    sections: list[dict[str, Any]] = []
    current_title = "简介"
    current_blocks: list[dict[str, Any]] = []

    def append_section() -> None:
        """把当前章节合并列表后追加到结果，并清空暂存块。"""

        if not current_blocks:
            return
        normalized_blocks: list[dict[str, Any]] = []
        for block in current_blocks:
            if block["type"] in {"bullet", "numbered"}:
                grouped_type = "bulletList" if block["type"] == "bullet" else "numberedList"
                if normalized_blocks and normalized_blocks[-1]["type"] == grouped_type:
                    normalized_blocks[-1]["items"].append(block["text"])
                else:
                    normalized_blocks.append({"type": grouped_type, "items": [block["text"]]})
            else:
                normalized_blocks.append(block)
        section_index = len(sections)
        section_digest = hashlib.sha256(
            f"{recipe_id}:{section_index}:{current_title}".encode("utf-8")
        ).hexdigest()[:12]
        sections.append(
            {
                "id": f"section_{section_digest}",
                "title": current_title,
                "blocks": normalized_blocks,
            }
        )
        current_blocks.clear()

    for block in blocks:
        if block["type"] == "heading" and block.get("level") == 2:
            append_section()
            current_title = block["text"]
        else:
            current_blocks.append(block)
    append_section()
    return sections


def parse_content_blocks(
    markdown_file: Path,
    lines: list[str],
    source: Path,
    output: Path,
    commit: str,
    max_edge: int,
    quality: int,
    strict_images: bool,
    converted_images: dict[str, str],
) -> tuple[list[dict[str, Any]], list[dict[str, str]]]:
    """按原文顺序解析标题、段落、列表、表格和图片。

    @param markdown_file 当前菜谱文件。
    @param lines 菜谱完整 Markdown 行列表。
    @param source HowToCook 根目录。
    @param output cook assets 根目录。
    @param commit 固定上游提交号。
    @param max_edge 本地 WebP 最长边。
    @param quality 本地 WebP 质量。
    @param strict_images 本地引用缺失时是否失败。
    @param converted_images 跨菜谱复用的图片转换缓存。
    @return `(内容块, 图片描述)`；图片描述按原文首次出现顺序去重。
    """

    blocks: list[dict[str, Any]] = []
    images: list[dict[str, str]] = []
    seen_images: set[tuple[str, str]] = set()
    paragraph_lines: list[str] = []
    table_lines: list[str] = []
    code_lines: list[str] = []
    in_code = False

    def append_images(line: str) -> str:
        """抽取当前行图片并返回去掉图片语法后的剩余文字。"""

        for _, _, alt_text, raw_target in markdown_images(line):
            descriptor = image_descriptor(
                markdown_file=markdown_file,
                raw_target=raw_target,
                alt_text=alt_text,
                source=source,
                output=output,
                commit=commit,
                max_edge=max_edge,
                quality=quality,
                strict_images=strict_images,
                converted_images=converted_images,
            )
            if descriptor is None:
                continue
            key = (descriptor["assetPath"], descriptor["sourceUrl"])
            if key not in seen_images:
                images.append(descriptor)
                seen_images.add(key)
            blocks.append({"type": "image", **descriptor})
        return strip_markdown_images(line).strip()

    for line_index, raw_line in enumerate(lines):
        line = raw_line.rstrip()
        if line.startswith("```"):
            flush_paragraph(blocks, paragraph_lines)
            if table_lines:
                blocks.append(table_block(table_lines))
                table_lines.clear()
            if in_code:
                if code_lines:
                    blocks.append({"type": "code", "text": "\n".join(code_lines)})
                code_lines.clear()
            in_code = not in_code
            continue
        if in_code:
            code_lines.append(line)
            continue

        heading = HEADING_PATTERN.match(line)
        if heading:
            flush_paragraph(blocks, paragraph_lines)
            if table_lines:
                blocks.append(table_block(table_lines))
                table_lines.clear()
            level = len(heading.group(1))
            if level > 1:
                blocks.append(
                    {"type": "heading", "text": clean_markdown_text(heading.group(2)), "level": level}
                )
            continue

        if line.strip().startswith("|"):
            flush_paragraph(blocks, paragraph_lines)
            if not TABLE_SEPARATOR_PATTERN.match(line):
                cells = [clean_markdown_text(cell) for cell in line.strip().strip("|").split("|")]
                table_lines.append("|".join(cells))
            continue
        if table_lines:
            blocks.append(table_block(table_lines))
            table_lines.clear()

        image_matches = markdown_images(line)
        if image_matches:
            flush_paragraph(blocks, paragraph_lines)
            without_images = append_images(line)
            if without_images:
                numbered = NUMBERED_PATTERN.match(without_images)
                bullet = BULLET_PATTERN.match(without_images)
                if numbered:
                    blocks.insert(len(blocks) - len(image_matches), {"type": "numbered", "text": clean_markdown_text(numbered.group(1))})
                elif bullet:
                    blocks.insert(len(blocks) - len(image_matches), {"type": "bullet", "text": clean_markdown_text(bullet.group(1))})
                else:
                    blocks.insert(len(blocks) - len(image_matches), {"type": "paragraph", "text": clean_markdown_text(without_images)})
            continue

        numbered = NUMBERED_PATTERN.match(line)
        if numbered:
            flush_paragraph(blocks, paragraph_lines)
            blocks.append({"type": "numbered", "text": clean_markdown_text(numbered.group(1))})
            continue
        bullet = BULLET_PATTERN.match(line)
        if bullet:
            flush_paragraph(blocks, paragraph_lines)
            blocks.append({"type": "bullet", "text": clean_markdown_text(bullet.group(1))})
            continue
        if line.lstrip().startswith(">"):
            flush_paragraph(blocks, paragraph_lines)
            blocks.append({"type": "quote", "text": clean_markdown_text(line.lstrip()[1:])})
            continue
        if re.fullmatch(r"\s*(?:---+|\*\*\*+)\s*", line):
            flush_paragraph(blocks, paragraph_lines)
            blocks.append({"type": "divider", "text": ""})
            continue
        if not line.strip():
            flush_paragraph(blocks, paragraph_lines)
            continue
        if line_index > 0:
            paragraph_lines.append(clean_markdown_text(line))

    flush_paragraph(blocks, paragraph_lines)
    if table_lines:
        blocks.append(table_block(table_lines))
    if code_lines:
        blocks.append({"type": "code", "text": "\n".join(code_lines)})
    return blocks, images


def recipe_source_url(relative_path: str, commit: str) -> str:
    """构造固定提交下的 GitHub 菜谱原文地址。

    @param relative_path 菜谱相对仓库根目录的 POSIX 路径。
    @param commit 固定上游提交号。
    @return 可在浏览器中打开的 GitHub 文件地址。
    """

    encoded = "/".join(quote(part, safe="") for part in relative_path.split("/"))
    return f"https://github.com/Anduin2017/HowToCook/blob/{commit}/{encoded}"


def build_recipe(
    markdown_file: Path,
    source: Path,
    output: Path,
    commit: str,
    max_edge: int,
    quality: int,
    strict_images: bool,
    converted_images: dict[str, str],
) -> dict[str, Any]:
    """把单个正式菜谱转换为目录对象。

    @param markdown_file 菜谱 Markdown 文件。
    @param source HowToCook 根目录。
    @param output cook assets 根目录。
    @param commit 固定上游提交号。
    @param max_edge 本地 WebP 最长边。
    @param quality 本地 WebP 质量。
    @param strict_images 本地引用缺失时是否失败。
    @param converted_images 跨菜谱共享的图片转换缓存。
    @return 可以直接序列化为 JSON 的完整菜谱字典。
    """

    relative = markdown_file.relative_to(source).as_posix()
    category_id = markdown_file.relative_to(source / "dishes").parts[0]
    if category_id not in CATEGORY_LOOKUP:
        raise ValueError(f"Unknown dish category: {category_id} ({relative})")
    lines = markdown_file.read_text(encoding="utf-8-sig").splitlines()
    first_heading = next(
        (match.group(2) for line in lines if (match := HEADING_PATTERN.match(line)) and len(match.group(1)) == 1),
        markdown_file.stem,
    )
    title = normalize_title(first_heading, markdown_file.stem)
    difficulty, calories = extract_metadata(lines)
    requirements = extract_requirements(lines)
    blocks, images = parse_content_blocks(
        markdown_file=markdown_file,
        lines=lines,
        source=source,
        output=output,
        commit=commit,
        max_edge=max_edge,
        quality=quality,
        strict_images=strict_images,
        converted_images=converted_images,
    )
    recipe_id = "recipe_" + hashlib.sha256(relative.encode("utf-8")).hexdigest()[:20]
    sections = group_recipe_sections(blocks, recipe_id)
    cover_image_path = next(
        (image["assetPath"] for image in images if image.get("assetPath")),
        "",
    )
    return {
        "id": recipe_id,
        "name": title,
        "categoryId": category_id,
        "categoryName": CATEGORY_LOOKUP[category_id]["displayName"],
        "summary": extract_summary(lines),
        "tags": [],
        "difficulty": difficulty,
        "calories": calories,
        "coverImageAssetPath": cover_image_path,
        "requirements": requirements,
        "sections": sections,
        "sourcePath": relative,
        "sourceUrl": recipe_source_url(relative, commit),
    }


def iter_recipe_files(source: Path) -> Iterable[Path]:
    """按分类顺序遍历全部正式菜谱 Markdown 文件。

    @param source HowToCook 仓库根目录。
    @return 惰性文件序列；明确排除 `dishes/template` 示例模板。
    """

    dishes_root = source / "dishes"
    for category_id, _, _ in CATEGORY_DEFINITIONS:
        category_root = dishes_root / category_id
        if not category_root.is_dir():
            raise FileNotFoundError(f"Missing category directory: {category_root}")
        yield from sorted(category_root.rglob("*.md"), key=lambda path: path.as_posix())


def build_ingredient_catalog(recipes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """从全部菜谱需求建立可搜索、可多选的去重食材目录。

    @param recipes 已完成结构化解析的全部正式菜谱。
    @return 按类型和中文名称排序的食材目录；同一ID在不同菜谱出现时合并别名，并采用更严格类型。
    """

    kind_priority = {"tool": 0, "seasoning": 1, "ingredient": 2}
    ingredients: dict[str, dict[str, Any]] = {}
    for recipe in recipes:
        for requirement in recipe["requirements"]:
            for ingredient_id in requirement["acceptedIngredientIds"]:
                existing = ingredients.get(ingredient_id)
                display_name = ingredient_id
                if existing is None:
                    ingredients[ingredient_id] = {
                        "id": ingredient_id,
                        "displayName": display_name,
                        "aliases": [requirement["displayName"]],
                        "kind": requirement["kind"],
                    }
                    continue
                if requirement["displayName"] not in existing["aliases"]:
                    existing["aliases"].append(requirement["displayName"])
                if kind_priority[requirement["kind"]] > kind_priority[existing["kind"]]:
                    existing["kind"] = requirement["kind"]
    return sorted(
        ingredients.values(),
        key=lambda ingredient: (
            -kind_priority[ingredient["kind"]],
            ingredient["displayName"],
            ingredient["id"],
        ),
    )


def validate_catalog(catalog: dict[str, Any]) -> None:
    """验证目录数量、分类、标识、内容和食材数据是否完整。

    @param catalog 即将写入 assets 的完整目录字典。
    @return 无返回值；发现遗漏、重复或空内容时抛出异常。
    """

    recipes = catalog["recipes"]
    if not recipes:
        raise ValueError("Cook catalog has no recipes")
    recipe_ids = [recipe["id"] for recipe in recipes]
    source_paths = [recipe["sourcePath"] for recipe in recipes]
    if len(recipe_ids) != len(set(recipe_ids)):
        raise ValueError("Duplicate recipe ids detected")
    if len(source_paths) != len(set(source_paths)):
        raise ValueError("Duplicate recipe source paths detected")
    actual_categories = {recipe["categoryId"] for recipe in recipes}
    expected_categories = set(CATEGORY_LOOKUP)
    if actual_categories != expected_categories:
        raise ValueError(
            f"Category mismatch: expected={sorted(expected_categories)}, actual={sorted(actual_categories)}"
        )
    for recipe in recipes:
        if not recipe["name"] or not recipe["sections"]:
            raise ValueError(f"Recipe has empty title/content: {recipe['sourcePath']}")
        if not recipe["requirements"]:
            raise ValueError(f"Recipe has no requirements: {recipe['sourcePath']}")


def main() -> None:
    """生成确定性的菜谱 JSON、压缩图片和许可证副本。

    使用方法：
    在 HarleyApp 仓库根目录执行本脚本。成功后可直接运行 Android 单元测试与构建；失败时不会
    写出 catalog.json，避免把不完整目录误打包进 APK。

    @return 无返回值；成功时打印英文统计信息，失败时由异常给出具体文件。
    """

    args = parse_args()
    source = args.source.resolve()
    output = args.output.resolve()
    if args.max_image_edge < 320 or args.max_image_edge > 4096:
        raise ValueError("--max-image-edge must be between 320 and 4096")
    if args.webp_quality < 1 or args.webp_quality > 100:
        raise ValueError("--webp-quality must be between 1 and 100")
    if not (source / "dishes").is_dir() or not (source / "LICENSE").is_file():
        raise FileNotFoundError(f"Not a HowToCook checkout: {source}")

    commit = resolve_commit(source, args.commit)
    output.mkdir(parents=True, exist_ok=True)
    converted_images: dict[str, str] = {}
    recipes = [
        build_recipe(
            markdown_file=markdown_file,
            source=source,
            output=output,
            commit=commit,
            max_edge=args.max_image_edge,
            quality=args.webp_quality,
            strict_images=args.strict_images,
            converted_images=converted_images,
        )
        for markdown_file in iter_recipe_files(source)
    ]
    catalog = {
        "schemaVersion": 1,
        "sourceRepositoryUrl": "https://github.com/Anduin2017/HowToCook",
        "sourceCommit": commit,
        "sourceLicense": "The Unlicense",
        "categories": [
            {
                key: value
                for key, value in CATEGORY_LOOKUP[item[0]].items()
                if key != "symbol"
            }
            for item in CATEGORY_DEFINITIONS
        ],
        "ingredients": build_ingredient_catalog(recipes),
        "recipes": recipes,
    }
    validate_catalog(catalog)

    catalog_path = output / "catalog.json"
    temporary_catalog_path = output / "catalog.json.tmp"
    temporary_catalog_path.write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    temporary_catalog_path.replace(catalog_path)
    (output / "HOWTOCOOK_LICENSE.txt").write_text(
        (source / "LICENSE").read_text(encoding="utf-8-sig"),
        encoding="utf-8",
    )
    print(
        "Cook catalog generated: "
        f"recipes={len(recipes)}, categories={len(catalog['categories'])}, "
        f"ingredients={len(catalog['ingredients'])}, images={len(converted_images)}, commit={commit}"
    )


if __name__ == "__main__":
    main()
