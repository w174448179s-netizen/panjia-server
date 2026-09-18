package com.panjia.common.util;

import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;

import java.util.List;
import java.util.function.LongFunction;

/**
 * 部门数据权限工具：全系统统一口径——所有登录用户只能查询本部门（含下级）数据。
 *
 * <p>规则（前端 useDeptScope 同口径，默认选中本部门、树裁剪为子树）：
 * <ul>
 *   <li>超管 → 不限制；</li>
 *   <li>未传 deptId → 强制取登录用户 dept_id（列表按部门子树过滤）；</li>
 *   <li>传入本人部门或其下级部门 → 放行（允许在本部门范围内下钻）；</li>
 *   <li>传入非本部门子树的 deptId → 拒绝（防止越权指定他部门绕过过滤）；</li>
 *   <li>登录用户无归属部门 → 降级为不限制（避免系统账号被锁死）。</li>
 * </ul>
 *
 * <p>适用：业绩查询/新签明细/实收审批/业绩调整/结佣明细等全部按「门店/组别」过滤的列表接口。
 * 各业务模块不再各自实现 enforce 方法，统一调用本工具；部门子树解析通过方法引用注入
 * （如 {@code deptService::selectDeptAndChildById}），保持本模块对系统实现模块零依赖。
 */
@Slf4j
public final class DeptScopeUtils {

    private DeptScopeUtils() {
    }

    /**
     * 校验并返回列表查询实际生效的 deptId。
     *
     * @param requestedDeptId 调用方传入的 deptId（可空）
     * @param subtreeResolver 部门子树解析函数（通常传 deptService::selectDeptAndChildById）
     * @param bizLabel        业务标签，用于报错文案与日志（如"业绩"/"实收审批"/"结佣"）
     * @return 实际生效的 deptId；返回原值表示不限制
     * @throws ServiceException 越权访问非本部门子树数据时抛出
     */
    public static Long enforceSelfDeptScope(Long requestedDeptId,
                                            LongFunction<List<Long>> subtreeResolver,
                                            String bizLabel) {
        try {
            var loginUser = LoginHelper.getLoginUser();
            if (loginUser == null || LoginHelper.isSuperAdmin()) {
                return requestedDeptId;
            }
            Long userDeptId = loginUser.getDeptId();
            if (userDeptId == null) {
                log.warn("[{}] 登录用户 deptId 为空，部门数据权限降级为不限制（请检查账号配置）", bizLabel);
                return requestedDeptId;
            }
            if (requestedDeptId == null || requestedDeptId.equals(userDeptId)) {
                return userDeptId;
            }
            List<Long> scopeDeptIds = subtreeResolver.apply(userDeptId);
            if (scopeDeptIds == null || !scopeDeptIds.contains(requestedDeptId)) {
                throw new ServiceException("仅能查看本部门（含下级）" + bizLabel + "数据");
            }
            return requestedDeptId;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[{}] 解析部门数据权限失败，默认不限制", bizLabel, e);
            return requestedDeptId;
        }
    }

    /**
     * 返回当前登录用户的部门子树（含自身），用于行级数据校验（如钻取明细逐行比对 deptId）。
     *
     * @param subtreeResolver 部门子树解析函数（通常传 deptService::selectDeptAndChildById）
     * @return 本部门（含下级）deptId 列表；超管或无归属部门时返回 null（表示不限制）
     */
    public static List<Long> selfDeptSubtree(LongFunction<List<Long>> subtreeResolver) {
        try {
            var loginUser = LoginHelper.getLoginUser();
            if (loginUser == null || LoginHelper.isSuperAdmin()) {
                return null;
            }
            Long userDeptId = loginUser.getDeptId();
            if (userDeptId == null) {
                log.warn("登录用户 deptId 为空，部门数据权限降级为不限制（请检查账号配置）");
                return null;
            }
            return subtreeResolver.apply(userDeptId);
        } catch (Exception e) {
            log.warn("解析登录用户部门子树失败，默认不限制", e);
            return null;
        }
    }
}
