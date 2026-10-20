package com.panjia.contracts.port;

import com.panjia.contracts.dto.PerfSchemeDTO;

import java.util.List;

/**
 * 绩效方案查询端口：员工域指派绩效方案时读取方案清单（校验存在性与适用岗位）。
 * <p>
 * 薪酬域（panjia-payroll）基于方案配置表（pj_payroll_perf_scheme）实现，
 * 员工域（panjia-people）消费，避免方案清单跨模块直查。
 */
public interface PerfSchemeQueryPort {

    /**
     * 读取全部绩效方案（含未生效历史，调用方自行按需过滤）。
     *
     * @return 方案视图列表；配置表为空时返回空列表
     */
    List<PerfSchemeDTO> listSchemes();
}
