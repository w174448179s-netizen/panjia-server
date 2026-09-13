-- V120008: 退单红冲场景下 currentReceived / receivableAmount 允许负数
-- 将 gte:0 改为 not_blank，保留非空校验但允许负值

UPDATE pj_import_template
SET validation_rules = jsonb_set(
    validation_rules,
    '{row_level}',
    (SELECT jsonb_agg(
        CASE
            WHEN elem->>'field' IN ('currentReceived', 'receivableAmount') AND elem->>'rule' = 'gte:0'
            THEN elem - 'rule' - 'message' || jsonb_build_object(
                'rule', 'not_blank',
                'message', CASE WHEN elem->>'field' = 'currentReceived'
                    THEN '当月实收业绩不能为空（退单可为负）'
                    ELSE '应收业绩不能为空（退单可为负）'
                END
            )
            ELSE elem
        END
    )
    FROM jsonb_array_elements(validation_rules->'row_level') AS elem)
)
WHERE source_type IN ('KE_SIGNED', 'KE_NEW_SIGN')
  AND validation_rules @> '{"row_level":[{"rule":"gte:0","field":"currentReceived"}]}'::jsonb
     OR validation_rules @> '{"row_level":[{"rule":"gte:0","field":"receivableAmount"}]}'::jsonb;

-- 确保 KE_SIGNED 模板的 currentReceived 规则也被更新（覆盖 field 名匹配）
UPDATE pj_import_template
SET validation_rules = jsonb_set(
    validation_rules,
    '{row_level}',
    (SELECT jsonb_agg(
        CASE
            WHEN elem->>'field' = 'currentReceived' AND elem->>'rule' = 'gte:0'
            THEN jsonb_build_object(
                'rule', 'not_blank',
                'field', 'currentReceived',
                'message', '当月实收业绩不能为空（退单可为负）'
            )
            ELSE elem
        END
    )
    FROM jsonb_array_elements(validation_rules->'row_level') AS elem)
)
WHERE template_code = 'KE_SIGNED'
  AND validation_rules @> '{"row_level":[{"rule":"gte:0","field":"currentReceived"}]}'::jsonb;

-- 确保 KE_NEW_SIGN 模板的 receivableAmount 规则也被更新
UPDATE pj_import_template
SET validation_rules = jsonb_set(
    validation_rules,
    '{row_level}',
    (SELECT jsonb_agg(
        CASE
            WHEN elem->>'field' = 'receivableAmount' AND elem->>'rule' = 'gte:0'
            THEN jsonb_build_object(
                'rule', 'not_blank',
                'field', 'receivableAmount',
                'message', '应收业绩不能为空（退单可为负）'
            )
            ELSE elem
        END
    )
    FROM jsonb_array_elements(validation_rules->'row_level') AS elem)
)
WHERE template_code = 'KE_NEW_SIGN'
  AND validation_rules @> '{"row_level":[{"rule":"gte:0","field":"receivableAmount"}]}'::jsonb;
