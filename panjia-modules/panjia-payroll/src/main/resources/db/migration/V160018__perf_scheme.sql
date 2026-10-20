-- ============================================================
-- 绩效方案体系：方案配置表 + 工资明细绩效列 + 种子方案 + 菜单
-- 5 类方案：NONE 无绩效 / GRADE_RATE 积分绩效（等级扣提点）/
--          RATE_TIER 业绩阶梯扣提点 / AMOUNT_TIER 业绩阶梯扣金额 /
--          GRADE_AMOUNT 积分等级扣金额（店长）
-- 未指派绩效方案事实的员工按 is_default 方案（积分绩效）计算，
-- 与改造前 policy.points 口径一致（历史批次重算零差异）。
-- ============================================================

-- ========== 1. 绩效方案配置表 ==========
CREATE TABLE IF NOT EXISTS pj_payroll_perf_scheme (
    id              BIGINT PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL,
    name            VARCHAR(64)  NOT NULL,
    scheme_type     VARCHAR(16)  NOT NULL,
    applicable_role VARCHAR(16)  NOT NULL DEFAULT 'ALL',
    rule_content    text,
    is_default      BOOLEAN      NOT NULL DEFAULT FALSE,
    effective_from  DATE         NOT NULL DEFAULT CURRENT_DATE,
    create_time     TIMESTAMP    DEFAULT now(),
    update_time     TIMESTAMP,
    CONSTRAINT uk_perf_scheme_code UNIQUE (code)
);

COMMENT ON TABLE  pj_payroll_perf_scheme IS '绩效方案配置（员工经 PERF_SCHEME 事实按月指派；算薪按方案类型计算扣点/扣款）';
COMMENT ON COLUMN pj_payroll_perf_scheme.code IS '方案编码（唯一，PERF_SCHEME 事实存储值）';
COMMENT ON COLUMN pj_payroll_perf_scheme.name IS '方案名称（展示）';
COMMENT ON COLUMN pj_payroll_perf_scheme.scheme_type IS '方案类型 NONE=无绩效/GRADE_RATE=积分等级扣提点/RATE_TIER=业绩阶梯扣提点/AMOUNT_TIER=业绩阶梯扣金额/GRADE_AMOUNT=积分等级扣金额';
COMMENT ON COLUMN pj_payroll_perf_scheme.applicable_role IS '适用岗位 AGENT=经纪人/MANAGER=店长/ALL=不限（员工指派时校验）';
COMMENT ON COLUMN pj_payroll_perf_scheme.rule_content IS '方案参数 JSON：GRADE_RATE={gradeA,gradeB,deductA,deductB,deductC,penaltyFee}；GRADE_AMOUNT={gradeA,gradeB,feeA,feeB,feeC}；RATE_TIER={tiers:[{min,max,rate}]}（rate 负=扣点）；AMOUNT_TIER={tiers:[{min,max,amount}]}（amount 正=扣款）';
COMMENT ON COLUMN pj_payroll_perf_scheme.is_default IS '默认方案（员工未指派 PERF_SCHEME 事实时生效；当前约定唯一默认=积分绩效）';

-- ========== 2. 工资明细加列 ==========
ALTER TABLE pj_payroll_detail ADD COLUMN IF NOT EXISTS perf_deduct_fee   NUMERIC;
ALTER TABLE pj_payroll_detail ADD COLUMN IF NOT EXISTS perf_scheme_code  VARCHAR(32);
ALTER TABLE pj_payroll_detail ADD COLUMN IF NOT EXISTS perf_calc_process text;

COMMENT ON COLUMN pj_payroll_detail.perf_deduct_fee   IS '绩效方案金额扣款（业绩阶梯B版/店长积分绩效：未达标扣固定金额，正数=扣款，进应发扣减；提点类扣款仍走 perf_deduct）';
COMMENT ON COLUMN pj_payroll_detail.perf_scheme_code  IS '绩效方案编码（该批次冻结的方案，空=默认积分绩效）';
COMMENT ON COLUMN pj_payroll_detail.perf_calc_process IS '绩效方案计算过程 JSON {schemeCode,schemeName,schemeType,grade?,tierMin?,tierMax?,perf?,deduct?,fee?}；未触发也落地供前端展示口径';

-- ========== 3. 种子方案（参数复制现 policy.points 口径，保证历史重算零差异） ==========
INSERT INTO pj_payroll_perf_scheme (id, code, name, scheme_type, applicable_role, rule_content, is_default, effective_from) VALUES
-- 积分绩效（默认）：与 policy.points 完全同参（8/6 分界，A 不扣 / B 扣 2% / C 扣 4%，晚提交 5 元/次）
(1762500000000000401, 'POINTS',        '积分绩效',    'GRADE_RATE',  'ALL',
 '{"gradeA":8.0,"gradeB":6.0,"deductA":0.0,"deductB":-0.02,"deductC":-0.04,"penaltyFee":5}', TRUE,  '2026-01-01'),
-- 业绩绩效A版：个人新签业绩阶梯扣提点，[min,max) 半开区间，末档 max=null 表无穷
(1762500000000000402, 'NEW_SIGN_RATE', '业绩绩效A版', 'RATE_TIER',   'ALL',
 '{"tiers":[{"min":0,"max":8000,"rate":-0.04},{"min":8000,"max":15000,"rate":-0.02},{"min":15000,"max":null,"rate":0}]}', FALSE, '2026-01-01'),
-- 业绩绩效B版：个人新签业绩阶梯扣金额（amount 正=扣款）
(1762500000000000403, 'NEW_SIGN_FEE',  '业绩绩效B版', 'AMOUNT_TIER', 'ALL',
 '{"tiers":[{"min":0,"max":8000,"amount":400},{"min":8000,"max":15000,"amount":200},{"min":15000,"max":null,"amount":0}]}', FALSE, '2026-01-01'),
-- 店长积分绩效：积分等级扣金额，仅店长可指派
(1762500000000000404, 'MANAGER_POINTS','店长积分绩效','GRADE_AMOUNT','MANAGER',
 '{"gradeA":8.0,"gradeB":6.0,"feeA":0,"feeB":300,"feeC":500}', FALSE, '2026-01-01'),
-- 无绩效：兼职等不考核人群
(1762500000000000405, 'NO_PERF',       '无绩效',      'NONE',        'ALL',
 NULL, FALSE, '2026-01-01');

-- ========== 4. 菜单与授权（C 型页面挂「薪酬计算」2300，配置口径仅超管） ==========
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param,
                      is_frame, is_cache, menu_type, visible, status, perms, icon, active_menu,
                      ext, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
(1761400000000002325, '绩效方案', 1761400000000002300, 8, 'scheme', 'payroll/scheme/index', NULL,
 'N', 'Y', 'C', '0', '0', 'payroll:scheme:list', 'star', '', '',
 1761000000000000100, 1761100000000000001, now(), NULL, NULL, '绩效方案配置（积分/业绩阶梯/店长积分，员工档案指派）')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO sys_role_menu (role_id, menu_id) VALUES
(1761300000000000001, 1761400000000002325)  -- 超级管理员 → 绩效方案菜单
ON CONFLICT (role_id, menu_id) DO NOTHING;
