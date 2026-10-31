# 任务清单：订单号口径一致性修复

> 依据 `spec.md`。执行状态：代码改造全部完成并编译通过；数据核对已通过；撤销批次重导与 TC-01..TC-10 需在测试环境界面执行。

## [x] T1 退单冲减明细查询兼容合同号键（P0）

- 文件：[CommissionRefundAdapter.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-commission/src/main/java/com/panjia/commission/adapter/CommissionRefundAdapter.java)
- 方法：`listSettledItems(String contractNo, String orderNo)`
- 改动：`contractNo` 非空时加 `eq(ContractNo)` 等值；`orderNo` 非空时改为
  `and(order_no=orderNo OR order_no=contractNo)`；`orderNo` 为空保持原逻辑。
- 结果：已改，编译通过；`sumSettledExpectAmount` / `listSettledLines` / `createRefundReversal` 均经此方法自动生效。

## [x] T2 实收事实查询双键退化（P0）

- 文件：[ReceivedRealFactAdapter.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-received/src/main/java/com/panjia/received/adapter/ReceivedRealFactAdapter.java)
- 方法：`findActiveByContract(period, orderNo, contractNo)`
- 改动：主查询 0 命中 + orderNo/contractNo 均非空 + 二者不等 + `countDistinctOrderNos(contractNo)==1` → 以 `orderNo=null` 重查；多订单不退化。
- 结果：已改，编译通过；`ReceivedAdjustServiceImpl` 4 处调用自动受益。

## [x] T3 新签补建单壳定位退化（P0）

- 文件：[ReceivedApplyServiceImpl.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-received/src/main/java/com/panjia/received/service/impl/ReceivedApplyServiceImpl.java)
- 方法：`resolvePendingAfterNewSign` + 新增私有方法 `resolvePendingContracts`
- 改动：壳定位三级回退（order_no=订单号 → order_no=合同号 → contract_no=合同号且唯一）；命中别名壳但同合同存在真实壳时改用真实壳；存在性判断改用定位后的锚点壳订单号。
- 结果：已改，编译通过。

## [x] T4 手工提交实收复用真实壳（P1）

- 文件同上
- 方法：`getOrCreateManualContract`
- 改动：建 MANUAL 壳前，若同期间同合同非虚拟订单号唯一且仅 1 个壳、且该壳为真实壳，直接复用。
- 结果：已改，编译通过。

## [x] T5 工资导出兜底补合同号键（P1）

- 文件：[CommissionQueryAdapter.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-commission/src/main/java/com/panjia/commission/adapter/CommissionQueryAdapter.java)
- 方法：`findRealFacts`
- 改动：bizKeys 同时收集 `orderNo` 与 `contractNo`。
- 结果：已改，编译通过。

## [x] T6 编译与静态检查

- 命令：`./mvnw -o -q -pl panjia-modules/panjia-received,panjia-modules/panjia-commission,panjia-modules/panjia-performance -am -DskipTests compile`
- 结果：编译成功（无输出，退出码 0）；未新增硬编码 SQL。
- 备注：不带 `-am` 时因本地 `panjia-contracts` 快照缺 `ReceivedContractInfoDTO`/`ReceivedFactInfoDTO`/`PendingReceipt` 报符号错误，与本次改动无关，用 `-am` 从源码构建依赖后通过。

## [x] T7a 数据核对（测试库 162.14.72.197 / panjia_test）

| 项 | 期望 | 实测 |
|----|------|------|
| 闸门：排除纯虚拟壳后实收去重订单号数 | 1 | 1 |
| `pj_received_contract` 该合同壳 | 合并前 2（MANUAL 虚拟 + KE 真实） | 2（MANUAL `2116521943131762689` / KE `2116522090603491331`） |
| 实收 ACTIVE 明细 | 16 | 16（虚拟 8 行净额 0；KE 8 行合计 2927.11） |
| 新签 ACTIVE 合计 | 2742.20 | 2742.20（8 行，其中 3 行非零） |
| 已结佣正额明细（R1 查询） | 0（尚未结佣） | 0 |
| 实收调整单 | 存在且 EXECUTED | `2116521943375032321`，order_no=contract_no=100015671805 |

## [ ] T7b 功能回归（需在测试环境界面执行，待业务操作）

- 撤销 KE 批次 `2116522069132849153` → 重新导入 → 核对：1 个壳、1 张真实审批单、16 条 ACTIVE、实收 2927.11 / 应收 2742.20；
- 发起结佣（合同 100015671805）成功，应结 2742.20；
- 退单冲减命中历史两种 `order_no` 落单（R1）；
- 实收调整（`pj_perf_received_adjust` 2116521943375032321）重执行/撤销不报「无有效实收明细」（R2）；
- 新签补录传 `orderNo=合同号` 命中真实壳、不新增壳（R3）；手工提交实收后仍 1 个壳（R4）；
- 工资导出该合同新签行存在且金额 2742.20（R5）；
- TC-01..TC-10 全过。

## 部署节奏（待确认）

- 本地验证 → 测试环境 → 生产；一次构建产物复用；禁止改码后同时部署两个环境。
