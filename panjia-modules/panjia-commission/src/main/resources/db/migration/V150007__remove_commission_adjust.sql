-- ============================================================================
-- 下线「结佣调整」功能：清理菜单、流程定义、配置项与调整单表。
-- 原则：
--   1. 不修改任何已应用的迁移脚本；
--   2. 全部 DELETE 使用精确条件，可重复执行；
--   3. 存在流程实例时保留流程定义，避免破坏在途/历史审批；
--   4. 调整单表仅在没有数据时 DROP，有数据时保留并输出提示。
-- ============================================================================

-- ---------- 1. 菜单：结佣调整页面（1761400000000002203）及其全部子按钮 ----------
DELETE FROM sys_role_menu
WHERE menu_id IN (
    SELECT menu_id FROM sys_menu
    WHERE menu_id = 1761400000000002203 OR parent_id = 1761400000000002203
);

DELETE FROM sys_menu
WHERE menu_id = 1761400000000002203 OR parent_id = 1761400000000002203;

-- ---------- 2. 流程定义（flowCode = commission_adjust） ----------
-- 有流程实例时整体保留；无实例时删除流转、节点、任务及定义。
DO $$
DECLARE
    v_instance_count bigint;
BEGIN
    SELECT COUNT(*) INTO v_instance_count
    FROM flow_instance
    WHERE definition_id IN (
        SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust'
    );

    IF v_instance_count = 0 THEN
        DELETE FROM flow_user
        WHERE associated IN (
            SELECT id FROM flow_task
            WHERE definition_id IN (
                SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust'
            )
        );

        DELETE FROM flow_task
        WHERE definition_id IN (
            SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust'
        );

        DELETE FROM flow_his_task
        WHERE definition_id IN (
            SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust'
        );

        DELETE FROM flow_skip
        WHERE definition_id IN (
            SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust'
        );

        DELETE FROM flow_node
        WHERE definition_id IN (
            SELECT id FROM flow_definition WHERE flow_code = 'commission_adjust'
        );

        DELETE FROM flow_definition WHERE flow_code = 'commission_adjust';
    ELSE
        RAISE NOTICE 'commission_adjust 存在 % 个流程实例，保留流程定义', v_instance_count;
    END IF;
END $$;

-- ---------- 3. 配置项（V200007 已删除时此处为空操作） ----------
DELETE FROM sys_config WHERE config_key = 'panjia.workflow.commission_adjust.skip_verify';

-- ---------- 4. 调整单表：仅在存在且为空时删除 ----------
DO $$
DECLARE
    v_row_count bigint;
BEGIN
    IF to_regclass('public.pj_commission_adjust') IS NOT NULL THEN
        EXECUTE 'SELECT COUNT(*) FROM pj_commission_adjust' INTO v_row_count;
        IF v_row_count = 0 THEN
            DROP TABLE pj_commission_adjust;
            RAISE NOTICE 'pj_commission_adjust 为空，已删除';
        ELSE
            RAISE NOTICE 'pj_commission_adjust 存在 % 条数据，保留表不删除', v_row_count;
        END IF;
    END IF;
END $$;
