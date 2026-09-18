#!/usr/bin/env python3
"""Spring bean 名冲突静态审计。

背景
----
`PanjiaAutoConfiguration` 上有 `@ComponentScan(basePackages = "com.panjia")`，
整个 `com.panjia.**` 树位于**同一个 Spring 上下文**。而 Spring 对
`@Service/@Component/@Repository/@Configuration/@Controller/@RestController/@Mapper`
默认取「简单类名首字母小写」作为 bean 名 —— 于是不同模块里两个**同名类**
会产生同一个 bean 名，启动即抛：

    ConflictingBeanDefinitionException: Annotation-specified bean name 'x' for bean
    class [com.a.X] conflicts with existing, non-compatible bean definition of same
    name and class [com.b.X]

编译期完全发现不了（各模块单独编译都通过），只有启动才炸。故需要本静态审计。

用法
----
    python3 scripts/audit_spring_bean_name_collision.py [根目录]

退出码：0 = 无冲突；1 = 发现冲突。
"""
from __future__ import annotations

import pathlib
import re
import sys

STEREO = (
    "Service", "Component", "Repository", "Configuration",
    "Controller", "RestController", "Mapper",
)

# @Service / @Service("name") / @Service(value = "name")
ANNO = re.compile(
    r"@(?:%s)\b\s*(?:\(\s*(?:value\s*=\s*)?\"([^\"]+)\"\s*\))?" % "|".join(STEREO)
)
TYPE_DECL = re.compile(
    r"(?:^|\s)(?:public\s+|final\s+|abstract\s+|sealed\s+|non-sealed\s+)*"
    r"(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)"
)
PKG = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)


def strip_comments(text: str) -> str:
    """去掉块注释与行注释，避免 javadoc / 注释里的 @Service 造成误报。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def stereotype_of(text: str) -> tuple[str, str | None] | None:
    """返回 (注解名, 显式 bean 名或 None)；无 stereotype 返回 None。"""
    m = ANNO.search(text)
    if not m:
        return None
    anno = re.search(r"@(\w+)", m.group(0)).group(1)
    return anno, m.group(1)


def main() -> int:
    root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
    files = [
        p for p in root.rglob("*.java")
        if "/src/main/java/" in p.as_posix()
    ]
    # 只看参与上下文扫描的包：com.panjia.*（业务模块）
    entries: list[tuple[str, str, str, str | None]] = []  # (beanName, fqn, anno, file)
    for p in files:
        raw = p.read_text(encoding="utf-8", errors="ignore")
        txt = strip_comments(raw)
        pkg_m = PKG.search(txt)
        if not pkg_m or not pkg_m.group(1).startswith("com.panjia"):
            continue
        st = stereotype_of(txt)
        if not st:
            continue
        anno, explicit = st
        decl = TYPE_DECL.search(txt)
        if not decl:
            continue
        simple = decl.group(1)
        bean = explicit if explicit else simple[0].lower() + simple[1:]
        entries.append((bean, f"{pkg_m.group(1)}.{simple}", anno, str(p)))

    by_bean: dict[str, list[tuple[str, str, str]]] = {}
    for bean, fqn, anno, path in entries:
        by_bean.setdefault(bean, []).append((fqn, anno, path))

    problems = []
    for bean, group in sorted(by_bean.items()):
        fqns = {g[0] for g in group}
        if len(fqns) > 1:
            problems.append((bean, sorted(fqns, key=lambda s: s)))

    print(f"扫描 {len(files)} 个 Java 文件，命中 panjia stereotype bean {len(entries)} 个，"
          f"唯一 bean 名 {len(by_bean)} 个")
    if not problems:
        print("OK：未发现 Spring bean 名冲突")
        return 0

    print(f"\n发现 {len(problems)} 处 bean 名冲突（启动必炸）：\n")
    for bean, fqns in problems:
        print(f"  bean 名 '{bean}'：")
        for f in fqns:
            print(f"    - {f}")
        print()
    print("修复方式二选一：① 给其中一个类显式命名 "
          '@Service("xxxYyyService")；② 移除多余的同名类（若它是纯转发壳）。')
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
