package com.panjia.people.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.panjia.people.domain.ContractParty;
import com.panjia.people.mapper.ContractPartyMapper;
import com.panjia.people.port.DeptPort;
import com.panjia.people.service.ContractPartyService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 合同甲方（用人单位）配置服务实现。
 */
@Service
@RequiredArgsConstructor
public class ContractPartyServiceImpl implements ContractPartyService {

    /** 默认甲方的部门 ID 约定值 */
    private static final long DEFAULT_DEPT_ID = 0L;

    private final ContractPartyMapper contractPartyMapper;
    private final DeptPort deptPort;

    @Override
    public List<Map<String, Object>> listAll() {
        List<ContractParty> parties = contractPartyMapper.selectList(
            new LambdaQueryWrapper<ContractParty>().orderByAsc(ContractParty::getDeptId));
        Set<Long> deptIds = parties.stream()
            .map(ContractParty::getDeptId).filter(id -> id != null && id > 0)
            .collect(Collectors.toSet());
        Map<Long, String> deptNames = deptPort.findDeptFullNames(deptIds);

        List<Map<String, Object>> rows = new ArrayList<>(parties.size());
        for (ContractParty p : parties) {
            Map<String, Object> row = new HashMap<>();
            row.put("partyId", p.getPartyId());
            row.put("deptId", p.getDeptId());
            row.put("deptName", p.getDeptId() != null && p.getDeptId() == 0L
                ? "默认甲方（全部部门）" : deptNames.getOrDefault(p.getDeptId(), "部门已删除"));
            row.put("companyName", p.getCompanyName());
            row.put("regAddress", p.getRegAddress());
            row.put("legalPerson", p.getLegalPerson());
            row.put("remark", p.getRemark());
            rows.add(row);
        }
        return rows;
    }

    @Override
    public void saveParty(ContractParty party, Long userId) {
        if (!StringUtils.hasText(party.getCompanyName())) {
            throw new ServiceException("单位（甲方）名称不能为空");
        }
        long deptId = party.getDeptId() == null ? DEFAULT_DEPT_ID : party.getDeptId();
        // 同一部门仅一条映射
        ContractParty exist = contractPartyMapper.selectOne(
            new LambdaQueryWrapper<ContractParty>().eq(ContractParty::getDeptId, deptId).last("LIMIT 1"));
        if (exist != null && (party.getPartyId() == null || !exist.getPartyId().equals(party.getPartyId()))) {
            throw new ServiceException(deptId == DEFAULT_DEPT_ID ? "默认甲方已存在，请直接编辑" : "该部门已配置甲方映射，请直接编辑");
        }
        party.setDeptId(deptId);
        LocalDateTime now = LocalDateTime.now();
        if (party.getPartyId() == null) {
            party.setCreateBy(userId);
            party.setCreateTime(now);
            contractPartyMapper.insert(party);
        } else {
            party.setUpdateBy(userId);
            party.setUpdateTime(now);
            contractPartyMapper.updateById(party);
        }
    }

    @Override
    public void deleteParty(Long partyId) {
        ContractParty party = contractPartyMapper.selectById(partyId);
        if (party == null) {
            return;
        }
        if (party.getDeptId() != null && party.getDeptId() == 0L) {
            throw new ServiceException("默认甲方不可删除，只能编辑");
        }
        contractPartyMapper.deleteById(partyId);
    }

    @Override
    public ContractParty resolveByDept(Long deptId) {
        // 1) 部门精确匹配
        if (deptId != null) {
            ContractParty exact = contractPartyMapper.selectOne(
                new LambdaQueryWrapper<ContractParty>().eq(ContractParty::getDeptId, deptId).last("LIMIT 1"));
            if (exact != null) {
                return exact;
            }
            // 2) 门店锚点匹配（映射配在门店级时，组别员工也能命中）
            Long storeAnchor = deptPort.findStoreAnchors(List.of(deptId)).get(deptId);
            if (storeAnchor != null && !storeAnchor.equals(deptId)) {
                ContractParty byStore = contractPartyMapper.selectOne(
                    new LambdaQueryWrapper<ContractParty>().eq(ContractParty::getDeptId, storeAnchor).last("LIMIT 1"));
                if (byStore != null) {
                    return byStore;
                }
            }
        }
        // 3) 默认甲方兜底
        return contractPartyMapper.selectOne(
            new LambdaQueryWrapper<ContractParty>().eq(ContractParty::getDeptId, DEFAULT_DEPT_ID).last("LIMIT 1"));
    }
}
