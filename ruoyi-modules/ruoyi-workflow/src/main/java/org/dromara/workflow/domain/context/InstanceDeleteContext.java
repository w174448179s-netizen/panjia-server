package org.dromara.workflow.domain.context;

import lombok.Data;
import org.dromara.warm.flow.orm.entity.FlowInstance;

import java.util.Collection;
import java.util.List;

/**
 * 删除流程实例 LiteFlow 上下文。
 *
 * @author may
 */
@Data
public class InstanceDeleteContext {

    /**
     * 按业务 id 删除时的业务 id 集合。
     */
    private final List<String> businessIds;

    /**
     * 按实例 id 删除时的实例 id 集合。
     */
    private final Collection<Long> instanceIds;

    /**
     * 是否删除历史实例及其历史任务数据。
     */
    private final boolean history;

    /**
     * 待删除流程实例。
     */
    private List<FlowInstance> flowInstances;

    /**
     * 系统级删除：跳过登录用户权限校验（无用户上下文的系统操作，如事件消费、批量撤销）。
     */
    private boolean sysDelete;

    /**
     * 实际执行删除的实例 id。
     */
    private List<Long> deleteInstanceIds;

    /**
     * 删除结果。
     */
    private boolean result;

    public static InstanceDeleteContext byBusinessIds(List<String> businessIds) {
        return new InstanceDeleteContext(businessIds, null, false);
    }

    /**
     * 系统级按业务 id 删除：与 {@link #byBusinessIds(List)} 同链路，仅跳过权限校验。
     */
    public static InstanceDeleteContext byBusinessIdsSys(List<String> businessIds) {
        InstanceDeleteContext context = byBusinessIds(businessIds);
        context.setSysDelete(true);
        return context;
    }

    public static InstanceDeleteContext byInstanceIds(Collection<Long> instanceIds) {
        return new InstanceDeleteContext(null, instanceIds, false);
    }

    public static InstanceDeleteContext byHistoryInstanceIds(Collection<Long> instanceIds) {
        return new InstanceDeleteContext(null, instanceIds, true);
    }

}
