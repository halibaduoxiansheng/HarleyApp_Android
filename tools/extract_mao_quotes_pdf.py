"""把用户提供的《毛主席语录》汉英对照 PDF 提取为 Debug 离线语录资源。"""

from __future__ import annotations

import argparse
import hashlib
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import pdfplumber


# 这份汉英对照版从 PDF 第23页开始进入语录正文，中文页和英文页按奇偶页成对排列。
# 下表记录33个中文章节在 PDF 中的实际起始页，下一章节起始页即为当前章节结束边界。
CHAPTER_STARTS: tuple[tuple[str, int], ...] = (
    ("一、共产党", 23),
    ("二、阶级和阶级斗争", 37),
    ("三、社会主义和共产主义", 65),
    ("四、正确处理人民内部矛盾", 109),
    ("五、战争与和平", 135),
    ("六、帝国主义和一切反动派都是纸老虎", 161),
    ("七、敢于斗争，敢于胜利", 179),
    ("八、人民战争", 191),
    ("九、人民军队", 213),
    ("十、党委领导", 221),
    ("十一、群众路线", 247),
    ("十二、政治工作", 277),
    ("十三、官兵关系", 303),
    ("十四、军民关系", 311),
    ("十五、三大民主", 319),
    ("十六、教育和训练", 333),
    ("十七、为人民服务", 341),
    ("十八、爱国主义和国际主义", 349),
    ("十九、革命英雄主义", 361),
    ("二十、勤俭建国", 371),
    ("二十一、自力更生，艰苦奋斗", 385),
    ("二十二、思想方法和工作方法", 401),
    ("二十三、调查研究", 451),
    ("二十四、纠正错误思想", 465),
    ("二十五、团结", 491),
    ("二十六、纪律", 497),
    ("二十七、批评和自我批评", 505),
    ("二十八、共产党员", 523),
    ("二十九、干部", 539),
    ("三十、青年", 563),
    ("三十一、妇女", 573),
    ("三十二、文化艺术", 583),
    ("三十三、学习", 593),
)

LAST_CHINESE_CONTENT_PAGE_EXCLUSIVE = 609
EXPECTED_QUOTE_COUNT = 427
HEADING_FONT_SIZE = 14.0
BODY_FONT_SIZE = 9.5
SOURCE_FONT_SIZE = 6.5
PAGE_NUMBER_TOP_RATIO = 0.84


@dataclass(frozen=True)
class ExtractedQuote:
    """保存一则已经从版面行重组完成的正文与出处。

    使用方法：
    提取器遇到“正文 -> 小字号出处 -> 下一则正文”的完整结构后创建本对象；输出函数会把正文和
    出处写在同一个 TXT 段落内，避免 App 解析时把出处误当成独立语录。

    参数：
    body：去除 PDF 强制换行和多余字间空格后的语录正文。
    source：紧随正文的小字号出处信息。
    """

    body: str
    source: str


def parse_arguments() -> argparse.Namespace:
    """解析命令行中的源 PDF 与目标 TXT 路径。

    使用方法：
    执行 ``python tools/extract_mao_quotes_pdf.py INPUT_PDF OUTPUT_TXT``。目标目录不存在时会自动
    创建；已有目标文件会在全部结构校验通过后原子替换。

    返回值：
    包含 ``input_pdf`` 和 ``output_txt`` 两个 ``Path`` 字段的命令行参数对象。
    """

    parser = argparse.ArgumentParser(
        description="Extract Chinese Mao quotations from the supplied bilingual PDF."
    )
    parser.add_argument("input_pdf", type=Path)
    parser.add_argument("output_txt", type=Path)
    return parser.parse_args()


def compact_pdf_text(value: str) -> str:
    """清除 PDF 文本层的空字符、字间空格和排版换行。

    参数：
    value：pdfplumber 返回的单行或多行文字。

    返回值：
    保留标点和原有字符顺序、但不含排版空白的连续文本。
    """

    return re.sub(r"\s+", "", value.replace("\x00", ""))


def dominant_font_size(line: dict) -> float:
    """取得一条 PDF 版面行中出现次数最多的字号。

    参数：
    line：``pdfplumber.Page.extract_text_lines(return_chars=True)`` 返回的一条行记录。

    返回值：
    以 0.1pt 为精度的主字号；没有可见字符时返回 0。
    """

    sizes = [
        round(float(character.get("size", 0.0)), 1)
        for character in line.get("chars", [])
        if str(character.get("text", "")).strip()
    ]
    if not sizes:
        return 0.0
    return Counter(sizes).most_common(1)[0][0]


def is_printed_page_number(line: dict, page_height: float, text: str) -> bool:
    """判断当前行是否为页面底部的印刷页码。

    参数：
    line：包含 ``top`` 坐标的 PDF 行记录。
    page_height：当前 PDF 页面高度。
    text：已经压缩空白的行文本。

    返回值：
    行位于页面底部且只包含阿拉伯数字时返回 ``True``，否则返回 ``False``。
    """

    return (
        float(line.get("top", 0.0)) > page_height * PAGE_NUMBER_TOP_RATIO
        and re.fullmatch(r"\d+", text) is not None
    )


def extract_chapter_quotes(pages: Iterable) -> list[ExtractedQuote]:
    """按字号层级从连续中文页面中恢复一章的独立语录。

    使用方法：
    传入同一章节按阅读顺序排列的中文页。该版 PDF 使用 10.6pt 正文、7.6pt 出处、15pt 标题；
    函数跨页保留状态，因此一则语录即使跨越两页也不会被强行截断。

    参数：
    pages：同一章节的 pdfplumber 页面迭代器，只能包含中文页。

    返回值：
    按原书顺序排列、正文与出处都非空的 ``ExtractedQuote`` 列表。

    异常：
    遇到孤立出处、无法识别的字号或缺少正文/出处时抛出 ``ValueError``，防止生成静默缺页资源。
    """

    quotes: list[ExtractedQuote] = []
    body_lines: list[str] = []
    source_lines: list[str] = []

    def flush_quote() -> None:
        """把当前正文和出处固化为一则语录，并清空跨行缓存。"""

        nonlocal body_lines, source_lines
        if not body_lines and not source_lines:
            return
        if not body_lines or not source_lines:
            raise ValueError(
                "Incomplete quote block: "
                f"body_lines={len(body_lines)}, source_lines={len(source_lines)}"
            )
        quotes.append(
            ExtractedQuote(
                body=compact_pdf_text("".join(body_lines)),
                source=compact_pdf_text("".join(source_lines)),
            )
        )
        body_lines = []
        source_lines = []

    for page in pages:
        lines = page.extract_text_lines(strip=True, return_chars=True)
        for line in lines:
            text = compact_pdf_text(str(line.get("text", "")))
            if not text:
                continue

            font_size = dominant_font_size(line)
            if font_size >= HEADING_FONT_SIZE:
                continue
            if is_printed_page_number(line, float(page.height), text):
                continue

            if font_size >= BODY_FONT_SIZE:
                # 已经读到出处后再次出现大字号正文，说明上一则语录完整结束。
                if source_lines:
                    flush_quote()
                body_lines.append(text)
            elif font_size >= SOURCE_FONT_SIZE:
                source_lines.append(text)
            else:
                raise ValueError(
                    f"Unsupported font size {font_size:.1f} on PDF page {page.page_number}: {text}"
                )

    flush_quote()
    return quotes


def validate_chapter_heading(page, expected_title: str) -> None:
    """确认章节起始页的大字号标题与固定目录映射一致。

    参数：
    page：章节起始 PDF 页面。
    expected_title：代码表中该页应当出现的中文标题。

    返回值：
    校验成功时无返回值；标题不一致时抛出 ``ValueError``，提示源 PDF 版次不匹配。
    """

    heading = "".join(
        compact_pdf_text(str(line.get("text", "")))
        for line in page.extract_text_lines(strip=True, return_chars=True)
        if dominant_font_size(line) >= HEADING_FONT_SIZE
    )
    if heading != compact_pdf_text(expected_title):
        raise ValueError(
            f"Unexpected chapter heading on PDF page {page.page_number}: {heading!r}"
        )


def extract_document(input_pdf: Path) -> list[tuple[str, list[ExtractedQuote]]]:
    """读取指定版次 PDF 并提取33章中文正文。

    参数：
    input_pdf：用户提供的《毛主席语录》汉英对照版 PDF 路径。

    返回值：
    由“章节标题、该章语录列表”组成的顺序列表。

    异常：
    文件不存在、页数不足、章节标题不匹配、中文页异常或最终不是427则语录时抛出异常。
    """

    if not input_pdf.is_file():
        raise FileNotFoundError(input_pdf)

    chapters: list[tuple[str, list[ExtractedQuote]]] = []
    with pdfplumber.open(input_pdf) as pdf:
        if len(pdf.pages) < LAST_CHINESE_CONTENT_PAGE_EXCLUSIVE - 1:
            raise ValueError(f"PDF has too few pages: {len(pdf.pages)}")

        for index, (title, start_page) in enumerate(CHAPTER_STARTS):
            end_page = (
                CHAPTER_STARTS[index + 1][1]
                if index + 1 < len(CHAPTER_STARTS)
                else LAST_CHINESE_CONTENT_PAGE_EXCLUSIVE
            )
            validate_chapter_heading(pdf.pages[start_page - 1], title)

            # 这份双语版的中文正文只在奇数 PDF 页；跳过相邻英文页可以避免把译文混入离线数据。
            chinese_pages = [pdf.pages[page_number - 1] for page_number in range(start_page, end_page, 2)]
            quotes = extract_chapter_quotes(chinese_pages)
            chapters.append((title, quotes))

    total_quotes = sum(len(quotes) for _, quotes in chapters)
    if len(chapters) != len(CHAPTER_STARTS) or total_quotes != EXPECTED_QUOTE_COUNT:
        raise ValueError(
            f"Unexpected extraction result: chapters={len(chapters)}, quotes={total_quotes}"
        )
    return chapters


def build_txt(input_pdf: Path, chapters: list[tuple[str, list[ExtractedQuote]]]) -> str:
    """把已校验章节转换为 App 解析器支持的 UTF-8 TXT。

    参数：
    input_pdf：用于生成可核验源文件指纹的 PDF 路径。
    chapters：``extract_document`` 返回的33章语录。

    返回值：
    带开发许可元数据、Markdown 风格章节标题和空行段落边界的完整 TXT 字符串。
    """

    digest = hashlib.sha256(input_pdf.read_bytes()).hexdigest().upper()
    output_lines = [
        "@title: 毛主席语录",
        f"@source: 用户提供的《毛主席语录》汉英对照版PDF（文件指纹 {digest[:12]}）",
        "@license: 用户提供，仅用于当前Debug开发验证；正式再分发前需补齐许可",
        "@redistribution-authorized: false",
        "",
    ]

    for title, quotes in chapters:
        output_lines.append(f"# {title}")
        output_lines.append("")
        for quote in quotes:
            output_lines.append(quote.body)
            output_lines.append(f"出处：{quote.source}")
            output_lines.append("")

    return "\n".join(output_lines).rstrip() + "\n"


def write_atomically(output_txt: Path, content: str) -> None:
    """在同目录写入临时文件并原子替换最终 TXT。

    参数：
    output_txt：最终 Debug 资产路径。
    content：已经完成章节数和语录数校验的 UTF-8 文本。

    返回值：
    写入成功时无返回值；文件系统错误会直接向调用方抛出，旧文件不会被半写入内容覆盖。
    """

    output_txt.parent.mkdir(parents=True, exist_ok=True)
    temporary_txt = output_txt.with_suffix(output_txt.suffix + ".tmp")
    temporary_txt.write_text(content, encoding="utf-8", newline="\n")
    temporary_txt.replace(output_txt)


def main() -> None:
    """执行提取、结构校验和原子写入，并输出不含正文的英文摘要日志。"""

    arguments = parse_arguments()
    chapters = extract_document(arguments.input_pdf)
    content = build_txt(arguments.input_pdf, chapters)
    write_atomically(arguments.output_txt, content)
    quote_count = sum(len(quotes) for _, quotes in chapters)
    print(
        "Extraction complete: "
        f"chapters={len(chapters)}, quotes={quote_count}, utf8_bytes={len(content.encode('utf-8'))}"
    )


if __name__ == "__main__":
    main()
