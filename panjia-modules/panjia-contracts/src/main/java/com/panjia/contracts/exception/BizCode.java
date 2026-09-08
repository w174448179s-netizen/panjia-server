package com.panjia.contracts.exception;

/**
 * 业务码常量类。
 * <p>
 * 复用 RuoYi ServiceException，本类只放业务码常量（禁止自建枚举体系）。
 * 使用方式：throw new ServiceException("导入模板未找到", BizCode.IMPORT_TEMPLATE_NOT_FOUND);
 */
public final class BizCode {

    /** 导入模板未找到（同时间窗口 0 条 active 模板） */
    public static final int IMPORT_TEMPLATE_NOT_FOUND = 1001;

    /** 导入模板匹配多条（同时间窗口多条 active 模板，违反唯一性约束） */
    public static final int IMPORT_TEMPLATE_MULTIPLE_MATCH = 1002;

    /** 字典翻译失败（Excel label 找不到对应 dict_value） */
    public static final int DICT_TRANSLATE_FAILED = 1003;

    private BizCode() {
        // 常量类，禁止实例化
    }
}
