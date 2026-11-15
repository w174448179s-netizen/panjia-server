package com.panjia.contracts.dto;

/**
 * IM 账号映射同步结果。
 *
 * @param total     本次同步渠道账号数
 * @param matched   成功匹配本地员工的账号数
 * @param unmatched 未匹配本地员工的账号数
 * @param created   新增映射行数
 * @param updated   更新映射行数
 */
public record ImUserMappingSyncResult(int total, int matched, int unmatched, int created, int updated) {
}
