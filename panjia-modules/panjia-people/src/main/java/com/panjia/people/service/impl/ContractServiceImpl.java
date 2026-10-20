package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.panjia.people.domain.Contract;
import com.panjia.people.domain.Employee;
import com.panjia.people.dto.ContractCreateDTO;
import com.panjia.people.dto.ContractExpiringVO;
import com.panjia.people.dto.ContractQuery;
import com.panjia.people.dto.ContractUpdateDTO;
import com.panjia.people.dto.ContractVO;
import com.panjia.people.mapper.ContractMapper;
import com.panjia.people.mapper.EmployeeMapper;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.ContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 员工合同服务实现。
 * <p>
 * 合同列表/到期名单的员工与门店信息均入口批量取数，不循环查库；
 * 扫描件元数据走 ruoyi-system 的 OSS 服务（url 为存储直链）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractServiceImpl implements ContractService {

    /** 到期提醒提前天数 */
    private static final int EXPIRE_REMIND_DAYS = 30;
    /** 合同类型合法性校验值域 */
    private static final Set<String> CONTRACT_TYPES = Set.of("LABOR", "PARTTIME", "OTHER");

    private final ContractMapper contractMapper;
    private final EmployeeMapper employeeMapper;
    private final DeptPort deptPort;
    private final ISysOssService ossService;

    // ==================== 查询 ====================

    @Override
    public PageResult<ContractVO> pageList(ContractQuery query, PageQuery pageQuery) {
        LambdaQueryWrapper<Contract> wrapper = new LambdaQueryWrapper<Contract>()
            .eq(query.getEmployeeId() != null, Contract::getEmployeeId, query.getEmployeeId())
            .eq(StringUtils.isNotBlank(query.getContractType()), Contract::getContractType, query.getContractType())
            .orderByDesc(Contract::getStartDate)
            .orderByDesc(Contract::getCreateTime);

        // 工号/姓名（模糊）→ 先解析员工 ID 集合
        List<Long> employeeIdFilter = resolveEmployeeIdsByKeyword(query.getEmployeeCode(), query.getEmployeeName());
        if (employeeIdFilter != null && employeeIdFilter.isEmpty()) {
            return PageResult.build(List.of(), 0L);
        }
        if (employeeIdFilter != null) {
            wrapper.in(Contract::getEmployeeId, employeeIdFilter);
        }
        // 门店（含下级）
        if (query.getDeptId() != null) {
            List<Long> deptIds = deptPort.findDeptAndChildIds(query.getDeptId());
            wrapper.in(Contract::getDeptId, deptIds);
        }

        Page<Contract> page = contractMapper.selectPage(pageQuery.build(), wrapper);
        List<ContractVO> vos = page.getRecords().stream().map(this::toVO).toList();
        enrich(vos);
        return PageResult.build(vos, page.getTotal());
    }

    /**
     * 工号/姓名关键字 → 员工 ID 集合。
     *
     * @return null=未传关键字（不筛选）；空集合=无匹配（短路返回空页）
     */
    private List<Long> resolveEmployeeIdsByKeyword(String employeeCode, String employeeName) {
        boolean hasCode = StringUtils.isNotBlank(employeeCode);
        boolean hasName = StringUtils.isNotBlank(employeeName);
        if (!hasCode && !hasName) {
            return null;
        }
        LambdaQueryWrapper<Employee> wrapper = new LambdaQueryWrapper<Employee>()
            .like(hasCode, Employee::getEmployeeCode, employeeCode)
            .like(hasName, Employee::getEmployeeName, employeeName)
            .select(Employee::getEmployeeId);
        return employeeMapper.selectList(wrapper).stream()
            .map(Employee::getEmployeeId)
            .toList();
    }

    // ==================== 新增 / 修改 / 删除 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createContract(ContractCreateDTO dto) {
        String type = dto.getContractType() == null ? "" : dto.getContractType().trim();
        if (!CONTRACT_TYPES.contains(type)) {
            throw new ServiceException("非法合同类型：{}", dto.getContractType());
        }
        if (employeeMapper.selectById(dto.getEmployeeId()) == null) {
            throw new ServiceException("员工不存在，employeeId={}", dto.getEmployeeId());
        }
        // 同员工同类型同开始日唯一（唯一索引兜底，此处先查给出友好提示）
        Long exists = contractMapper.selectCount(new LambdaQueryWrapper<Contract>()
            .eq(Contract::getEmployeeId, dto.getEmployeeId())
            .eq(Contract::getContractType, type)
            .eq(Contract::getStartDate, dto.getStartDate()));
        if (exists != null && exists > 0) {
            throw new ServiceException("该员工在 {} 已有同类型合同记录，续签请使用新的开始日期",
                dto.getStartDate());
        }
        Contract contract = new Contract();
        contract.setEmployeeId(dto.getEmployeeId());
        contract.setContractType(type);
        contract.setDeptId(dto.getDeptId());
        contract.setStartDate(dto.getStartDate());
        contract.setEndDate(dto.getEndDate());
        contract.setIdCardOssIds(joinOssIds(dto.getIdCardOssIds()));
        contract.setRemark(dto.getRemark());
        contractMapper.insert(contract);
        return contract.getContractId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateContract(Long contractId, ContractUpdateDTO dto) {
        Contract contract = contractMapper.selectById(contractId);
        if (contract == null) {
            throw new ServiceException("合同不存在，contractId={}", contractId);
        }
        if (StringUtils.isNotBlank(dto.getContractType())) {
            String type = dto.getContractType().trim();
            if (!CONTRACT_TYPES.contains(type)) {
                throw new ServiceException("非法合同类型：{}", dto.getContractType());
            }
            contract.setContractType(type);
        }
        if (dto.getDeptId() != null) {
            contract.setDeptId(dto.getDeptId());
        }
        if (dto.getStartDate() != null) {
            contract.setStartDate(dto.getStartDate());
        }
        if (dto.getEndDate() != null) {
            contract.setEndDate(dto.getEndDate());
        }
        if (dto.getIdCardOssIds() != null) {
            contract.setIdCardOssIds(joinOssIds(dto.getIdCardOssIds()));
        }
        if (dto.getRemark() != null) {
            contract.setRemark(dto.getRemark());
        }
        contractMapper.updateById(contract);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteContracts(List<Long> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return;
        }
        contractMapper.deleteByIds(contractIds);
    }

    // ==================== 到期提醒 ====================

    @Override
    public List<ContractExpiringVO> listExpiring() {
        LocalDate today = LocalDate.now();
        LocalDate remindEnd = today.plusDays(EXPIRE_REMIND_DAYS);
        // 只查有固定期限的合同（无固定期限不参与到期提醒）
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
            .isNotNull(Contract::getEndDate)
            .le(Contract::getEndDate, remindEnd));

        // 按 (employeeId, contractType) 取最新一条：start_date desc → create_time desc
        Map<String, Contract> latestByKey = new LinkedHashMap<>();
        for (Contract c : contracts) {
            String key = c.getEmployeeId() + "|" + c.getContractType();
            Contract current = latestByKey.get(key);
            if (current == null || isNewer(c, current)) {
                latestByKey.put(key, c);
            }
        }

        // 已到期未重签（end_date < 今天）+ 即将到期（[今天, 今天+30]）
        List<Contract> expiring = latestByKey.values().stream()
            .filter(c -> !c.getEndDate().isBefore(today))
            .toList();
        if (expiring.isEmpty()) {
            return List.of();
        }

        // 批量 enrich 员工与门店
        Set<Long> employeeIds = expiring.stream().map(Contract::getEmployeeId).collect(Collectors.toSet());
        Map<Long, Employee> employees = new LinkedHashMap<>();
        employeeMapper.selectByIds(employeeIds).forEach(e -> employees.put(e.getEmployeeId(), e));
        Set<Long> deptIds = expiring.stream().map(Contract::getDeptId).collect(Collectors.toSet());
        Map<Long, String> deptNames = deptPort.findDeptFullNames(deptIds);

        Comparator<ContractExpiringVO> ordering = Comparator
            .comparing((ContractExpiringVO v) -> !v.isExpired())       // 已到期在前
            .thenComparing(ContractExpiringVO::getEndDate);            // 其余按到期日升序
        return expiring.stream().map(c -> {
            ContractExpiringVO vo = new ContractExpiringVO();
            vo.setEmployeeId(c.getEmployeeId());
            vo.setContractType(c.getContractType());
            vo.setEndDate(c.getEndDate());
            vo.setExpired(c.getEndDate().isBefore(today));
            Employee emp = employees.get(c.getEmployeeId());
            if (emp != null) {
                vo.setEmployeeCode(emp.getEmployeeCode());
                vo.setEmployeeName(emp.getEmployeeName());
            }
            vo.setDeptName(deptNames.get(c.getDeptId()));
            return vo;
        }).sorted(ordering).toList();
    }

    /** 新记录判定：start_date 更晚视为更新；同日按 create_time 更晚 */
    private boolean isNewer(Contract candidate, Contract current) {
        int byStart = candidate.getStartDate().compareTo(current.getStartDate());
        if (byStart != 0) {
            return byStart > 0;
        }
        if (candidate.getCreateTime() != null && current.getCreateTime() != null) {
            return candidate.getCreateTime().isAfter(current.getCreateTime());
        }
        return false;
    }

    // ==================== 本人合同查询 ====================

    @Override
    public List<ContractVO> listMyContracts(Long employeeId) {
        if (employeeId == null) {
            return List.of();
        }
        List<Contract> list = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
            .eq(Contract::getEmployeeId, employeeId)
            .orderByDesc(Contract::getStartDate)
            .orderByDesc(Contract::getCreateTime));
        if (list.isEmpty()) {
            return List.of();
        }
        List<ContractVO> vos = list.stream().map(this::toVO).toList();
        enrich(vos);
        return vos;
    }

    // ==================== 内部方法 ====================

    private ContractVO toVO(Contract contract) {
        ContractVO vo = new ContractVO();
        vo.setContractId(contract.getContractId());
        vo.setEmployeeId(contract.getEmployeeId());
        vo.setDeptId(contract.getDeptId());
        vo.setContractType(contract.getContractType());
        vo.setStartDate(contract.getStartDate());
        vo.setEndDate(contract.getEndDate());
        vo.setRemark(contract.getRemark());
        vo.setCreateTime(contract.getCreateTime());
        vo.setIdCardOssIds(splitOssIds(contract.getIdCardOssIds()));
        return vo;
    }

    /**
     * 批量填充员工（工号/姓名）、门店名与扫描件 URL。
     */
    private void enrich(List<ContractVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        // 员工
        Set<Long> employeeIds = vos.stream().map(ContractVO::getEmployeeId).collect(Collectors.toSet());
        Map<Long, Employee> employees = new LinkedHashMap<>();
        employeeMapper.selectByIds(employeeIds).forEach(e -> employees.put(e.getEmployeeId(), e));
        vos.forEach(vo -> {
            Employee emp = employees.get(vo.getEmployeeId());
            if (emp != null) {
                vo.setEmployeeCode(emp.getEmployeeCode());
                vo.setEmployeeName(emp.getEmployeeName());
            }
        });
        // 门店
        Set<Long> deptIds = vos.stream().map(ContractVO::getDeptId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> deptNames = deptPort.findDeptFullNames(deptIds);
        vos.forEach(vo -> vo.setDeptName(deptNames.get(vo.getDeptId())));
        // 扫描件 URL（ossId → 直链；元数据已删的 ossId 跳过）
        List<Long> ossIds = vos.stream()
            .map(ContractVO::getIdCardOssIds)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .map(s -> {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        Map<Long, String> urlById = new LinkedHashMap<>();
        if (!ossIds.isEmpty()) {
            Collection<SysOssVo> ossList = ossService.listByIds(ossIds);
            for (SysOssVo oss : ossList) {
                urlById.put(oss.getOssId(), oss.getUrl());
            }
        }
        vos.forEach(vo -> {
            if (vo.getIdCardOssIds() == null) {
                return;
            }
            List<String> urls = new ArrayList<>(vo.getIdCardOssIds().size());
            for (String id : vo.getIdCardOssIds()) {
                try {
                    urls.add(urlById.get(Long.parseLong(id.trim())));
                } catch (NumberFormatException e) {
                    urls.add(null);
                }
            }
            vo.setIdCardUrls(urls);
        });
    }

    /** ossId 集合 → 逗号分隔串（去空白、去重、保持顺序；空集合 → null） */
    private String joinOssIds(List<String> ossIds) {
        if (ossIds == null) {
            return null;
        }
        String joined = ossIds.stream()
            .filter(StringUtils::isNotBlank)
            .map(String::trim)
            .distinct()
            .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /** 逗号分隔串 → ossId 集合（null/空 → 空集合） */
    private List<String> splitOssIds(String joined) {
        if (StringUtils.isBlank(joined)) {
            return List.of();
        }
        return StringUtils.splitList(joined, ",").stream()
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .toList();
    }
}
