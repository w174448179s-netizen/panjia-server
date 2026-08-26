package com.panjia.common.aspect;

import com.panjia.common.annotation.Idempotent;
import com.panjia.common.exception.DuplicateOperationException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.dromara.common.redis.utils.RedisUtils;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.time.Duration;

/**
 * 幂等切面
 * <p>
 * 基于 Redis SETNX 实现，幂等键有效期 24 小时。
 * 支持 SpEL 表达式作为幂等键，从方法参数中提取。
 */
@Aspect
@Component
public class IdempotentAspect {

    private static final String IDEMPOTENT_PREFIX = "panjia:idempotent:";
    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String key = resolveKey(joinPoint, idempotent.key());
        String redisKey = IDEMPOTENT_PREFIX + key;

        boolean acquired = RedisUtils.setObjectIfAbsent(redisKey, "1", Duration.ofSeconds(idempotent.expire()));
        if (!acquired) {
            throw new DuplicateOperationException(idempotent.message());
        }

        try {
            return joinPoint.proceed();
        } catch (Throwable e) {
            // 执行失败时删除幂等键，允许重试
            RedisUtils.deleteObject(redisKey);
            throw e;
        }
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, String keyExpr) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = DISCOVERER.getParameterNames(method);

        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }

        Expression expression = PARSER.parseExpression(keyExpr);
        Object value = expression.getValue(context);
        return value != null ? value.toString() : "default";
    }
}
