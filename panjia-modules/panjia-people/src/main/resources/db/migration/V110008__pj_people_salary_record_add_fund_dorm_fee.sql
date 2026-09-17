-- 社保金额、商业保险金额、公积金金额、宿舍费金额落到 salary_record 物化表
ALTER TABLE pj_people_salary_record
    ADD COLUMN IF NOT EXISTS social_fee     DECIMAL(12,2),
    ADD COLUMN IF NOT EXISTS commercial_fee DECIMAL(12,2),
    ADD COLUMN IF NOT EXISTS housing_fund   DECIMAL(12,2),
    ADD COLUMN IF NOT EXISTS dormitory_fee  DECIMAL(12,2);

COMMENT ON COLUMN pj_people_salary_record.social_fee     IS '社保金额（自定义；null=用全局默认算法）';
COMMENT ON COLUMN pj_people_salary_record.commercial_fee IS '商业保险金额（自定义；null=用全局默认 21 元）';
COMMENT ON COLUMN pj_people_salary_record.housing_fund   IS '公积金金额（自定义；null=用全局默认算法）';
COMMENT ON COLUMN pj_people_salary_record.dormitory_fee IS '宿舍费金额（自定义；null=用全局默认算法）';
