package com.panjia.performance.service;

import com.panjia.contracts.dto.EmployeeMainDataDTO;
import com.panjia.performance.domain.bo.PerformanceFactBo;
import com.panjia.performance.domain.bo.PerformanceManageContractDetailBo;
import com.panjia.performance.domain.bo.PerformanceManageContractBo;
import com.panjia.performance.domain.vo.PerformanceFactVo;
import com.panjia.performance.domain.vo.PerformanceFactSearchVo;
import com.panjia.performance.domain.vo.PerformanceManageContractVo;
import com.panjia.performance.domain.vo.PerformanceManageVo;
import com.panjia.performance.domain.vo.PerformanceManagePageVo;
import com.panjia.performance.domain.vo.PerformanceSearchDetailVo;
import com.panjia.performance.domain.bo.PerformanceSearchBo;
import com.panjia.performance.domain.bo.PerformanceSearchBizTypesBo;
import com.panjia.performance.domain.bo.PerformanceSearchEmployeeOptionsBo;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 业绩查询服务。
 * <p>
 * 提供业绩事实的分页查询、详情查询、汇总统计、期间封账判断等只读能力。
 */
public interface IPerformanceQueryService {

    /**
     * 分页查询业绩事实列表。
     */
    PageResult<PerformanceFactVo> listFacts(PerformanceFactBo query, PageQuery pageQuery);

    /**
     * 按员工 + 期间查询业绩明细（供下游域调用）。
     */
    List<PerformanceFactVo> listByEmployeeAndPeriod(Long employeeId, String period, String factType);

    /**
     * 查询期间是否已封账。
     */
    boolean isPeriodClosed(String period);

    /**
     * 业绩管理合同维度分页查询（懒加载树表：合同 → 人 → 明细）。
     */
    PerformanceManagePageVo<PerformanceManageContractVo> pageManageByContract(PerformanceManageContractBo query, PageQuery pageQuery);

    /**
     * 按合同号集合查询业绩管理明细（合同维度树表懒加载数据源）。
     */
    List<PerformanceManageVo> listManageDetailsByContractNos(PerformanceManageContractDetailBo query);

    /**
     * 查询有 ACTIVE 业绩事实的期间（倒序）。
     */
    List<String> listManagePeriods();

    /**
     * 完整业绩查询（合同维度）。
     */
    PageResult<PerformanceFactSearchVo> searchByContract(PerformanceSearchBo query, PageQuery pageQuery);

    /**
     * 完整业绩查询的业务类型下拉选项。
     */
    List<String> searchBizTypes(PerformanceSearchBizTypesBo query);

    /**
     * 完整业绩查询·员工下拉选项（按姓名/工号远程搜索）。
     */
    List<EmployeeMainDataDTO> searchEmployeeOptions(PerformanceSearchEmployeeOptionsBo query);

    /**
     * 完整业绩查询·按业务键查询合同下明细（查看详情弹窗数据源）。
     */
    List<PerformanceSearchDetailVo> searchDetails(String bizNo);
}
