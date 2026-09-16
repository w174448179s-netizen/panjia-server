-- V100010: 为三个流程定义中的总监审批节点添加 AutoApproval ext 配置
-- 设计依据：《审批集成设计说明 V1.0》§六 坑①——总监超时72小时自动审批通过
-- ext 格式：[{"code":"ButtonPermissionEnum","value":"..."},{"code":"AutoApproval","value":"hours=72,skipType=PASS"}]
-- 幂等：ext NOT LIKE '%AutoApproval%' 确保重复执行不会重复追加

UPDATE flow_node
SET ext = REPLACE(ext, ']', ',{"code":"AutoApproval","value":"hours=72,skipType=PASS"}]')
WHERE node_code IN ('perf_director', 'rcv_director', 'capp_director')
  AND ext IS NOT NULL
  AND ext NOT LIKE '%AutoApproval%';
