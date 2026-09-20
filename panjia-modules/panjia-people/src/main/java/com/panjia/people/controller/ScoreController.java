package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.dto.ScoreQuery;
import com.panjia.people.dto.ScoreVO;
import com.panjia.people.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 绩效积分明细：积分日报导入同步的月度汇总查询（人事/总监）。
 * <p>
 * 数据写入唯一入口是导入同步（ScoreService.syncScoreSummaries），
 * 无人工增删改端点；期间提交审批后由前端按行级 locked 标记隐藏提交入口。
 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/people/score")
public class ScoreController extends BaseController {

    private final ScoreService scoreService;

    /** 分页查询（人事/总监） */
    @SaCheckPermission("people:score:list")
    @GetMapping("/list")
    public R<PageResult<ScoreVO>> list(ScoreQuery query, PageQuery pageQuery) {
        return R.ok(scoreService.page(query, pageQuery));
    }

    /** 明细查询 */
    @SaCheckPermission("people:score:list")
    @GetMapping("/{id}")
    public R<ScoreVO> getById(@PathVariable Long id) {
        return R.ok(scoreService.getById(id));
    }

    /**
     * 手工新增积分记录（补录/修正）。数据来源 MANUAL，同员工同月份导入同步会覆盖（导入为准）。
     * 请求体：{ employeeId, scoreMonth: "yyyy-MM", totalPoints, attendDays, lateSubmitCount }
     */
    @SaCheckPermission("people:score:add")
    @Log(title = "积分新增", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Void> create(@RequestBody Map<String, Object> body) {
        Long employeeId = parseLong(body.get("employeeId"), "员工");
        YearMonth scoreMonth = parseMonth(body.get("scoreMonth"));
        BigDecimal totalPoints = parseDecimal(body.get("totalPoints"), "总积分");
        Integer attendDays = parseInt(body.get("attendDays"), "出勤天数");
        Integer lateSubmitCount = parseInt(body.get("lateSubmitCount"), "晚提交次数");
        scoreService.create(employeeId, scoreMonth, totalPoints, attendDays, lateSubmitCount);
        return R.ok("已新增积分记录");
    }

    /**
     * 删除积分记录。仅在期间未锁定（未提交审批）时允许删除。
     */
    @SaCheckPermission("people:score:remove")
    @Log(title = "积分删除", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        scoreService.delete(id);
        return R.ok("已删除积分记录");
    }

    /**
     * 修改积分原始事实（总积分/出勤天数/晚提交次数）。
     * 用途：数据修正与晚提交豁免——晚提交处罚 = 次数 × 5 元/次，经总监同意可减免，
     * 由人事调整晚提交次数；平均积分/等级/扣点/扣款随修改实时重算。
     * 仅期间未锁定（未提交审批）时允许修改。
     */
    @SaCheckPermission("people:score:list")
    @Log(title = "积分原始事实修改", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> updateRawFacts(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BigDecimal totalPoints = parseDecimal(body.get("totalPoints"), "总积分");
        Integer attendDays = parseInt(body.get("attendDays"), "出勤天数");
        Integer lateSubmitCount = parseInt(body.get("lateSubmitCount"), "晚提交次数");
        scoreService.updateRawFacts(id, totalPoints, attendDays, lateSubmitCount);
        return R.ok("已修改积分记录");
    }

    private BigDecimal parseDecimal(Object value, String label) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            throw new ServiceException(label + "不能为空");
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(label + "格式不正确：" + value);
        }
    }

    private Long parseLong(Object value, String label) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            throw new ServiceException(label + "不能为空");
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(label + "格式不正确：" + value);
        }
    }

    private YearMonth parseMonth(Object value) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            throw new ServiceException("积分月份不能为空");
        }
        try {
            return YearMonth.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("积分月份格式不正确，应为 yyyy-MM：" + value);
        }
    }

    private Integer parseInt(Object value, String label) {
        if (value == null || StringUtils.isBlank(String.valueOf(value))) {
            throw new ServiceException(label + "不能为空");
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(label + "格式不正确：" + value);
        }
    }
}
