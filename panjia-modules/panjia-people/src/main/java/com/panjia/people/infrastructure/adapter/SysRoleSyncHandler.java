package com.panjia.people.infrastructure.adapter;

import com.panjia.contracts.event.DomainEventHandler;
import com.panjia.contracts.event.EmployeeRoleChangedEvent;
import com.panjia.people.infrastructure.repository.RoleMappingMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 员工角色变更 → 系统角色联动处理器。
 * <p>
 * 消费 Outbox 事件 {@code people.role_changed}，通过映射表 pj_people_role_mapping
 * 查找新角色对应的 sys_role_id 列表，解绑旧角色、绑定新角色。
 * <p>
 * 五个关键约束：
 * <ol>
 *   <li>业务角色是"主"，权限是"从"——单一入口，只有人事变更能触发</li>
 *   <li>不直接写 sys_user_role——统一走 Outbox 事件</li>
 *   <li>映射表支持热改——不同客户角色体系不同，改配置不改代码</li>
 *   <li>无账号员工跳过同步——user_id = NULL 只更新 employee_role</li>
 *   <li>方向单向——底座改权限 ≠ 业务角色变化，防循环</li>
 * </ol>
 */
@Slf4j
@Component
public class SysRoleSyncHandler implements DomainEventHandler {

    @Autowired
    private RoleMappingMapper roleMappingMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public String eventType() {
        return "employee.role.changed";
    }

    /**
     * 处理角色变更事件：解绑旧 sys_role + 绑定新 sys_role。
     * <p>
     * 旧角色映射的 sys_role_id 中，若不在新角色映射列表中则解绑；
     * 新角色映射的 sys_role_id 中，若用户尚未绑定则插入。
     * <p>
     * 幂等保证：Dispatcher 在调用前已做 pj_outbox_idempotent 幂等检查。
     *
     * @param eventId     outbox 事件唯一 ID（日志追踪）
     * @param payloadJson 事件 payload（EmployeeRoleChangedEvent 的 JSON）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(String eventId, String payloadJson) {
        EmployeeRoleChangedEvent payload = deserializePayload(payloadJson);
        if (payload == null) {
            log.warn("角色变更事件 payload 反序列化失败: eventId={}", eventId);
            return;
        }

        // 约束4：无账号员工跳过同步
        if (payload.getUserId() == null) {
            log.info("员工无登录账号，跳过系统角色同步: employeeId={}", payload.getEmployeeId());
            return;
        }

        Long userId = payload.getUserId();
        List<Long> oldSysRoleIds = roleMappingMapper.selectSysRoleIdsByEmployeeRole(payload.getOldRole());
        List<Long> newSysRoleIds = roleMappingMapper.selectSysRoleIdsByEmployeeRole(payload.getNewRole());

        // 解绑旧角色（在新角色列表中不存在的 sys_role_id 才删除）
        for (Long oldRoleId : oldSysRoleIds) {
            if (!newSysRoleIds.contains(oldRoleId)) {
                sysUserRoleMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysUserRole::getRoleId, oldRoleId));
                log.info("解绑系统角色: userId={}, roleId={}", userId, oldRoleId);
            }
        }

        // 绑定新角色（用户尚未绑定的 sys_role_id 才插入）
        for (Long newRoleId : newSysRoleIds) {
            boolean alreadyBound = sysUserRoleMapper.exists(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserRole>()
                    .eq(SysUserRole::getUserId, userId)
                    .eq(SysUserRole::getRoleId, newRoleId));
            if (!alreadyBound) {
                SysUserRole userRole = new SysUserRole();
                userRole.setUserId(userId);
                userRole.setRoleId(newRoleId);
                sysUserRoleMapper.insert(userRole);
                log.info("绑定系统角色: userId={}, roleId={}", userId, newRoleId);
            }
        }

        log.info("角色联动完成: employeeId={}, {} → {}, sysRoles={}",
            payload.getEmployeeId(), payload.getOldRole(), payload.getNewRole(), newSysRoleIds);
    }

    /**
     * 反序列化 outbox payload 为 EmployeeRoleChangedEvent。
     *
     * @param payloadJson JSON 字符串
     * @return 事件对象，失败返回 null
     */
    private EmployeeRoleChangedEvent deserializePayload(String payloadJson) {
        try {
            return objectMapper.readValue(payloadJson, EmployeeRoleChangedEvent.class);
        } catch (Exception e) {
            log.error("反序列化 EmployeeRoleChangedEvent 失败: {}", e.getMessage(), e);
            return null;
        }
    }
}
