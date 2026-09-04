"""生成HarleyApp使用的小学英语年级、册次离线推荐资源。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


DEFAULT_OUTPUT = Path("app/src/main/assets/primary_english_words.json")
GRADE_NAMES = {3: "三", 4: "四", 5: "五", 6: "六"}
EXPECTED_PEP_COUNTS = {
    (3, 1): 64,
    (3, 2): 71,
    (4, 1): 84,
    (4, 2): 104,
    (5, 1): 132,
    (5, 2): 155,
    (6, 1): 146,
    (6, 2): 92,
}
FALLBACK_MEANINGS = {
    "how about": "……怎么样",
    "we will": "我们将会",
    "are not": "不是；没有",
    "have ... class": "上……课",
    "do morning exercises": "做早操",
    "having ... class": "正在上……课",
    "postcard": "明信片",
    "hear": "听见",
}


# 一、二年级不是“三年级起点”教材正式词表，因此只提供原创启蒙分册并在App中明确标注。
PREP_BOOKS: dict[tuple[int, int], tuple[str, list[tuple[str, str]]]] = {
    (1, 1): (
        "一年级上 · 启蒙",
        [
            ("hello", "你好"), ("hi", "嗨"), ("goodbye", "再见"),
            ("please", "请"), ("thanks", "谢谢"), ("sorry", "对不起"),
            ("yes", "是；好的"), ("no", "不；不是"), ("I", "我"),
            ("you", "你；你们"), ("he", "他"), ("she", "她"),
            ("my", "我的"), ("your", "你的；你们的"), ("name", "名字"),
            ("boy", "男孩"), ("girl", "女孩"), ("teacher", "老师"),
            ("friend", "朋友"), ("school", "学校"), ("book", "书"),
            ("pen", "钢笔"), ("pencil", "铅笔"), ("bag", "书包；袋子"),
            ("red", "红色"), ("yellow", "黄色"), ("blue", "蓝色"),
            ("one", "一"), ("two", "二"), ("three", "三"),
            ("four", "四"), ("five", "五"), ("cat", "猫"),
            ("dog", "狗"), ("bird", "鸟"), ("fish", "鱼"),
            ("mother", "妈妈；母亲"), ("father", "爸爸；父亲"),
            ("good", "好的"), ("happy", "高兴的"), ("morning", "早晨"),
        ],
    ),
    (1, 2): (
        "一年级下 · 启蒙",
        [
            ("six", "六"), ("seven", "七"), ("eight", "八"),
            ("nine", "九"), ("ten", "十"), ("white", "白色"),
            ("black", "黑色"), ("green", "绿色"), ("orange", "橙色；橙子"),
            ("head", "头"), ("face", "脸"), ("eye", "眼睛"),
            ("ear", "耳朵"), ("nose", "鼻子"), ("mouth", "嘴巴"),
            ("hand", "手"), ("arm", "手臂"), ("leg", "腿"),
            ("foot", "脚"), ("apple", "苹果"), ("banana", "香蕉"),
            ("pear", "梨"), ("milk", "牛奶"), ("water", "水"),
            ("rice", "米饭"), ("bread", "面包"), ("egg", "鸡蛋"),
            ("big", "大的"), ("small", "小的"), ("long", "长的"),
            ("short", "短的；矮的"), ("run", "跑"), ("jump", "跳"),
            ("sing", "唱歌"), ("dance", "跳舞"), ("read", "阅读"),
            ("draw", "画画"), ("eat", "吃"), ("drink", "喝"),
            ("look", "看"), ("listen", "听"),
        ],
    ),
    (2, 1): (
        "二年级上 · 启蒙",
        [
            ("classroom", "教室"), ("desk", "书桌"), ("chair", "椅子"),
            ("door", "门"), ("window", "窗户"), ("ruler", "尺子"),
            ("eraser", "橡皮"), ("crayon", "蜡笔"), ("brother", "兄弟"),
            ("sister", "姐妹"), ("grandfather", "祖父；外祖父"),
            ("grandmother", "祖母；外祖母"), ("family", "家庭；家人"),
            ("home", "家"), ("room", "房间"), ("table", "桌子"),
            ("bed", "床"), ("shirt", "衬衫"), ("shoes", "鞋"),
            ("sunny", "晴朗的"), ("rainy", "下雨的"), ("cloudy", "多云的"),
            ("windy", "有风的"), ("hot", "热的"), ("cold", "冷的"),
            ("Monday", "星期一"), ("Tuesday", "星期二"),
            ("Wednesday", "星期三"), ("Thursday", "星期四"),
            ("Friday", "星期五"), ("Saturday", "星期六"),
            ("Sunday", "星期日"), ("today", "今天"), ("tomorrow", "明天"),
            ("open", "打开"), ("close", "关闭"), ("sit", "坐"),
            ("stand", "站立"), ("write", "写"), ("speak", "说；讲"),
        ],
    ),
    (2, 2): (
        "二年级下 · 启蒙",
        [
            ("zoo", "动物园"), ("panda", "熊猫"), ("tiger", "老虎"),
            ("elephant", "大象"), ("monkey", "猴子"), ("rabbit", "兔子"),
            ("duck", "鸭子"), ("chicken", "鸡"), ("cow", "奶牛"),
            ("horse", "马"), ("sheep", "绵羊"), ("tomato", "西红柿"),
            ("potato", "土豆"), ("carrot", "胡萝卜"), ("cake", "蛋糕"),
            ("juice", "果汁"), ("breakfast", "早餐"), ("lunch", "午餐"),
            ("dinner", "晚餐"), ("time", "时间"), ("clock", "钟"),
            ("park", "公园"), ("bus", "公共汽车"), ("bike", "自行车"),
            ("play", "玩；参加运动"), ("swim", "游泳"), ("walk", "步行"),
            ("fly", "飞"), ("cook", "做饭"), ("help", "帮助"),
            ("clean", "打扫；干净的"), ("beautiful", "美丽的"),
            ("kind", "友善的"), ("funny", "有趣的"), ("new", "新的"),
            ("old", "旧的；年老的"), ("fast", "快的"), ("slow", "慢的"),
            ("where", "在哪里"), ("what", "什么"), ("who", "谁"),
        ],
    ),
}


def parse_arguments() -> argparse.Namespace:
    """
    读取生成脚本所需目录和输出参数。

    使用方法：
    在项目根目录传入PEP分册参考仓库和简明释义仓库。脚本只读取源数据并生成紧凑的App资源，
    不会改动两个参考仓库。

    @return 包含两个输入目录和一个输出文件路径的参数对象。
    """

    parser = argparse.ArgumentParser(description="生成小学英语分级推荐资源")
    parser.add_argument("pep_source", type=Path, help="包含八册PEP JSON的textbooks目录")
    parser.add_argument("simple_source", type=Path, help="包含四个年级简明JSONL的目录")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="生成的assets JSON路径")
    return parser.parse_args()


def load_simple_meanings(simple_source: Path, grade: int) -> dict[str, str]:
    """
    读取某年级合并词表中的第一条简明中文义项。

    @param simple_source 四个年级JSONL所在目录。
    @param grade 三至六年级整数。
    @return 以小写英文拼写为键、适合列表展示的简明中文义项为值的字典。
    """

    source_path = simple_source / f"人教小学{GRADE_NAMES[grade]}年级.jsonl"
    meanings: dict[str, str] = {}
    for raw_line in source_path.read_text(encoding="utf-8").splitlines():
        item = json.loads(raw_line)
        translations = item.get("translations", [])
        if not translations:
            continue
        spelling = str(item.get("word", "")).strip().lower()
        meaning = str(translations[0].get("translation", "")).strip()
        if spelling and meaning:
            meanings.setdefault(spelling, meaning)
    return meanings


def load_pep_book(
    pep_source: Path,
    simple_source: Path,
    grade: int,
    term: int,
) -> dict[str, Any]:
    """
    合并一个PEP分册的词序、音标和简明中文义项。

    @param pep_source 八册PEP JSON所在目录。
    @param simple_source 年级简明释义JSONL所在目录。
    @param grade 三至六年级整数。
    @param term 1表示上册，2表示下册。
    @return 可直接写入最终资源的单个分册对象，同册重复拼写只保留首次出现位置。
    """

    source_path = pep_source / f"pep-primary-g{grade}-s{term}.json"
    source_book = json.loads(source_path.read_text(encoding="utf-8"))
    simple_meanings = load_simple_meanings(simple_source, grade)
    seen_words: set[str] = set()
    words: list[dict[str, str]] = []

    for unit in source_book.get("units", []):
        for item in unit.get("words", []):
            spelling = str(item.get("en", "")).strip()
            normalized = spelling.lower()
            if not spelling or normalized in seen_words:
                continue
            meaning = simple_meanings.get(normalized) or FALLBACK_MEANINGS.get(normalized, "")
            if not meaning:
                raise RuntimeError(f"{grade}年级第{term}册缺少简明释义：{spelling}")
            seen_words.add(normalized)
            words.append(
                {
                    "word": spelling,
                    "meaningZh": meaning,
                    "phonetic": str(item.get("phonetic", "")).strip(),
                }
            )

    expected_count = EXPECTED_PEP_COUNTS[(grade, term)]
    if len(words) != expected_count:
        raise RuntimeError(
            f"{grade}年级第{term}册应有{expected_count}个去重词条，实际为{len(words)}个"
        )
    return {
        "grade": grade,
        "term": term,
        "title": f"{grade}年级{'上' if term == 1 else '下'} · 人教PEP",
        "isOfficialPepSeries": True,
        "words": words,
    }


def create_prep_book(grade: int, term: int) -> dict[str, Any]:
    """
    创建一、二年级启蒙分册对象。

    @param grade 一或二年级整数。
    @param term 1表示上册，2表示下册。
    @return 明确标注为非正式PEP必修词表的原创启蒙分册。
    """

    title, entries = PREP_BOOKS[(grade, term)]
    return {
        "grade": grade,
        "term": term,
        "title": title,
        "isOfficialPepSeries": False,
        "words": [
            {"word": spelling, "meaningZh": meaning, "phonetic": ""}
            for spelling, meaning in entries
        ],
    }


def validate_books(books: list[dict[str, Any]]) -> None:
    """
    校验十二个分册的覆盖、字段和同册唯一性。

    @param books 即将写入APK的完整分册列表。
    @return 无返回值；发现漏册、空字段或同册重复时抛出异常停止生成。
    """

    expected_levels = {(grade, term) for grade in range(1, 7) for term in (1, 2)}
    actual_levels = {(int(book["grade"]), int(book["term"])) for book in books}
    if actual_levels != expected_levels or len(books) != len(expected_levels):
        raise RuntimeError("小学英语资源必须完整覆盖一至六年级上下册")

    for book in books:
        normalized_words = [str(item["word"]).strip().lower() for item in book["words"]]
        if not normalized_words or any(not word for word in normalized_words):
            raise RuntimeError(f"{book['title']}存在空白单词")
        if len(normalized_words) != len(set(normalized_words)):
            raise RuntimeError(f"{book['title']}存在重复单词")
        if any(not str(item["meaningZh"]).strip() for item in book["words"]):
            raise RuntimeError(f"{book['title']}存在空白中文释义")


def write_asset(output_path: Path, books: list[dict[str, Any]]) -> None:
    """
    写入Android离线资源及必要的来源和边界说明。

    @param output_path 最终assets JSON文件路径。
    @param books 已校验的一至六年级十二个分册。
    @return 无返回值。
    """

    output_path.parent.mkdir(parents=True, exist_ok=True)
    root = {
        "schemaVersion": 1,
        "source": {
            "publisherIntroduction": "https://www.pep.com.cn/xw/zt/hd/12/xjcjs/xx/202409/t20240920_1995564.html",
            "gradeTermReference": "https://github.com/cyforkk/pep-english-words",
            "notice": "三至六年级按人教PEP三年级起点常用词整理，非出版社授权电子教材；一二年级为原创启蒙词。",
        },
        "books": books,
    }
    output_path.write_text(
        json.dumps(root, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )


def main() -> None:
    """
    生成并校验小学英语十二个离线推荐分册。

    使用方法：
    在项目根目录执行本脚本并提供两个公开参考目录。脚本生成资源后会打印每册词数，便于在
    Android构建前快速核对范围。

    @return 无返回值。
    """

    arguments = parse_arguments()
    books = [create_prep_book(grade, term) for grade in (1, 2) for term in (1, 2)]
    books.extend(
        load_pep_book(arguments.pep_source, arguments.simple_source, grade, term)
        for grade in range(3, 7)
        for term in (1, 2)
    )
    validate_books(books)
    write_asset(arguments.output, books)

    for book in books:
        print(f"{book['title']}: {len(book['words'])}")


if __name__ == "__main__":
    main()
