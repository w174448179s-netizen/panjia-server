package com.panjia.people.service;

import com.panjia.people.dto.ContractCreateDTO;
import com.panjia.people.dto.ContractExpiringVO;
import com.panjia.people.dto.ContractQuery;
import com.panjia.people.dto.ContractUpdateDTO;
import com.panjia.people.dto.ContractVO;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.mybatis.core.page.PageQuery;

import java.util.List;

/**
 * 员工合同服务（劳动合同/兼职合同/其他）。
 * <p>
 * 续签 = 新增记录，历史保留；到期提醒口径见 {@link #listExpiring()}。
 */
public interface ContractService {

    /**
     * 分页查询合同列表（筛选：员工/工号/姓名/类型/门店树）。
     *
     * @param query     筛选条件
     * @param pageQuery 分页参数
     * @return 合同视图分页
     */
    PageResult<ContractVO> pageList(ContractQuery query, PageQuery pageQuery);

    /**
     * 新增合同（同一员工同一类型同一开始日唯一）。
     *
     * @param dto 新增请求
     * @return 合同 ID
     */
    Long createContract(ContractCreateDTO dto);

    /**
     * 修改合同（错录修正；员工不允许改，换人走删除重录）。
     *
     * @param contractId 合同 ID
     * @param dto        修改请求
     */
    void updateContract(Long contractId, ContractUpdateDTO dto);

    /**
     * 删除合同（错录修正，按 ID 批量）。
     *
     * @param contractIds 合同 ID 集合
     */
    void deleteContracts(List<Long> contractIds);

    /**
     * 到期提醒名单：每员工每合同类型取最新一条（start_date 倒序，平局 create_time 倒序），
     * 其 end_date 落在 [今天, 今天+30天] 为即将到期（expired=false），
     * 早于今天为已到期未重签（expired=true）；无固定期限合同不参与。
     *
     * @return 到期名单（已到期在前，其余按到期日升序）
     */
    List<ContractExpiringVO> listExpiring();
}
