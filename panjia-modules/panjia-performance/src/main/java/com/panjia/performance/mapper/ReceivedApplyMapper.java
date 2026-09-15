package com.panjia.performance.mapper;

import com.panjia.performance.domain.ReceivedApply;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;

/**
 * 实收业绩审批单 Mapper。
 */
@Mapper
public interface ReceivedApplyMapper extends BaseMapperPlus<ReceivedApply, ReceivedApply> {

    /**
     * 校验指定用户是否为某任务的待办办理人（查 flow_user 表）。
     * <p>用于驳回鉴权对齐 approve 路径的引擎原生鉴权：
     * approve 走 completeTask(completeTask) 由引擎按 flow_user 判权，
     * reject 走 rejectTask（系统身份 ignore=true）无引擎鉴权，故业务层补此校验。
     * <p>processed_by 在 flow_user 中以字符串存 user_id，故参数按字符串比较。
     *
     * @param taskId 任务 ID
     * @param userId 当前登录用户 ID
     * @return 命中条数（≥1 表示是该任务办理人）
     */
    @Select("SELECT COUNT(1) FROM flow_user WHERE associated = #{taskId} AND processed_by = #{userId} "
        + "AND type = '1' AND del_flag = '0'")
    int countFlowUserAssignment(@Param("taskId") Long taskId, @Param("userId") Long userId);
}
