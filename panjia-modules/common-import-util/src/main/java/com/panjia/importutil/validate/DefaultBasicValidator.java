package com.panjia.importutil.validate;

import com.panjia.importutil.dto.ParsedRow;
import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.template.model.ColumnDef;
import com.panjia.importutil.template.model.ImportTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 默认基础格式校验器：必填 / 枚举白名单 / 正则 / 最大长度。
 * <p>
 * 类型错误已由解析阶段写入 {@link ParsedSheet#getErrors()}；
 * 本校验器对行内 rawValues 做语义级基础校验，失败同时把行标记为 invalid。
 */
@Component
public class DefaultBasicValidator implements BasicValidator {

    @Override
    public List<FieldError> validate(ParsedSheet sheet, ImportTemplate template) {
        List<FieldError> errors = new ArrayList<>(sheet.getErrors());
        List<ColumnDef> cols = template.getColumns();

        for (ParsedRow row : sheet.getRows()) {
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

                // 枚举白名单
                if (col.getEnumValues() != null && !col.getEnumValues().isEmpty()
                    && !col.getEnumValues().contains(raw)) {
                    row.setValid(false);
                    errors.add(new FieldError(row.getRowNo(), col.getField(), raw,
                        "ENUM_INVALID", "枚举值不合法: " + raw + "，允许: " + col.getEnumValues()));
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
        }
        return errors;
    }
}
