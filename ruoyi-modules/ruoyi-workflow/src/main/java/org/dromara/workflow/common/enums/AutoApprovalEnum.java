package org.dromara.workflow.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 超时自动审批配置枚举（输入框类型，无固定选项）。
 * <p>
 * ext JSON 中 code 为 {@code "AutoApproval"}（非枚举 simpleName），
 * value 格式 {@code hours=72,skipType=PASS} 或简写 {@code 72}。
 *
 * @author panjia
 */
@Getter
@AllArgsConstructor
public enum AutoApprovalEnum implements NodeExtEnum {
    ;

    private final String label;
    private final String value;
    private final boolean selected;
}
