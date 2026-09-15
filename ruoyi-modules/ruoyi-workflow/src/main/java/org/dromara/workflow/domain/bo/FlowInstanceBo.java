package org.dromara.workflow.domain.bo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 流程实例查询条件对象。
 *
 * @author may
 */
@Data
public class FlowInstanceBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 流程定义名称
     */
    private String flowName;

    /**
     * 流程定义编码
     */
    private String flowCode;

    /**
     * 任务发起人
     */
    private String startUserId;

    /**
     * 业务id
     */
    private String businessId;

    /**
     * 流程分类id
     */
    private String category;

    /**
     * 任务名称
     */
    private String nodeName;

    /**
     * 申请人Ids
     */
    private List<String> createByIds;

    /**
     * 业务标题关键字（模糊匹配 flow_instance_biz_ext.business_title，
     * 覆盖合同号/房源地址/账期/金额等业务摘要）
     */
    private String businessTitle;

}
