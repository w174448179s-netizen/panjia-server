package com.panjia.performance.service;

import com.panjia.performance.dto.FactQuery;
import com.panjia.performance.dto.PerformanceFactDTO;
import com.panjia.performance.dto.PerformanceManageContractVO;
import com.panjia.performance.dto.PerformanceManageDTO;
import com.panjia.performance.dto.PerformanceManageEmployeeVO;
import com.panjia.performance.dto.PerformanceManagePageVO;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.math.BigDecimal;
import java.util.List;

/**
 * 业绩查询服务。
 * <p>
 * 提供业绩事实的分页查询、详情查询、汇总统计、期间封账判断等只读能力。
 */
public interface PerformanceQueryService {

    /**
     * 分页查询业绩事实列表。
     * <p>
     * 支持按期间、事实口径、员工ID、部门ID、业务类型、状态、来源等条件筛选。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 业绩事实分页结果
     */
    PageResult<PerformanceFactDTO> listFacts(FactQuery query, PageQuery pageQuery);

    /**
     * 根据 ID 查询业绩事实详情。
     *
     * @param id 事实 ID
     * @return 业绩事实详情；不存在时返回 null
     */
    PerformanceFactDTO getFact(Long id);

    /**
     * 汇总业绩金额。
     * <p>
     * 参数均为可选，{@code employeeId}/{@code deptId} 可以为 null，
     * 为 null 时表示不限制该维度。
     *
     * @param period     归属期间（可选）
     * @param factType   事实口径（可选）
     * @param employeeId 员工 ID（可选）
     * @param deptId     部门 ID（可选）
     * @return 业绩金额汇总
     */
    BigDecimal sumPerformance(String period, String factType, Long employeeId, Long deptId);

    /**
     * 按员工 + 期间查询业绩明细。
     * <p>
     * 供 {@code CommissionPerformanceQueryPort} 等下游域调用。
     *
     * @param employeeId 员工 ID
     * @param period     归属期间
     * @param factType   事实口径
     * @return 业绩事实列表
     */
    List<PerformanceFactDTO> listByEmployeeAndPeriod(Long employeeId, String period, String factType);

    /**
     * 查询期间是否已封账。
     *
     * @param period 期间（YYYY-MM）
     * @return true 表示已封账
     */
    boolean isPeriodClosed(String period);

    /**
     * 业绩管理人维度分页查询（懒加载树表）。
     * <p>
     * 后端按员工分页，只返回当前页签约人的聚合行（每人一行：金额合计/合同数/明细数），
     * 人下合同与明细通过 {@link #listManageDetails} 按员工懒加载。
     * factType 决定金额口径：PERF_REAL=结佣业绩（当月实收）/ PERF_EXPECT=新签业绩（当月应收）。
     *
     * @param period   归属期间（必填）
     * @param factType 事实口径（必填）
     * @param deptId   部门 ID（可选，含子部门）
     * @param bizType  业务类型（可选）
     * @param settled  是否已结算（可选；null=全部）
     * @param keyword  关键字（可选：员工号/姓名/合同号/订单号/房源地址/角色/门店/店组）
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页人数
     * @return 人维度分页结果（人聚合行 + 总人数 + 业务类型集合 + 全局汇总）
     */
    PerformanceManagePageVO<PerformanceManageEmployeeVO> pageManage(String period, String factType, Long deptId,
                                       String bizType, Boolean settled, String keyword,
                                       Integer pageNum, Integer pageSize);

    /**
     * 按员工 ID 集合查询业绩管理明细（树表懒加载数据源）。
     * <p>
     * 过滤条件与 {@link #pageManage} 完全一致，保证展开明细的合计与人行聚合金额吻合。
     * 单个员工展开时集合长度为 1，「全部展开」时传当前页全部员工 ID。
     *
     * @param period      归属期间（必填）
     * @param factType    事实口径（必填）
     * @param deptId      部门 ID（可选，含子部门）
     * @param bizType     业务类型（可选）
     * @param settled     是否已结算（可选；null=全部）
     * @param keyword     关键字（可选）
     * @param employeeIds 员工 ID 集合（不能为空）
     * @return 员工业绩明细行（按姓名/合同号/日期/角色排序）
     */
    List<PerformanceManageDTO> listManageDetails(String period, String factType, Long deptId,
                                                 String bizType, Boolean settled, String keyword,
                                                 List<Long> employeeIds);

    /**
     * 业绩管理合同维度分页查询（懒加载树表：合同 → 人 → 明细）。
     * <p>
     * 后端按合同号分页，只返回当前页合同的聚合行（每合同一行：合同号/订单号/类型/
     * 房源地址/签约日期/合同金额/涉及人数/明细数），合同下签约人明细通过
     * {@link #listManageDetailsByContractNos} 按合同号懒加载。
     *
     * @return 合同维度分页结果（合同聚合行 + 总合同数 + 业务类型集合 + 全局汇总）
     */
    PerformanceManagePageVO<PerformanceManageContractVO> pageManageByContract(String period, String factType,
                                       Long deptId, String bizType, Boolean settled, String keyword,
                                       Integer pageNum, Integer pageSize);

    /**
     * 按合同号集合查询业绩管理明细（合同维度树表懒加载数据源）。
     * <p>
     * 过滤条件与 {@link #pageManageByContract} 一致，返回这些合同下所有签约人的明细行，
     * 前端按「合同 → 人 → 明细」组装树。
     *
     * @param contractNos 合同号集合（不能为空）
     * @return 业绩明细行（按合同号/姓名/日期/角色排序）
     */
    List<PerformanceManageDTO> listManageDetailsByContractNos(String period, String factType, Long deptId,
                                                 String bizType, Boolean settled, String keyword,
                                                 List<String> contractNos);

    /**
     * 查询有 ACTIVE 业绩事实的期间（倒序），供前端默认选中最新数据期间。
     *
     * @return 期间列表（YYYY-MM）
     */
    List<String> listManagePeriods();
}
