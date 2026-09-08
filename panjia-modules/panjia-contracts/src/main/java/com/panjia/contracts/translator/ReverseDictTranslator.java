package com.panjia.contracts.translator;

/**
 * 反向字典翻译接口。
 * <p>
 * 解决 RuoYi DictUtils.getDictLabel 的方向陷阱：
 * getDictLabel 是 value→label（正向），而 Excel 导入需要 label→value（反向）。
 * <p>
 * 实现规约（本 Task 只定义，实现在 Task-2-3）：
 * 1. 必须复用 RuoYi 字典内存缓存，禁止每行查 DB；
 * 2. 字典更新时清缓存（监听 RuoYi 字典修改事件）；
 * 3. 找不到抛 DICT_TRANSLATE_FAILED（行级错误，不终止整批）。
 */
public interface ReverseDictTranslator {

    /**
     * Excel 单元格 label（中文文本）→ 系统 dict_value。
     *
     * @param dictType 字典类型（如 panjia_biz_type）
     * @param label   Excel 表头或单元格中文文本
     * @return 系统 dict_value
     * @throws org.dromara.common.core.exception.ServiceException 找不到时抛 DICT_TRANSLATE_FAILED（code=1003）
     */
    String labelToValue(String dictType, String label);
}
