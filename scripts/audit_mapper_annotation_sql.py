#!/usr/bin/env python3
"""审计 MyBatis 注解 SQL（@Select/@Update/@Insert/@Delete）的 XML 陷阱。

两类必须拦截的问题：
  A. 无 <script> 包裹，却出现动态标签（<foreach>/<if>/<where>/<trim>/<set>/<choose>/<bind>/<include>）
     → MyBatis 走 RawSqlSource，标签原样下发数据库，运行期语法错误。
  B. 无 <script> 包裹，却出现 XML 实体（&lt; &gt; &amp; &quot; &apos;）
     → 实体原样下发，PG 在 ";" 处截断语句。
  C. 有 <script> 包裹，但内部未转义的裸 "<"（如 `<= CURRENT_DATE`、`a < b`）
     → XML 解析失败，Mapper 装载期即崩。

用法: python3 audit_mapper_annotation_sql.py <源码根目录> [更多根目录...]
退出码: 0 无问题 / 1 发现问题
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANNOTATIONS = ("@Select", "@Update", "@Insert", "@Delete")
DYNAMIC_TAGS = ("foreach", "if", "where", "trim", "set", "choose", "when",
                "otherwise", "bind", "include", "sql")
ENTITY_RE = re.compile(r"&(lt|gt|amp|quot|apos);")
TAG_RE = re.compile(r"<\s*(/)?\s*([A-Za-z_][\w.-]*)")
SCRIPT_RE = re.compile(r"^\s*<script\s*>", re.IGNORECASE)


def unescape_java(s: str) -> str:
    out, i = [], 0
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            nxt = s[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\"}
            out.append(mapping.get(nxt, "\\" + nxt))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def _read_literal(source: str, i: int):
    """从下标 i（已跳过空白）读一个字符串字面量，返回 (内容, 结束下标)；不是字面量返回 None。"""
    if source.startswith('"""', i):
        end = source.find('"""', i + 3)
        return (None, i) if end == -1 else (source[i + 3:end], end + 3)
    if i < len(source) and source[i] == '"':
        j, buf = i + 1, []
        while j < len(source):
            if source[j] == "\\":
                buf.append(source[j:j + 2])
                j += 2
                continue
            if source[j] == '"':
                break
            buf.append(source[j])
            j += 1
        return unescape_java("".join(buf)), j + 1
    return None, i


def extract_annotations(source: str):
    """产出 (行号, 注解名, SQL 文本)。

    手工扫描字符串字面量，正确处理 Java 文本块（\"\"\"）、普通字符串，
    以及 "+" 拼接的多个字面量，避免 SQL 内部 ')' + 换行导致提前截断。
    """
    head = re.compile(r"@(Select|Update|Insert|Delete)\s*\(")
    for m in head.finditer(source):
        name = m.group(1)
        line = source[:m.start()].count("\n") + 1
        i = m.end()
        chunks = []
        while True:
            while i < len(source) and source[i] in " \t\r\n":
                i += 1
            if source.startswith('"""', i):
                end = source.find('"""', i + 3)
                if end == -1:
                    break
                chunks.append(source[i + 3:end])
                i = end + 3
            elif i < len(source) and source[i] == '"':
                j, buf = i + 1, []
                while j < len(source):
                    if source[j] == "\\":
                        buf.append(source[j:j + 2])
                        j += 2
                        continue
                    if source[j] == '"':
                        break
                    buf.append(source[j])
                    j += 1
                chunks.append(unescape_java("".join(buf)))
                i = j + 1
            else:
                break
            k = i
            while k < len(source) and source[k] in " \t\r\n":
                k += 1
            if k < len(source) and source[k] == "+":
                i = k + 1
                continue
            break
        if chunks:
            yield line, name, "".join(chunks)


def audit_file(path: Path, problems: list):
    source = path.read_text(encoding="utf-8", errors="replace")
    for line, name, sql in extract_annotations(source):
        if not sql.strip():
            continue
        has_script = bool(SCRIPT_RE.match(sql))

        if has_script:
            # C：<script> 块必须能被 XML 解析器解析
            try:
                ET.fromstring(sql)
            except ET.ParseError as e:
                problems.append((path, line, f"{name} <script> 块 XML 解析失败：{e} "
                                            "（裸 '<' 必须写成 &lt;，裸 '&' 必须写成 &amp;）"))
            continue

        # A：无 <script> 却含动态标签
        tags = {m.group(2).lower() for m in TAG_RE.finditer(sql)}
        dynamic = sorted(t for t in tags if t in DYNAMIC_TAGS)
        if dynamic:
            problems.append((path, line, f"{name} 含动态标签 {dynamic} 但缺少 <script> 包裹"
                                        "（标签会原样下发数据库）"))
        # B：无 <script> 却含 XML 实体
        entities = sorted({m.group(0) for m in ENTITY_RE.finditer(sql)})
        if entities:
            problems.append((path, line, f"{name} 含 XML 实体 {entities} 但缺少 <script> 包裹"
                                        "（实体会原样下发，分号会被当成语句结束符）"))


def main() -> int:
    roots = [Path(p) for p in sys.argv[1:]] or [Path.cwd()]
    files = []
    for root in roots:
        files.extend(p for p in root.rglob("*.java") if "/target/" not in p.as_posix())
    problems = []
    for f in sorted(files):
        if re.search(r"@(Select|Update|Insert|Delete)\s*\(", f.read_text(encoding="utf-8", errors="replace")):
            audit_file(f, problems)

    checked = len([f for f in files])
    if not problems:
        print(f"OK：扫描 {checked} 个 Java 文件，未发现 MyBatis 注解 SQL 的 XML/实体陷阱")
        return 0
    print(f"发现 {len(problems)} 处问题（扫描 {checked} 个 Java 文件）：\n")
    for path, line, msg in problems:
        print(f"  {path}:{line}\n    {msg}\n")
    return 1


if __name__ == "__main__":
    sys.exit(main())
