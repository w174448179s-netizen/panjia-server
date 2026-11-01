package com.panjia.contracts.port;

import com.panjia.contracts.dto.ImUserMappingSyncResult;
import com.panjia.contracts.dto.ImUserSyncDTO;

import java.util.List;

/**
 * IM 账号映射同步端口（钉钉/企微等渠道模块 → people 员工域）。
 * <p>
 * 渠道模块只负责从开放平台拉人员并转换成 {@link ImUserSyncDTO}；
 * 员工域负责按工号匹配本地员工、回填登录账号并 upsert 到
 * {@code pj_people_im_mapping}，保证多渠道写入口径一致。
 * <p>
 * 幂等：同一 channel + corpId + externalUserId 重复同步只更新，不新增。
 */
public interface ImUserMappingSyncPort {

    /**
     * 同步一批渠道账号到映射表。
     *
     * @param channel 渠道标识（DINGTALK / WECOM / FEISHU）
     * @param corpId  企业标识（钉钉 corpId / 企微 corpid；单企业传空串）
     * @param users   渠道通讯录人员列表
     * @return 同步统计（总数/匹配/未匹配/新增/更新）
     */
    ImUserMappingSyncResult sync(String channel, String corpId, List<ImUserSyncDTO> users);
}
