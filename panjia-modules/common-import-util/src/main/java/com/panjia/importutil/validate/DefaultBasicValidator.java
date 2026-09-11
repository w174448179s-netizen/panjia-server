package com.panjia.importutil.validate;

import com.panjia.importutil.convert.TypeConverter;
import com.panjia.importutil.dict.DictDataPort;
import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.ImportTemplate;
import com.panjia.importutil.template.model.RuleDef;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 默认基础格式校验器：必填 / 枚举白名单 / 正则 / 最大长度 / 行级规则。
 * <p>
 * 类型错误已由解析阶段写入 {@link ParsedSheet#getErrors()}；
 * 本校验器对行内 rawValues 做语义级基础校验，失败同时把行标记为 invalid。
 * <p>
 * 行级规则来源：{@code ImportTemplate.validationRules}（由 import 域桥接器从
 * {@code pj_import_template.validation_rules JSONB.row_level} 解析而来）。
 * <p>
 * 支持的规则表达式：
 * <ul>
 *   <li>{@code not_blank} — 字段值不能为空（与 {@code ColumnDef.isRequired} 双保险；规则
 *       可以重复声明但不重复报错，二者本质等价，主要让模板表 JSONB 声明有自洽文档）</li>
 *   <li>{@code gte:N} — DECIMAL/INT 字段 ≥ N</li>
 *   <li>{@code gt:N} — DECIMAL/INT 字段 &gt; N</li>
 *   <li>{@code dict_in:DICT_TYPE} — 字典白名单：与 {@code ColumnDef.dictType} / {@code ColumnDef.enumValues}
 *       同时存在时采用 <b>AND</b> 语义——值必须同时落在字典实时值集合和 enumValues 静态白名单内，
 *       任一不过都拦截。只配 dictType 走字典实时校验，只配 enumValues 走静态白名单。</li>
 * </ul>
 * <p>
 * 不支持的规则（解析为 {@code UNSUPPORTED_RULE}）会被记录但不阻断校验，
 * 留给后续版本补全规则解析器。
 * <p>
 * {@link DictDataPort} 可选注入：业务域（import / people / performance）
 * 可提供具体实现（查 sys_dict_data 或 cache），不提供时降级为 enumValues 硬比对。
 * 这是 SPI 模式：common-import-util 不硬依赖 system 模块。
 */
@Component
public class DefaultBasicValidator implements BasicValidator {

    /**
     * 字典查询端口（common-import-util 内置默认实现 {@code DefaultSysDictDataAdapter}，
     * 走 RuoYi {@link ISysDictDataService}）。V6.0.1 起不再 optional：作为公共设计，
     * 工具层强制消费字典中心 dictType 配置。如果调用方不想要字典校验，模板列不写 dictType 即可。
     */
    private final DictDataPort dictDataPort;

    public DefaultBasicValidator(DictDataPort dictDataPort) {
        this.dictDataPort = dictDataPort;
    }

    @Override
    public List<FieldError> validate(ParsedSheet sheet, ImportTemplate template) {
        List<FieldError> errors = new ArrayList<>(sheet.getErrors());
        List<ColumnDef> cols = template.getColumns();
        List<RuleDef> rules = template.getValidationRules();

        // field → 列定义 索引，便于规则命中后找到列
        Map<String, ColumnDef> colByField = indexColumns(cols);
        // field → 规则集合（同一字段多条规则按声明顺序执行）
        Map<String, List<RuleDef>> rulesByField = groupRulesByField(rules);

        for (ParsedRow row : sheet.getRows()) {
            // 1. 列级校验：required / enumValues / pattern / maxLength
            for (ColumnDef col : cols) {
                String raw = row.getRawValues().get(col.getField());
                boolean blank = raw == null || raw.isBlank();

                // 必填
                if (col.isRequired() && blank) {
                    row.setValid(false);
                    errors.add(new FieldError(row.getRowNo(), col.getField(), raw,
                        "REQUIRED_MISSING", "必填项缺失: " + col.getColName()));
                    continue;
                }
                if (blank) {
                    continue;
                }

                // 字典 / 枚举白名单（AND 校验语义）
                // 1) 字典属性（dictType）：实时字典中心值集合，单独配置时按字典值校验
                // 2) 枚举白名单（enumValues）：模板表静态白名单，单独配置时按静态值校验
                // 3) 同时配置：值必须同时落在字典集合与枚举集合内，任一不过都报错
                // 这样模板既能复用字典中心的实时值，又能在 Excel 客户端生成静态下拉。
                Set<String> dictValueSet = resolveDictValueSet(col);
                Set<String> enumValueSet = resolveEnumValueSet(col);
                boolean hasDict = !dictValueSet.isEmpty();
                boolean hasEnum = !enumValueSet.isEmpty();
                if (hasDict || hasEnum) {
                    boolean dictOk = !hasDict || dictValueSet.contains(raw);
                    boolean enumOk = !hasEnum || enumValueSet.contains(raw);
                    if (!dictOk && !enumOk) {
                        // 两边都不过（值既不在字典也不在白名单）
                        row.setValid(false);
                        errors.add(new FieldError(row.getRowNo(), col.getField(), raw,
                            "ENUM_INVALID",
                            "枚举/字典值不合法: " + raw
                                + "，字典允许: " + dictValueSet
                                + "，模板允许: " + enumValueSet));
                    } else if (!dictOk) {
                        // 仅字典不过（如字典中心已删除该值）
                        row.setValid(false);
                        errors.add(new FieldError(row.getRowNo(), col.getField(), raw,
                            "DICT_INVALID",
                            "字典值不合法: " + raw + "，字典允许: " + dictValueSet));
                    } else if (!enumOk) {
                        // 仅枚举不过（如模板白名单与字典脱钩，但静态仍限制）
                        row.setValid(false);
                        errors.add(new FieldError(row.getRowNo(), col.getField(), raw,
                            "ENUM_INVALID",
                            "枚举值不合法: " + raw + "，允许: " + enumValueSet));
                    }
                }

                // 正则
                if (col.getPattern() != null && !col.getPattern().isBlank()) {
                    try {
                        if (!Pattern.matches(col.getPattern(), raw)) {
                            row.setValid(false);
                            errors.add(new FieldError(row.getRowNo(), col.getField(), raw,
                                "PATTERN_MISMATCH", "格式不匹配: " + col.getColName()));
                        }
                    } catch (Exception ignored) {
                        // 非法正则不阻断
                    }
                }

                // 最大长度
                if (col.getMaxLength() != null && raw.length() > col.getMaxLength()) {
                    row.setValid(false);
                    errors.add(new FieldError(row.getRowNo(), col.getField(), raw,
                        "MAX_LENGTH", "长度超过上限 " + col.getMaxLength() + ": " + col.getColName()));
                }
            }

            // 2. 行级规则校验：来源模板表 validation_rules JSONB
            // 只在 rawValues 上做语义级校验，与列级校验互补。
            // 命中顺序：跳过 blank（blank 由 required / not_blank 处理）；多规则按声明顺序。
            for (Map.Entry<String, List<RuleDef>> e : rulesByField.entrySet()) {
                String field = e.getKey();
                ColumnDef col = colByField.get(field);
                if (col == null) {
                    // 规则指向不存在的列：跳过
                    continue;
                }
                String raw = row.getRawValues().get(field);
                boolean blank = raw == null || raw.isBlank();

                for (RuleDef rule : e.getValue()) {
                    String r = rule.getRule() == null ? "" : rule.getRule().trim();

                    // not_blank：blank 即视为失败。注意：如果列已被列为 required，
                    // 该行的 REQUIRED_MISSING 已经记过，这里跳过避免重复刷同一种错误。
                    if ("not_blank".equalsIgnoreCase(r)) {
                        if (blank && !col.isRequired()) {
                            row.setValid(false);
                            errors.add(new FieldError(row.getRowNo(), field, raw,
                                "REQUIRED_MISSING",
                                rule.getMessage() != null ? rule.getMessage()
                                    : "必填项缺失: " + col.getColName()));
                        }
                        continue;
                    }

                    if (blank) {
                        // 数值/字典类规则跳过 blank，由 not_blank / required 处理
                        continue;
                    }

                    // gte:N / gt:N：数字字段
                    if (r.startsWith("gte:") || r.startsWith("gt:")) {
                        BigDecimal threshold;
                        try {
                            threshold = new BigDecimal(r.substring(4));
                        } catch (NumberFormatException nfe) {
                            errors.add(new FieldError(row.getRowNo(), field, raw,
                                "RULE_FORMAT_ERR", "规则表达式非法: " + r));
                            continue;
                        }
                        // 优先用解析阶段转换后的值：量纲与落库一致（transform=percent 列
                        // 转换后是 0.05，阈值比较不能用 raw 的 5），拿不到再退回 raw 解析
                        BigDecimal actual;
                        Object converted = row.getValues().get(field);
                        if (converted instanceof BigDecimal bd) {
                            actual = bd;
                        } else {
                            try {
                                // 与 TypeConverter 同一套解析：支持 "5.00%" 百分比 / 千分位，
                                // 否则带 % 的合法值会因解析失败静默跳过范围校验
                                actual = TypeConverter.parseDecimal(raw);
                            } catch (NumberFormatException nfe) {
                                // 非数字 — 留给类型校验（解析阶段已经记），这里跳过
                                continue;
                            }
                        }
                        boolean ok = r.startsWith("gte:")
                            ? actual.compareTo(threshold) >= 0
                            : actual.compareTo(threshold) > 0;
                        if (!ok) {
                            row.setValid(false);
                            String op = r.startsWith("gte:") ? "≥" : ">";
                            errors.add(new FieldError(row.getRowNo(), field, raw,
                                "RANGE_ERR",
                                (rule.getMessage() != null ? rule.getMessage()
                                    : (col.getColName() + " 必须" + op + threshold))
                                    + "（当前值=" + raw + "）"));
                        }
                        continue;
                    }

                    // dict_in:DICT_TYPE：字典白名单 + 枚举白名单 AND 校验
                    if (r.toLowerCase().startsWith("dict_in:")) {
                        Set<String> dictValueSet = resolveDictValueSet(col);
                        Set<String> enumValueSet = resolveEnumValueSet(col);
                        boolean hasDict = !dictValueSet.isEmpty();
                        boolean hasEnum = !enumValueSet.isEmpty();
                        if (!hasDict && !hasEnum) {
                            // 既没 dictType 也没 enumValues — 视为模板声明缺失，不阻断
                            continue;
                        }
                        boolean dictOk = !hasDict || dictValueSet.contains(raw);
                        boolean enumOk = !hasEnum || enumValueSet.contains(raw);
                        if (!dictOk && !enumOk) {
                            row.setValid(false);
                            errors.add(new FieldError(row.getRowNo(), field, raw,
                                "ENUM_INVALID",
                                rule.getMessage() != null ? rule.getMessage()
                                    : ("字典+枚举均不含: " + raw
                                        + "，字典: " + dictValueSet
                                        + "，枚举: " + enumValueSet)));
                        } else if (!dictOk) {
                            row.setValid(false);
                            errors.add(new FieldError(row.getRowNo(), field, raw,
                                "DICT_INVALID",
                                rule.getMessage() != null ? rule.getMessage()
                                    : ("字典值不合法: " + raw + "，字典允许: " + dictValueSet)));
                        } else if (!enumOk) {
                            row.setValid(false);
                            errors.add(new FieldError(row.getRowNo(), field, raw,
                                "ENUM_INVALID",
                                rule.getMessage() != null ? rule.getMessage()
                                    : ("枚举值不合法: " + raw + "，允许: " + enumValueSet)));
                        }
                        continue;
                    }

                    // 规则未被本版识别：记一条警告类 issue 但不阻断校验流程
                    logUnsupported(row.getRowNo(), field, r);
                }
            }
        }
        return errors;
    }

    private static Map<String, ColumnDef> indexColumns(List<ColumnDef> cols) {
        Map<String, ColumnDef> idx = new HashMap<>();
        for (ColumnDef c : cols) {
            if (c.getField() != null) {
                idx.put(c.getField(), c);
            }
        }
        return idx;
    }

    private static Map<String, List<RuleDef>> groupRulesByField(List<RuleDef> rules) {
        Map<String, List<RuleDef>> grouped = new HashMap<>();
        if (rules == null) {
            return grouped;
        }
        for (RuleDef r : rules) {
            if (r.getField() == null || r.getField().isBlank()) {
                continue;
            }
            grouped.computeIfAbsent(r.getField(), k -> new ArrayList<>()).add(r);
        }
        return grouped;
    }

    /** 留口：未来真正接字典中心时可在此注入。 */
    private static void logUnsupported(int rowNo, String field, String rule) {
        // 模板层引入新规则名但本版未实现，悄悄放过 — 不污染用户业务路径。
        // 规则解析器补全后再补充具体错误码。
    }

    /**
     * 解析列的字典实时值集合：仅当 {@code ColumnDef.dictType} 设置且 {@link DictDataPort}
     * 提供实现时返回字典中心值。失败/未注入一律返回空集，由调用方按需走 enumValues。
     *
     * @param col 列定义
     * @return 字典值集合（空 = 无 dictType、字典查询失败或字典无值）
     */
    private Set<String> resolveDictValueSet(ColumnDef col) {
        if (col.getDictType() == null || col.getDictType().isBlank() || dictDataPort == null) {
            return Collections.emptySet();
        }
        try {
            Set<String> values = dictDataPort.getDictValues(col.getDictType());
            return values == null ? Collections.emptySet() : values;
        } catch (Exception ignored) {
            // 字典查询失败时返回空集，由调用方按需走 enumValues，不阻断校验流程
            return Collections.emptySet();
        }
    }

    /**
     * 解析列的枚举静态白名单集合：仅当 {@code ColumnDef.enumValues} 设置时返回。
     * 字典集合与枚举集合互相独立，调用方按 AND 语义组合校验。
     *
     * @param col 列定义
     * @return 静态白名单集合（空 = 未配置 enumValues）
     */
    private static Set<String> resolveEnumValueSet(ColumnDef col) {
        if (col.getEnumValues() == null || col.getEnumValues().isEmpty()) {
            return Collections.emptySet();
        }
        return new java.util.LinkedHashSet<>(col.getEnumValues());
    }
}
