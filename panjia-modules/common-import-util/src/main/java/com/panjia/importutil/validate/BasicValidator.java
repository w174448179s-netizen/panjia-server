package com.panjia.importutil.validate;

import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.template.model.ImportTemplate;

import java.util.List;

/**
 * 基础格式校验 SPI（必填 / 枚举 / 正则 / 长度）。
 * <p>
 * 纯技术校验，不碰业务库；业务校验（唯一性/外键/枚举业务态）由业务域自己做。
 */
public interface BasicValidator {

    /**
     * 对解析结果做基础格式校验。
     *
     * @param sheet    解析结果
     * @param template 模板定义
     * @return 校验错误列表（不抛异常，空列表表示全部通过）
     */
    List<FieldError> validate(ParsedSheet sheet, ImportTemplate template);
}
