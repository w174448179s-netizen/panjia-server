-- ============================================================
-- V110030
-- 修复 IM 账号映射重复行：唯一键由 (channel, corp_id, external_user_id)
-- 调整为 (channel, external_user_id)。
--
-- 原因：最初 corp_id 未配置（库里为空串），配置真实 CorpId 后再次同步，
--       同一钉钉 userid 被当成新账号再插一行，导致同一人出现两条记录，
--       免登/推送也可能命中旧行。corp_id 只作为属性，不应参与账号唯一定位。
--
-- 依赖：pj_people_im_mapping（V110027）
-- ============================================================

-- 1. 去重：同一渠道 + 同一 external_user_id 只保留一行
--    优先保留已绑定员工的记录；都未绑定或都已绑定时保留最新（mapping_id 最大）的一行
DELETE FROM pj_people_im_mapping a
USING pj_people_im_mapping b
WHERE a.channel = b.channel
  AND a.external_user_id = b.external_user_id
  AND a.mapping_id <> b.mapping_id
  AND (
        (b.employee_id IS NOT NULL AND a.employee_id IS NULL)
     OR ((b.employee_id IS NULL) = (a.employee_id IS NULL) AND b.mapping_id > a.mapping_id)
  );

-- 2. 重建唯一索引（corp_id 不再参与唯一约束）
DROP INDEX IF EXISTS uk_im_mapping_ext;
CREATE UNIQUE INDEX uk_im_mapping_ext ON pj_people_im_mapping(channel, external_user_id);

COMMENT ON INDEX uk_im_mapping_ext IS '同一渠道内一个账号仅一行，corp_id 仅作属性记录';
