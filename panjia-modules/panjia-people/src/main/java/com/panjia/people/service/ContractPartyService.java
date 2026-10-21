package com.panjia.people.service;

import com.panjia.people.domain.ContractParty;

import java.util.List;
import java.util.Map;

/**
 * 合同甲方（用人单位）配置服务。
 */
public interface ContractPartyService {

    /**
     * 全量列表（管理弹窗用，含部门全路径名）。
     *
     * @return 甲方配置列表（默认甲方在前）
     */
    List<Map<String, Object>> listAll();

    /**
     * 新增/更新甲方配置（partyId 为空=新增）。
     *
     * @param party  配置内容
     * @param userId 操作人
     */
    void saveParty(ContractParty party, Long userId);

    /**
     * 删除甲方配置（默认甲方 dept_id=0 禁止删除）。
     *
     * @param partyId 主键
     */
    void deleteParty(Long partyId);

    /**
     * 按员工所属部门解析甲方：部门精确匹配 → 门店锚点匹配 → 默认甲方（dept_id=0）。
     *
     * @param deptId 员工部门 ID
     * @return 甲方配置；无任何配置返回 null
     */
    ContractParty resolveByDept(Long deptId);
}
