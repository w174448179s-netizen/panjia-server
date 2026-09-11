package com.panjia.performance.domain;

/**
 * 非法状态转换异常。
 * <p>
 * 当状态机检测到不允许的状态流转时抛出，包含源状态、目标状态和实体名称，
 * 便于快速定位问题。
 */
public class IllegalStateTransitionException extends RuntimeException {

    /**
     * 构造非法状态转换异常。
     *
     * @param from   源状态
     * @param to     目标状态
     * @param entity 实体名称
     */
    public IllegalStateTransitionException(String from, String to, String entity) {
        super("状态非法转换: " + entity + " " + from + " → " + to);
    }
}
