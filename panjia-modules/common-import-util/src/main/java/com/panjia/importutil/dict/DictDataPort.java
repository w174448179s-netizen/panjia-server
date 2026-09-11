package com.panjia.importutil.dict;

/**
 * 字典数据查询端口（SPI）：把导入校验期的字典白名单校验从「模板里硬编码 enumValues」
 * 升级为「运行时查字典中心」。
 * <p>
 * 模板层配置替换方案：
 * <ul>
 *   <li>老配置 {@code enumValues:["A0","A1"...]}: 模板表带具体值，校验期硬比对</li>
 *   <li>新配置 {@code dictType:"panjia_employee_level"}: 模板只声明字典类型，
 *       校验期通过本端口查 sys_dict_data（或上游域的 dict cache）拿实时值集合</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li><b>是 SPI 而非具体实现</b>：common-import-util 不依赖任何 system 模块，
 *       各业务域（import / people / performance）按需实现</li>
 *   <li><b>失败返回空集合</b>：字典查不到不阻断校验流程，让上层业务决策（返回空集合
 *       意味着 dict_in 规则被降级跳过，由业务方改 issue 或回填 enumValues）</li>
 *   <li><b>缓存策略由实现方决定</b>：高频校验场景下实现方可用本地 cache，
 *       本接口不变；语义查询是「给定 dictType 应有哪些 dict_value」</li>
 * </ul>
 * <p>
 * 与 V2.0 §3.4 「shift-left」配套使用：业务硬约束（字典白名单）应该尽量前置到
 * 用户填写场景（Excel 模板层 DataValidation 下拉框）而不是服务端硬比对。
 */
public interface DictDataPort {

    /**
     * 查字典的 dict_value 集合。
     *
     * @param dictType 字典类型（如 {@code panjia_employee_level}）
     * @return 该字典下所有启用项的 dict_value 集合（未启用项过滤）；
     *         查不到时返回空集合而非 null
     */
    java.util.Set<String> getDictValues(String dictType);
}
