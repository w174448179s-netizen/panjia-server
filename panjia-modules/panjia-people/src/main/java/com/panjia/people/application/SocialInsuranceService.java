package com.panjia.people.application;

import com.panjia.people.domain.SocialInsuranceProfile;
import com.panjia.people.infrastructure.repository.SocialInsuranceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 社保档案应用服务。
 * <p>
 * 初始档案由 {@link EmployeeService#createEmployee} 建档时按职级模板生成；
 * 本服务提供社保档案查询，供快照取数与档案核对使用。
 */
@Slf4j
@Service
public class SocialInsuranceService {

    @Autowired
    private SocialInsuranceMapper socialInsuranceMapper;

    /**
     * 查询员工当前社保档案（effective_to 为空）。
     *
     * @param employeeId 员工 ID
     * @return 社保档案，无则 null
     */
    @Transactional(readOnly = true)
    public SocialInsuranceProfile getCurrentProfile(Long employeeId) {
        return socialInsuranceMapper.selectByEmployeeId(employeeId);
    }
}
