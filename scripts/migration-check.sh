#!/usr/bin/env bash
# ============================================================
# 盘家智管 · Flyway 段位/序号/孤儿引用 CI 校验
#
# 规则来源：ruoyi-admin/src/main/resources/db/migration/README.md
#
# 校验项：
#   1. 段位与模块归属匹配（V11xxxx 只允许在 panjia-people/ 下，等等）
#   2. 跨模块文件无重名（避免 Maven 多模块资源冲突）
#   3. 同一段位内序号连续性（允许跳号但 WARN 提示）
#   4. sys_menu.parent_id 不引用未 INSERT 的 menu_id（孤儿引用）
#   5. role_id=1（超管）不应绑非超管菜单
#
# 用法：
#   bash scripts/migration-check.sh            # 全量校验
#   bash scripts/migration-check.sh --strict   # 把跳号 WARN 升级为 ERROR
#
# 兼容：bash 3.2+ / zsh 5+（macOS 系统 bash 3.2 可跑，CI Linux/GitHub Actions bash 4+ 同样可跑）
# ============================================================
set -o pipefail

cd "$(dirname "$0")/.."

STRICT=0
if [ "${1:-}" = "--strict" ]; then STRICT=1; fi

ERRORS=0
WARNS=0

# 临时文件存放关联语义（兼容 bash 3.2 无关联数组）
TMPDIR_MIG=$(mktemp -d -t mig-check.XXXXXX)
trap 'rm -rf "$TMPDIR_MIG"' EXIT

# warn/fail 由子 shell awk|while 调用时无法回写主 shell 计数，
# 故所有规则把 warn 文本追加到 WARK_FILE，主 shell 末尾一次性消费并按 strict 升级
WARK_FILE="$TMPDIR_MIG/warns"
> "$WARK_FILE"

# 段位归属：segment_owner 文件每行 "2位前缀<空格>模块"
# 新域接入：在此追加一行 `XX <module-name>` 即可
SEGMENT_OWNER_FILE="$TMPDIR_MIG/segment_owner"
cat > "$SEGMENT_OWNER_FILE" <<'EOF'
10 ruoyi-admin
11 panjia-people
12 panjia-import
13 panjia-outbox
14 panjia-performance
15 panjia-customer
16 panjia-commission
17 panjia-payroll
18 panjia-ledger
19 panjia-rules
EOF

ok()    { echo "  [OK]   $*"; }
warn()  { echo "  [WARN] $*"; WARNS=$((WARNS + 1)); if [ "$STRICT" = "1" ]; then ERRORS=$((ERRORS + 1)); fi; }
defer_warn() { echo "$*" >> "$WARK_FILE"; }   # 给子 shell 用
fail()  { echo "  [FAIL] $*"; ERRORS=$((ERRORS + 1)); }

# 段位前缀数字 → 模块名
segment_owner() {
    local prefix="$1"
    awk -v p="$prefix" '$1 == p { print $2; exit}' "$SEGMENT_OWNER_FILE"
}

# ----------------------------------------------------------
# 收集所有 SQL
# ----------------------------------------------------------
SQL_FILES=()
while IFS= read -r f; do
    SQL_FILES+=("$f")
done < <(find . -path "*/src/main/resources/db/migration/V*.sql" -not -path "*/target/*" 2>/dev/null | sort)

echo "============================================================"
echo "  Flyway 段位/序号/孤儿引用 CI 校验"
echo "  扫描到 ${#SQL_FILES[@]} 个迁移脚本"
echo "  模式: $([ "$STRICT" = "1" ] && echo "strict (WARN=ERROR)" || echo "default (WARN 仅提示)")"
echo "============================================================"

# ----------------------------------------------------------
# 规则 1：段位与模块归属匹配
# ----------------------------------------------------------
echo ""
echo "[规则 1] 段位与模块归属匹配"

# 同时把 version → fname → module 写到临时文件，给规则 3 用
VERSION_TABLE="$TMPDIR_MIG/versions.tsv"
> "$VERSION_TABLE"

for sql in "${SQL_FILES[@]}"; do
    fname=$(basename "$sql")
    version=$(echo "$fname" | sed -E 's/^V([0-9]+)__.*/\1/')
    # 路径形如 ./panjia-modules/panjia-import/src/... 或 ./ruoyi-admin/src/...
    # 取 panjia-modules/ 后的第一段；其它情况取第一级
    module=$(echo "$sql" | sed -E 's|^\./panjia-modules/([^/]+)/.*|\1|; s|^\./([^/]+)/.*|\1|')
    printf '%s\t%s\t%s\n' "$version" "$fname" "$module" >> "$VERSION_TABLE"

    # V1 是 RuoYi 基线，不参与段位归属
    if [ "$version" = "1" ]; then
        if [ "$module" != "ruoyi-admin" ]; then
            fail "$fname: V1 基线脚本必须在 ruoyi-admin/ 下，当前在 $module"
        else
            ok "$fname: V1 基线归属 ruoyi-admin"
        fi
        continue
    fi

    prefix=$(echo "$version" | cut -c1-2)
    expected_module=$(segment_owner "$prefix")

    if [ -z "$expected_module" ]; then
        # 10-99 范围但未在白名单 → 提示申请
        if [ "$prefix" -ge 10 ] && [ "$prefix" -le 99 ] 2>/dev/null; then
            fail "$fname: 段位 ${prefix}xxx 尚未在 scripts/migration-check.sh 的 SEGMENT_OWNER_FILE 登记，请先申请再使用"
        else
            fail "$fname: 无法识别段位前缀 '$prefix'（version=$version）"
        fi
        continue
    fi

    if [ "$module" != "$expected_module" ]; then
        fail "$fname: 段位 ${prefix}xxx 应在 '$expected_module/' 下，但实际在 '$module/'"
    else
        ok "$fname: V$version 归属 $module 符合 ${prefix}xxx 段位规则"
    fi
done

# ----------------------------------------------------------
# 规则 2：跨模块文件无重名（Maven 资源冲突）
# ----------------------------------------------------------
echo ""
echo "[规则 2] 跨模块文件无重名（避免 Maven classpath 冲突）"

DUP_NAMES=$(awk -F'\t' '{print $2}' "$VERSION_TABLE" | sort | uniq -d)
if [ -n "$DUP_NAMES" ]; then
    for n in $DUP_NAMES; do
        modules=$(awk -F'\t' -v name="$n" '$2 == name {print $3}' "$VERSION_TABLE" | sort -u | tr '\n' ',' | sed 's/,$//')
        fail "$n duplicated in modules: $modules (Maven 'Found more than one migration')"
    done
else
    ok "cross-module filename uniqueness verified"
fi

# ----------------------------------------------------------
# 规则 3：同一段位内序号连续性（仅 WARN）
# ----------------------------------------------------------
echo ""
echo "[规则 3] 同一段位内序号连续性（允许跳号，仅 WARN）"

# 按段位分组列出最后版本
echo ""
echo "  各段位版本分布："

# 提取每个 prefix 下最大的 version
awk -F'\t' '{
    v = $1 + 0
    p = substr($1, 1, 2)
    if (!(p in max) || v > max[p]) max[p] = v
    if (!(p in cnt)) cnt[p] = 0
    cnt[p]++
}
END {
    for (p in max) print p "|" max[p] "|" cnt[p]
}' "$VERSION_TABLE" | sort -t'|' -k1,1n | while IFS='|' read -r prefix last count; do
    echo "    ${prefix}xxx -> last V${last} (${count} scripts)"
done

# 跨段位跳号检测（每个段位内排序后检测 gap）
awk -F'\t' '{
    v = $1 + 0
    p = substr($1, 1, 2)
    print p "\t" v
}' "$VERSION_TABLE" | sort -k1,1n -k2,2n | awk -F'\t' '
BEGIN { prev_p = ""; prev_v = 0 }
{
    if ($1 == prev_p) {
        if ($2 > prev_v + 1) {
            printf "DEFER_WARN\t段位 %sxxx 跳号: V%d -> V%d (中间缺 V%d)\n", prev_p, prev_v, $2, prev_v + 1
        }
    }
    prev_p = $1; prev_v = $2
}' | while IFS=$'\t' read -r tag msg; do
    if [ "$tag" = "DEFER_WARN" ]; then
        defer_warn "$msg"
    fi
done

# ----------------------------------------------------------
# 规则 4：sys_menu.parent_id 孤儿引用
# 用 python3 解析 SQL（CI Linux/macOS 都有 python3）
# 处理两种 INSERT 形式：
#   A) INSERT INTO sys_menu VALUES (...);                    [V1 基线]
#   B) INSERT INTO sys_menu (menu_id, menu_name, parent_id, ...) VALUES (...); [V100001+]
# 按 SQL 引号规则分列，找 parent_id 位置
# ----------------------------------------------------------
echo ""
echo "[规则 4] sys_menu.parent_id 孤儿引用检查"

MENU_ID_FILE="$TMPDIR_MIG/menu_ids"
PARENT_REF_FILE="$TMPDIR_MIG/parent_refs"
> "$MENU_ID_FILE"
> "$PARENT_REF_FILE"

PYTHON_BIN=$(command -v python3 || command -v python || echo "")
if [ -z "$PYTHON_BIN" ]; then
    warn "未找到 python3/python，跳过规则 4 孤儿引用检查"
else
    # 把所有 SQL 文件路径写入列表
    SQL_LIST_FILE="$TMPDIR_MIG/sql_list"
    printf '%s\n' "${SQL_FILES[@]}" > "$SQL_LIST_FILE"

    "$PYTHON_BIN" - "$SQL_LIST_FILE" "$MENU_ID_FILE" "$PARENT_REF_FILE" <<'PYEOF'
import re, sys

sql_list, menu_id_file, parent_ref_file = sys.argv[1], sys.argv[2], sys.argv[3]

# sys_menu 的列名顺序（V1 与 V100001 列数略有差异，但 menu_id/menu_name/parent_id 位置固定）
# 用正则识别 "(menu_id, menu_name, parent_id, ...)" 或裸 VALUES

# 逐文件读入
all_sql = []
with open(sql_list) as f:
    for line in f:
        p = line.strip()
        if p:
            try:
                with open(p) as fp:
                    all_sql.append((p, fp.read()))
            except Exception:
                pass

# 把每个文件中所有 sys_menu INSERT 语句提取出来
insert_re = re.compile(
    r"INSERT\s+INTO\s+sys_menu\s*(?:\(([^)]+)\))?\s*VALUES\s*\(([^;]+)\)\s*;",
    re.IGNORECASE | re.DOTALL,
)

menu_ids = set()
parent_refs = []  # [(parent_id, file, menu_id), ...]

for path, content in all_sql:
    # 把跨行 INSERT 压成一行：替换 \n 在 (...) 内部
    # 简单做法：把所有换行替换为空格，因为 sys_menu INSERT 内容里没有真正的换行需求
    normalized = re.sub(r"\s+", " ", content)

    for m in insert_re.finditer(normalized):
        cols_str, vals_str = m.group(1), m.group(2)
        # 分列：按 SQL 风格（处理引号、括号）
        def split_sql_values(s):
            out, buf, in_q, q_ch, depth = [], [], False, '', 0
            for ch in s:
                if in_q:
                    buf.append(ch)
                    if ch == q_ch:
                        in_q = False
                elif ch in ("'", '"'):
                    in_q = True; q_ch = ch
                    buf.append(ch)
                elif ch == '(':
                    depth += 1; buf.append(ch)
                elif ch == ')':
                    depth -= 1; buf.append(ch)
                elif ch == ',' and depth == 0:
                    out.append(''.join(buf).strip())
                    buf = []
                else:
                    buf.append(ch)
            if buf:
                out.append(''.join(buf).strip())
            return out

        vals = split_sql_values(vals_str)

        if cols_str:
            cols = [c.strip().strip('"').strip("'").lower() for c in cols_str.split(',')]
            try:
                idx_menu = cols.index('menu_id')
                idx_parent = cols.index('parent_id')
            except ValueError:
                continue
        else:
            # 默认顺序: menu_id, menu_name, parent_id, ...
            idx_menu, idx_parent = 0, 2

        if idx_menu >= len(vals) or idx_parent >= len(vals):
            continue

        def to_int(s):
            s = s.strip()
            if not s or s.upper() == 'NULL':
                return None
            try:
                return int(s)
            except ValueError:
                # 比如 now() 之类的，跳过
                return None

        mid = to_int(vals[idx_menu])
        pid = to_int(vals[idx_parent])

        if mid is not None:
            menu_ids.add(mid)
        if pid is not None:
            parent_refs.append((pid, path, mid))

with open(menu_id_file, 'w') as f:
    for mid in sorted(menu_ids):
        f.write(f"{mid}\n")
with open(parent_ref_file, 'w') as f:
    for pid, path, mid in parent_refs:
        fname = path.split('/')[-1]
        f.write(f"{pid}\t{fname}\t{mid if mid else ''}\n")
PYEOF

    MENU_ID_COUNT=$(wc -l < "$MENU_ID_FILE" 2>/dev/null | tr -d ' ')
    MENU_ID_COUNT=${MENU_ID_COUNT:-0}
    ok "已收集 $MENU_ID_COUNT 个 sys_menu.menu_id"

    # 检测孤儿引用（基于第 3 个数字启发式的兜底，现在用真正的 SQL 解析）
    ORPHAN_COUNT=0
    while IFS=$'\t' read -r pid fname mid_or_blank; do
        [ -z "$pid" ] && continue
        if [ "$pid" = "0" ]; then continue; fi
        if ! grep -qx "$pid" "$MENU_ID_FILE" 2>/dev/null; then
            mid_note="(本行 menu_id=${mid_or_blank:-?})"
            fail "孤儿引用: $fname 中 sys_menu.parent_id=$pid 未在任何迁移脚本中 INSERT ${mid_note}"
            ORPHAN_COUNT=$((ORPHAN_COUNT + 1))
        fi
    done < "$PARENT_REF_FILE"
    [ "$ORPHAN_COUNT" -eq 0 ] && ok "所有 sys_menu.parent_id 引用均合法"
fi

# ----------------------------------------------------------
# 规则 5：role_id=1（超管）不应绑非超管菜单（仅 WARN）
# ----------------------------------------------------------
echo ""
echo "[规则 5] role_id=1（超管）菜单绑定语义（仅 WARN）"

ROLE_BIND_COUNT=0
ROLE_BIND_FILES=()
for sql in "${SQL_FILES[@]}"; do
    fname=$(basename "$sql")
    if grep -qE "INSERT INTO sys_role_menu" "$sql" 2>/dev/null && \
       grep -qE "VALUES[[:space:]]*\([[:space:]]*1," "$sql" 2>/dev/null; then
        ROLE_BIND_COUNT=$((ROLE_BIND_COUNT + 1))
        ROLE_BIND_FILES+=("$fname")
    fi
done
if [ "$ROLE_BIND_COUNT" -gt 0 ]; then
    defer_warn "检测到 $ROLE_BIND_COUNT 个脚本包含显式绑定 role_id=1 的 sys_role_menu 行（${ROLE_BIND_FILES[*]}）；通常超管菜单应通过 'admin' 标识动态授权，请确认是否合理"
else
    ok "未检测到显式绑定 role_id=1 的 sys_role_menu 行"
fi

# ----------------------------------------------------------
# 消费 deferred warns（在 strict 模式下升级为 ERROR）
# ----------------------------------------------------------
if [ -s "$WARK_FILE" ]; then
    while IFS= read -r msg; do
        if [ "$STRICT" = "1" ]; then
            fail "$msg"
        else
            warn "$msg"
        fi
    done < "$WARK_FILE"
fi

# ----------------------------------------------------------
# 总结
# ----------------------------------------------------------
echo ""
echo "============================================================"
if [ "$ERRORS" -gt 0 ]; then
    echo "  FAIL: $ERRORS 处错误，$WARNS 处警告"
    echo "============================================================"
    exit 1
fi

if [ "$WARNS" -gt 0 ]; then
    echo "  PASS with warnings: $WARNS 处警告"
else
    echo "  PASS: 全部规则校验通过"
fi
echo "============================================================"
exit 0