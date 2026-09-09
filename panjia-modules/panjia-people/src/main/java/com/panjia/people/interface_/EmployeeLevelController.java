package com.panjia.people.interface_;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.application.EmployeeLevelService;
import com.panjia.people.application.dto.EmployeeLevelChangeDTO;
import com.panjia.people.domain.EmployeeLevel;
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

import java.util.List;

/**
 * 职级变更控制器。
 */
@Slf4j
@RestController
@RequestMapping("/people/level")
public class EmployeeLevelController {

    @Autowired
    private EmployeeLevelService levelService;

    /**
     * 职级变更（晋升/降级）。
     *
     * @param dto 职级变更 DTO
     * @return 操作结果
     */
    @SaCheckPermission("people:level:change")
    @PostMapping("/change")
    public R<Void> change(@Validated @RequestBody EmployeeLevelChangeDTO dto) {
        levelService.changeLevel(dto.getEmployeeId(), dto.getNewLevelCode(),
            dto.getEffectiveDate(), dto.getReason());
        return R.ok();
    }

    /**
     * 查询员工职级历史。
     *
     * @param id 员工 ID
     * @return 职级记录列表
     */
    @SaCheckPermission("people:level:list")
    @GetMapping("/history/{id}")
    public R<List<EmployeeLevel>> history(@PathVariable Long id) {
        return R.ok(levelService.getLevelHistory(id));
    }
}
