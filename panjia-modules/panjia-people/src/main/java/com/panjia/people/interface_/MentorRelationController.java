package com.panjia.people.interface_;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.application.MentorRelationService;
import com.panjia.people.application.dto.MentorRelationCreateDTO;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 师徒关系控制器。
 */
@Slf4j
@RestController
@RequestMapping("/people/mentor")
public class MentorRelationController {

    @Autowired
    private MentorRelationService mentorService;

    /**
     * 建立师徒关系。
     *
     * @param dto 师徒关系 DTO
     * @return 操作结果
     */
    @SaCheckPermission("people:mentor:add")
    @PostMapping
    public R<Void> create(@Validated @RequestBody MentorRelationCreateDTO dto) {
        mentorService.createRelation(dto.getMentorId(), dto.getApprenticeId(),
            dto.getIndustryYears(), dto.getRecommendDate());
        return R.ok();
    }

    /**
     * 查询师傅的合格徒弟数量（行业经验 >=2 年且有效），用于招聘奖励 +N%。
     *
     * @param id 师傅员工 ID
     * @return 合格徒弟数
     */
    @SaCheckPermission("people:mentor:list")
    @GetMapping("/qualified-count/{id}")
    public R<Integer> qualifiedCount(@PathVariable Long id) {
        return R.ok(mentorService.countQualifiedApprentices(id));
    }
}
