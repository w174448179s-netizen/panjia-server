package com.panjia.common.constant;

/**
 * 盘家智管自定义字段翻译类型常量。
 * <p>
 * 配合 ruoyi-common-translation 的 {@code @Translation} 注解使用：
 * 常量值必须与实现类上的 {@code @TranslationType(type = ...)} 严格一致，
 * 否则翻译静默失效（处理器按 type 字符串精确匹配）。
 * <p>
 * 实现类必须注册为 Spring Bean（panjia 的 {@code PanjiaAutoConfiguration}
 * 已组件扫描 com.panjia 全包），框架的 TranslationConfig 会自动收集全部
 * {@code TranslationInterface} 实现并按 @TranslationType 分发。
 * <p>
 * 注意：RuoYi 自带的 {@code user_id_to_nickname} 翻译的是 sys_user 用户 ID，
 * 业务表里的 employee_id 是员工档案表（pj_people_employee）的雪花 ID，
 * 两者不是同一 ID 体系，不能用错。
 */
public interface PanjiaTransConstant {

    /** 员工档案 ID → 员工姓名（实现见 panjia-people 的 EmployeeNameTranslationImpl） */
    String EMPLOYEE_ID_TO_NAME = "panjia_employee_id_to_name";

    /** 员工档案 ID → 工号（employeeCode） */
    String EMPLOYEE_ID_TO_CODE = "panjia_employee_id_to_code";
}
