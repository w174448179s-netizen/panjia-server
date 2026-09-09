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

    // ==================== 员工域（panjia-people）2xxx ====================

    /** 员工不存在 */
    public static final int EMPLOYEE_NOT_FOUND = 2001;

    /** 工号已存在（唯一约束冲突） */
    public static final int EMPLOYEE_CODE_DUPLICATE = 2002;

    /** 指定时点无有效职级（不降级，避免算薪口径错误） */
    public static final int NO_VALID_LEVEL = 2003;

    /** 不能自推荐（师徒关系 mentor_id = apprentice_id） */
    public static final int MENTOR_SELF_REFERENCE = 2004;

    /** 该员工已有有效师傅（同一徒弟只能有一个有效师傅） */
    public static final int MENTOR_ALREADY_EXISTS = 2005;

    /** 师傅推荐人数已达上限（5 人） */
    public static final int MENTOR_LIMIT_REACHED = 2006;

    private BizCode() {
        // 常量类，禁止实例化
    }
}
