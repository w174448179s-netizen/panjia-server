-- 伯乐加点落库（RATE_ADD 模式溯源展示）
-- 经纪人职级规则 mentorBonusMode=RATE_ADD 时，伯乐加点 = MIN(加点费率 × 达标千里马数, 封顶)，
-- 已计入 finalRate（最终提成比例）。仅 RATE_ADD 模式落库（null=AMOUNT_RATIO 模式），
-- 前端「伯乐奖励」列据此自适应：mentor_add 非空显示加点百分比，否则显示 mentor_bonus 金额。
ALTER TABLE pj_payroll_detail
    ADD COLUMN IF NOT EXISTS mentor_add NUMERIC(8,6);

COMMENT ON COLUMN pj_payroll_detail.mentor_add IS '伯乐加点（仅RATE_ADD模式落地：加点费率×达标千里马数封顶，已计入finalRate；AMOUNT_RATIO模式为空）';
