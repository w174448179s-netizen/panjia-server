package com.panjia.people.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 员工域配置。
 * <p>
 * 对应 application.yml：
 * <pre>
 * panjia:
 *   tenant:
 *     root-dept-id: 1761000000000000100   # 客户根部门 ID（岗位挂该节点下，部门建树的父节点）
 * </pre>
 */
@Data
@Component
@ConfigurationProperties(prefix = "panjia.tenant")
public class PeopleProperties {

    /**
     * 客户根部门 ID。
     * <p>
     * sys_post 岗位挂在此节点下；导入自动建门店/组别时以此为父节点。
     * 默认 1 仅作兜底，实际由初始化脚本对齐（盘家为 1761000000000000100）。
     */
    private Long rootDeptId = 1L;
}
