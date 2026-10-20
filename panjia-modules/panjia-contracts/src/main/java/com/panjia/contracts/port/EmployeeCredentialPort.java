package com.panjia.contracts.port;

/**
 * 员工凭据写操作端口（people 域对外契约）。
 * <p>
 * 当前仅用于工资查询密码（独立于登录密码）。后续如需扩展其他凭据写入，也应在本端口追加方法。
 */
public interface EmployeeCredentialPort {

    /**
     * 设置或更新指定员工的工资查询密码。
     * <p>
     * 调用方传入明文密码，由实现方负责 BCrypt hash 后落库。
     *
     * @param employeeId  员工 ID
     * @param rawPassword 明文密码（已由调用方做基础长度/复杂度校验）
     */
    void setSalaryQueryPassword(Long employeeId, String rawPassword);
}
