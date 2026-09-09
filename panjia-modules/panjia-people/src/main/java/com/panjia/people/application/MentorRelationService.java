package com.panjia.people.application;

import cn.dev33.satoken.exception.NotLoginException;
import com.panjia.contracts.exception.BizCode;
import com.panjia.people.domain.ChangeLog;
import com.panjia.people.domain.EmployeeChangeTypeEnum;
import com.panjia.people.domain.MentorRelation;
import com.panjia.people.infrastructure.repository.ChangeLogMapper;
import com.panjia.people.infrastructure.repository.EmployeeMapper;
import com.panjia.people.infrastructure.repository.MentorRelationMapper;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 师徒关系服务。
 * <p>
 * 业务规则：
 * <ol>
 *   <li>徒弟行业经验 &gt;= 2 年 → 有招聘奖励资格</li>
 *   <li>师傅最多 +10%（5 个合格徒弟）</li>
 *   <li>徒弟离职 → 关系失效，已发奖励不追回</li>
 * </ol>
 */
@Slf4j
@Service
public class MentorRelationService {

    /** 师傅推荐徒弟数上限 */
    private static final long MENTOR_LIMIT = 5;

    @Autowired
    private MentorRelationMapper mentorMapper;
    @Autowired
    private EmployeeMapper employeeMapper;
    @Autowired
    private ChangeLogMapper changeLogMapper;

    /**
     * 建立师徒关系。
     *
     * @param mentorId       师傅员工 ID
     * @param apprenticeId   徒弟员工 ID
     * @param industryYears  徒弟行业经验年数
     * @param recommendDate  推荐日期
     */
    @Transactional(rollbackFor = Exception.class)
    public void createRelation(Long mentorId, Long apprenticeId,
                               BigDecimal industryYears, LocalDate recommendDate) {
        if (mentorId.equals(apprenticeId)) {
            throw new ServiceException("不能自推荐", BizCode.MENTOR_SELF_REFERENCE);
        }
        if (employeeMapper.selectById(mentorId) == null || employeeMapper.selectById(apprenticeId) == null) {
            throw new ServiceException("师傅或徒弟员工不存在", BizCode.EMPLOYEE_NOT_FOUND);
        }
        if (mentorMapper.existsActiveByApprentice(apprenticeId)) {
            throw new ServiceException("该员工已有有效师傅", BizCode.MENTOR_ALREADY_EXISTS);
        }
        if (mentorMapper.countActiveByMentor(mentorId) >= MENTOR_LIMIT) {
            throw new ServiceException("师傅推荐人数已达上限(5)", BizCode.MENTOR_LIMIT_REACHED);
        }

        MentorRelation relation = new MentorRelation();
        relation.setMentorId(mentorId);
        relation.setApprenticeId(apprenticeId);
        relation.setApprenticeIndustryYears(industryYears);
        relation.setRecommendDate(recommendDate);
        relation.setActive(true);
        relation.setCreatedBy(resolveOperator());
        relation.setCreatedAt(LocalDateTime.now());
        mentorMapper.insert(relation);

        changeLogMapper.insert(ChangeLog.create(apprenticeId, EmployeeChangeTypeEnum.MENTOR_CREATE,
            "mentor", null, String.valueOf(mentorId), "师徒关系绑定", resolveOperator()));
        log.info("师徒关系建立: mentor={}, apprentice={}", mentorId, apprenticeId);
    }

    /**
     * 失效徒弟当前所有有效师徒关系（徒弟离职时调用）。
     *
     * @param apprenticeId 徒弟员工 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deactivateByApprenticeResign(Long apprenticeId) {
        List<MentorRelation> active = mentorMapper.selectActiveByApprentice(apprenticeId);
        for (MentorRelation rel : active) {
            rel.deactivate();
            mentorMapper.updateById(rel);
        }
        if (!active.isEmpty()) {
            changeLogMapper.insert(ChangeLog.create(apprenticeId, EmployeeChangeTypeEnum.MENTOR_DEACTIVATE,
                "mentor", String.valueOf(active.size()), "0", "徒弟离职，师徒关系失效", resolveOperator()));
        }
    }

    /**
     * 查询师傅的合格徒弟数量（行业经验 &gt;= 2 年且在有效期内），用于招聘奖励 +N%。
     *
     * @param mentorId 师傅员工 ID
     * @return 合格徒弟数
     */
    @Transactional(readOnly = true)
    public int countQualifiedApprentices(Long mentorId) {
        return (int) mentorMapper.countQualifiedByMentor(mentorId);
    }

    /**
     * 解析当前操作人登录名，未登录兜底 system。
     *
     * @return 操作人 login_name
     */
    private String resolveOperator() {
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            return loginUser.getUsername();
        } catch (NotLoginException e) {
            return "system";
        }
    }
}
