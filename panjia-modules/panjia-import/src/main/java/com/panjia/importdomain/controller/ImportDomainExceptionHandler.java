package com.panjia.importdomain.controller;

import cn.hutool.http.HttpStatus;
import com.panjia.importdomain.domain.IllegalStateTransitionException;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 导入域模块内业务异常处理器。
 * <p>
 * 设计意图：{@link IllegalStateTransitionException} 是状态机硬约束的业务信号
 * （V2.0 §3.5 强制规则 #1/#2），属于预期内的用户操作冲突，<b>不应</b>穿透到
 * License 全局兜底（{@code GlobalLicenseExceptionHandler} 会将其伪装为
 * "系统内部错误"）。本 Handler 在本域范围内将其转成 {@code 400 + 友好提示}，
 * 前端按常规业务错误处理即可。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} 强制最高优先级，避免被
 * {@code GlobalLicenseExceptionHandler} 的 {@code Exception.class} 兜底抢先匹配。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ImportDomainExceptionHandler {

    /**
     * 状态机非法转换 → 业务可读提示。
     * 例：ARCHIVED → ARCHIVED → "该批次已归档，无需重复操作"
     */
    @ExceptionHandler(IllegalStateTransitionException.class)
    public R<Void> handleIllegalStateTransition(IllegalStateTransitionException e) {
        log.warn("[ImportDomainExceptionHandler] 状态机拒绝: {}", e.getMessage());
        return R.fail(HttpStatus.HTTP_BAD_REQUEST, friendlyMessage(e.getMessage()));
    }

    /**
     * 把状态机的原始 message（"非法状态转换: FROM → TO"）翻译成业务可读文案。
     */
    private static String friendlyMessage(String raw) {
        if (raw == null || !raw.startsWith("非法状态转换:")) {
            return raw == null ? "操作不被允许" : raw;
        }
        // 例："非法状态转换: ARCHIVED → ARCHIVED"
        String body = raw.substring("非法状态转换:".length()).trim();
        String[] parts = body.split("→");
        if (parts.length != 2) {
            return "当前状态不允许该操作";
        }
        String from = parts[0].trim();
        String to = parts[1].trim();
        if (from.equals(to)) {
            // 同态转换（典型：ARCHIVED → ARCHIVED）
            return switch (from) {
                case "ARCHIVED" -> "该批次已归档，无需重复操作";
                case "FAILED" -> "该批次已失败，请新建批次重试";
                default -> "该批次已处于" + statusDesc(from) + "状态";
            };
        }
        // 异态转换（典型：ARCHIVED → NORMALIZING）
        return switch (from + "->" + to) {
            case "ARCHIVED->NORMALIZING" -> "已归档的批次不支持重归一化";
            case "ARCHIVED->PENDING_CONFIRM" -> "已归档的批次不支持重归一化";
            case "FAILED->NORMALIZING" -> "已失败的批次不支持重归一化，请新建批次重试";
            case "FAILED->ARCHIVED" -> "已失败的批次不支持归档，请新建批次重试";
            default -> "当前状态（" + statusDesc(from) + "）不允许该操作";
        };
    }

    private static String statusDesc(String name) {
        return switch (name) {
            case "PARSING" -> "解析中";
            case "NORMALIZING" -> "归一化中";
            case "PENDING_CONFIRM" -> "待确认";
            case "ARCHIVED" -> "已归档";
            case "FAILED" -> "已失败";
            default -> name;
        };
    }
}