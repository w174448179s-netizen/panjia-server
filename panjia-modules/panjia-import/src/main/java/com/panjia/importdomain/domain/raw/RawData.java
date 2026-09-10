package com.panjia.importdomain.domain.raw;

/**
 * 原始归档数据标记接口（V1.4 §3.2）。
 * <p>
 * 所有 RawData 实体 insert-only，禁止 UPDATE/DELETE。
 * 归一化阶段只读不写 RawData。
 */
public interface RawData {

    Long getId();

    Long getBatchId();

    Integer getRowNo();

    String getRawJson();
}
