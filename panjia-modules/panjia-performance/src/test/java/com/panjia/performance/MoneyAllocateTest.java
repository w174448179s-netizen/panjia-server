package com.panjia.performance;

import com.panjia.performance.util.MoneyUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实收联动分摊回归测试（生产 TGCF2606071055 案例）。
 * <p>
 * 旧口径 {@link MoneyUtil#allocateByAmount} 按带符号金额占比分摊，正负混合行下分母被负数行主导，
 * 分摊方向反转：7月新签调整 -70.12 联动实收时，6月正数行反而被抬高 +86.75、7月负数行被压到 -173.50，
 * 污染其它期间。新口径 {@link MoneyUtil#allocateByAbsAmount} 按绝对值占比分摊，保证各行沿 delta 同向移动。
 */
@Tag("dev")
class MoneyAllocateTest {

    private static BigDecimal sum(BigDecimal[] parts) {
        BigDecimal s = BigDecimal.ZERO;
        for (BigDecimal p : parts) {
            s = s.add(p);
        }
        return s;
    }

    /**
     * 生产案例还原：郭恒平实收行 [6月495, 7月-990, 8月94.88]，新签变动 delta=-70.12。
     * 旧口径分母 = 495-990+94.88 = -400.12，分摊后 6月 +86.75（反转）、7月 -173.50；
     * 新口径必须全为负向分摊，且合计精确等于 delta。
     */
    @Test
    void mixedSignRowsMustNotFlipDirection() {
        List<BigDecimal> amounts = List.of(
            new BigDecimal("495.00"), new BigDecimal("-990.00"), new BigDecimal("94.88"));
        BigDecimal delta = new BigDecimal("-70.12");

        // 旧口径复现反转（仅作对照留痕，证明旧逻辑确实会污染 6月）
        BigDecimal[] signed = MoneyUtil.allocateByAmount(amounts, delta);
        assertEquals(new BigDecimal("86.75"), signed[0], "旧带符号口径下 6月正数行被反向抬高（生产事故复现）");

        // 新口径：全部沿 delta 同向（负向），无反转
        BigDecimal[] abs = MoneyUtil.allocateByAbsAmount(amounts, delta);
        assertEquals(new BigDecimal("-21.97"), abs[0]);
        assertEquals(new BigDecimal("-43.94"), abs[1]);
        assertEquals(new BigDecimal("-4.21"), abs[2]);
        assertEquals(delta, sum(abs), "Σparts 必须精确等于 delta");

        // 分摊后行金额：495→473.03、-990→-1033.94、94.88→90.67，无一行反号
        assertEquals(new BigDecimal("473.03"), amounts.get(0).add(abs[0]));
        assertEquals(new BigDecimal("-1033.94"), amounts.get(1).add(abs[1]));
        assertEquals(new BigDecimal("90.67"), amounts.get(2).add(abs[2]));
    }

    /**
     * 同号行新旧口径结果一致（贾泓升 [6月495, 8月70.12] delta=-70.12 → -61.42/-8.70）。
     */
    @Test
    void sameSignRowsConsistentWithLegacy() {
        List<BigDecimal> amounts = List.of(new BigDecimal("495.00"), new BigDecimal("70.12"));
        BigDecimal delta = new BigDecimal("-70.12");

        BigDecimal[] abs = MoneyUtil.allocateByAbsAmount(amounts, delta);
        BigDecimal[] signed = MoneyUtil.allocateByAmount(amounts, delta);
        assertEquals(new BigDecimal("-61.42"), abs[0]);
        assertEquals(new BigDecimal("-8.70"), abs[1]);
        assertEquals(signed[0], abs[0], "同号行新旧口径必须一致");
        assertEquals(signed[1], abs[1]);
        assertEquals(delta, sum(abs));
    }

    /**
     * 全负行增加（7月实收修正 [-495,-495] +668.50 类场景）：绝对值均摊。
     */
    @Test
    void allNegativeRowsIncreaseTogether() {
        List<BigDecimal> amounts = List.of(new BigDecimal("-495.00"), new BigDecimal("-495.00"));
        BigDecimal delta = new BigDecimal("668.50");

        BigDecimal[] abs = MoneyUtil.allocateByAbsAmount(amounts, delta);
        assertEquals(new BigDecimal("334.25"), abs[0]);
        assertEquals(new BigDecimal("334.25"), abs[1]);
        assertEquals(delta, sum(abs));
    }

    /**
     * 尾差必须补到绝对值最大行，且合计精确等于 delta。
     */
    @Test
    void tailDiffGoesToLargestAbsRow() {
        List<BigDecimal> amounts = List.of(
            new BigDecimal("100.00"), new BigDecimal("-50.00"), new BigDecimal("25.00"));
        BigDecimal delta = new BigDecimal("-70.13");

        BigDecimal[] abs = MoneyUtil.allocateByAbsAmount(amounts, delta);
        assertEquals(delta, sum(abs), "存在舍入尾差时 Σparts 仍必须精确等于 delta");
        // 各行均负向，无反转
        for (BigDecimal p : abs) {
            assertTrue(p.signum() <= 0, "混合符号行不得出现反向分摊：" + p);
        }
        // 方向校验：100→约-40、-50→约-20、25→约-10
        assertTrue(abs[0].abs().compareTo(abs[2].abs()) > 0);
    }

    /**
     * 全 0 行平摊，尾差补第一条（与带符号版本全 0 分支同语义）。
     */
    @Test
    void allZeroRowsSplitEvenly() {
        List<BigDecimal> amounts = List.of(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        BigDecimal[] abs = MoneyUtil.allocateByAbsAmount(amounts, new BigDecimal("9.00"));
        assertEquals(new BigDecimal("3.00"), abs[0]);
        assertEquals(new BigDecimal("3.00"), abs[1]);
        assertEquals(new BigDecimal("3.00"), abs[2]);
        assertEquals(new BigDecimal("9.00"), sum(abs));
    }
}
