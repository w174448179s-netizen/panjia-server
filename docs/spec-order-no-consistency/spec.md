# 订单号口径一致性排查与修复规格 V1.0

> 关联文档：《盘家智管_实收虚拟壳合并详细设计_V1.0.md》（已批准，§6 四项改造已落地）
> 触发场景：合同 100015671805 发起结佣报「该合同/订单没有可结佣的新签业绩」。
> 本规格覆盖该问题之外，全链路同类「新签 `order_no=合同号` vs 实收 `order_no=真实订单号`」口径不一致的残余点。

## 1. 背景

贝壳新签导入时 `order_no` 被误填为合同号（`order_no = contract_no = 100015671805`），
而对应实收壳 `order_no = 14567723`、`contract_no = 100015671805`。虚拟 MANUAL 壳与真实 KE 壳合并（重键）后：

- 新签事实：两列均为合同号；
- 实收事实/壳：`order_no=14567723`、`contract_no=100015671805`。

任何「以实收订单号作为唯一业务键去查新签」的路径都会 0 命中。已修复两处：

1. `ReceivedContractMapper.countDistinctOrderNos` 排除纯虚拟壳（否则闸门计数为 2，退化被拒）。
2. `CommissionApplicationService.loadUnsettledExpectFacts` 双键精确 + 未命中走双侧闸门退化合同号。

本次排查的目标：确定其余同类路径是否修复，并给出可执行的改造清单。

## 2. 问题清单（残余同类点）

| # | 位置 | 现状 | 影响 | 级别 | 结论 |
|---|------|------|------|------|------|
| R1 | [CommissionRefundAdapter.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-commission/src/main/java/com/panjia/commission/adapter/CommissionRefundAdapter.java#L189-L200) `listSettledItems` | `orderNo` 非空时只 `eq(item.order_no, orderNo)`，无合同号兜底 | 历史按合同号落单的已结佣明细，在退单冲减时漏冲，退单金额算少 | P0 | 需改 |
| R2 | [ReceivedAdjustServiceImpl.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-performance/src/main/java/com/panjia/performance/service/impl/ReceivedAdjustServiceImpl.java#L273-L273) L273/349/645/990 | 以调整单存档 `(orderNo, contractNo)` 查 `PERF_REAL`，实收侧 `selectActiveByContract` 的 `orderNo` 无退化 | 调整单基于虚拟壳旧订单号（合同号）→ 壳重键后执行报「该期间无有效实收明细」 | P0 | 需改 |
| R3 | [ReceivedApplyServiceImpl.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-received/src/main/java/com/panjia/received/service/impl/ReceivedApplyServiceImpl.java#L1388-L1470) `resolvePendingAfterNewSign` | step2 定位壳仅按 `order_no=orderNo`，无退化 | 新签补录入口传 `orderNo=合同号` 时定位不到已重键的真实壳 → 补建单静默跳过（异常仅 warn） | P0 | 需改 |
| R4 | [ReceivedApplyServiceImpl.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-received/src/main/java/com/panjia/received/service/impl/ReceivedApplyServiceImpl.java#L3847-L3853) `getOrCreateManualContract` | 按新签 `expect.orderNo`（=合同号）+ MANUAL 幂等建壳 | 手工补录实收会新建第三个壳（合同号壳），重新引入双壳 | P1 | 需改 |
| R5 | [CommissionQueryAdapter.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-commission/src/main/java/com/panjia/commission/adapter/CommissionQueryAdapter.java#L87-L116) `findRealFacts` | bizKeys 只收集实收事实的 `orderNo`，不收集 `contractNo` | 工资导出兜底（无锁单时）漏该合同新签行，导出金额少 | P1 | 需改 |
| R6 | [ReceivedApplyServiceImpl.java](file:///Users/shengwei/workspace/panjia-server/panjia-modules/panjia-received/src/main/java/com/panjia/received/service/impl/ReceivedApplyServiceImpl.java#L3361-L3361) `getContractDetail` | `contractNo AND orderNo` 精确查壳 | 双壳期/历史单查不到详情 | P2 | 不改（合并后自愈） |

已核查确认**安全，无需改动**的路径：

- `PerformanceFactSummaryDTO` 相关：`PerfRefundServiceImpl`（keys 同时含 orderNo 与 contractNo）、`ReceivedSupplementSupport`（同）、`ReceivedRealFactMapper.selectActiveByBizKeys`（order_no IN OR contract_no IN）。
- `CommissionApplicationService` 其余调用点：L282 `approvedRealFactsOfMonth`（实收侧键，按实收订单号，天然命中）、L586 批量实收、L1215 `loadExpectItemFacts`（逐对双键）、L2641 `fillExpectedAdjusted`（contractNo 优先）、L2748 `createApplicationWithItems` 应收补算（orderNo 未命中回退 contractNo）。
- `BizKeyMatchGuard` / `CommissionPerformanceAdapter.findActiveByBizKey`（EXPECT 已具备退化）。
- 列表页 `listAvailableContracts`：expectKeys 双键 + 分组双键兜底，故用户能点到「发起结佣」按钮。

## 3. 修复方案（逐文件）

### R1 `CommissionRefundAdapter.listSettledItems`

```java
private List<CommissionItem> listSettledItems(String contractNo, String orderNo) {
    LambdaQueryWrapper<CommissionItem> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(CommissionItem::getStatus, ItemStatus.APPROVED)
        .eq(CommissionItem::getFactType, FACT_TYPE_EXPECT)
        .gt(CommissionItem::getAmount, BigDecimal.ZERO);
    if (StringUtils.isNotBlank(contractNo)) {
        wrapper.eq(CommissionItem::getContractNo, contractNo);
    }
    if (StringUtils.isNotBlank(orderNo)) {
        // 兼容历史「order_no 落的是合同号」的明细：同合同下 order_no 命中真实订单号或合同号均可
        wrapper.and(w -> w.eq(CommissionItem::getOrderNo, orderNo)
            .or(StringUtils.isNotBlank(contractNo), x -> x.eq(CommissionItem::getOrderNo, contractNo)));
    } else if (StringUtils.isNotBlank(contractNo)) {
        wrapper.eq(CommissionItem::getContractNo, contractNo);
    }
    return itemMapper.selectList(wrapper);
}
```

约束：新增 `contractNo` 等值条件作为串单防线（同合同多订单时不会跨合同误冲）；`orderNo` 为空时行为不变。

### R2 实收调整执行的双键退化

改动点：`ReceivedRealFactAdapter.findActiveByContract`（唯一出口，被 `CommissionPerformanceAdapter` 的 `PERF_REAL` 分支调用）。

1. 先按 `(orderNo, contractNo)` 调 `realMapper.selectActiveByContract(period, orderNo, matchKey)`；
2. 结果非空直接返回；
3. 结果为空且 `orderNo != null` 且 `contractNo != null` 且 `orderNo != contractNo` 时，先过闸门：
   `contractMapper.countDistinctOrderNos(contractNo) == 1`（该方法已排除纯虚拟壳），
   通过则再以 `orderNo=null` 调一次 `selectActiveByContract(period, null, contractNo)`；
4. 多订单（≥2）不退化，保持 0 行，由调用方报错（防串单）。

影响范围：`ReceivedAdjustServiceImpl` 4 处调用自动受益，不逐个改调用点。

### R3 `resolvePendingAfterNewSign` 壳定位退化

step2 定位壳逻辑改为三级回退（每级均限定 `period`）：

1. `order_no = orderNo`（orderNo 非空时）；
2. `order_no = contractNo`（orderNo≠contractNo，覆盖合并前虚拟壳/历史按合同号建的壳）；
3. `contract_no = contractNo` 且该合同下非虚拟壳唯一（`countDistinctOrderNos(contractNo)==1`）。

任一命中即返回；全部未命中保持现有 warn 跳过。step1 存在性判断同步按上述键集合判断，避免「事实存在但壳判空」。

### R4 `getOrCreateManualContract` 复用真实壳

建 MANUAL 壳前：

1. 按 `(period, contractNo)` 查已有壳；
2. 若恰有 1 个壳且其 `order_no` 非合同号（真实壳），直接复用该壳（不再新建）；
3. 否则保持现逻辑（按 `expect.orderNo` + MANUAL 幂等新建）。

保证手工补录不再产生「合同号壳」的第三个壳。

### R5 `CommissionQueryAdapter.findRealFacts`

bizKeys 收集时同时加入 `contractNo`：

```java
for (PerformanceFactSummaryDTO f : realFacts) {
    if (StringUtils.isNotBlank(f.getOrderNo())) bizKeys.add(f.getOrderNo().trim());
    if (StringUtils.isNotBlank(f.getContractNo())) bizKeys.add(f.getContractNo().trim());
}
```

`findActiveByBizKeys` 本身对 order_no/contract_no 双列 OR，加入合同号键后即可命中「两列都是合同号」的新签行。仅导出兜底路径，无写入风险。

### R6 不改

`getContractDetail` 保持精确双键（防串单优先）；合并完成后双壳消失，该问题自愈。

## 4. 防串单统一约束

所有退化必须同时满足：

- `contractNo` 非空；
- `countDistinctOrderNos(contractNo) == 1`（实收侧已排除纯虚拟壳）；
- 仅用于「查询 0 命中」的兜底，不改变主路径。

禁止在 `order_no` 主查询上直接加 `OR contract_no`（会让同合同多订单串单）。

## 5. 验证方案

### 5.1 静态

- 编译：`./mvnw -o -q -pl panjia-modules/panjia-received,panjia-modules/panjia-commission,panjia-modules/panjia-performance -DskipTests compile`
- 确认无新增硬编码 SQL，退化查询复用既有 Mapper 方法。

### 5.2 数据（测试库 162.14.72.197 / panjia_test）

1. 合并前基线SQL（设计文档 §8 兜底 SQL）：纯虚拟壳数=1、真实审批单=1、ACTIVE 明细=16、实收 2927.11、应收 2742.20。
2. R1：`SELECT count(*) FROM pj_commission_item WHERE contract_no='100015671805' AND (order_no='14567723' OR order_no='100015671805') AND status='APPROVED' AND fact_type='PERF_EXPECT' AND amount>0`，应覆盖历史两种落单。
3. R2：构造/复用一张 adjustment（order_no=100015671805）在执行后不报「无有效实收明细」，且金额与调整前一致。
4. R3：对新签补录传 `orderNo=100015671805`，确认补建单命中真实壳，不新增壳。
5. R4：手工提交实收后 `pj_received_contract` 该合同同期壳数量仍为 1。
6. R5：工资导出该合同新签行存在且金额 2742.20。

### 5.3 功能回归

- 发起结佣（合同 100015671805）成功，应结 2742.20；
- 自动发起（实收审批通过事件）仍一张实收单一张结佣单；
- 退单执行后冲减金额为正明细的相反数，无漏冲；
- 撤销批次 `2116522069132849153` → 重新导入 → 走新合并逻辑（TC-01..TC-10）。

## 6. 风险与回滚

| 风险 | 缓解 |
|------|------|
| 退单 `OR order_no=合同号` 误匹配 | 额外 `contract_no` 等值约束 + 仅 APPROVED/正额/PERF_EXPECT |
| 实收退化带回同合同其他订单 | 闸门强制 `countDistinctOrderNos==1` |
| 手工补录复用错壳 | 仅当合同下壳唯一且非合同号壳时复用 |
| 工资导出重复计数 | bizKeys 为 Set，合同号键去重，`findActiveByBizKeys` 单行只返回一次 |

回滚：四处改动均为单方法级，可用 git revert 单文件回退；无表结构变更。

## 7. 审批

需用户确认后进入执行：

- [ ] 确认 R1~R5 全部修改（推荐）
- [ ] 仅修 P0（R1/R2/R3）
- [ ] 仅修 P0 + R4
- [ ] 先只修 R1/R3
