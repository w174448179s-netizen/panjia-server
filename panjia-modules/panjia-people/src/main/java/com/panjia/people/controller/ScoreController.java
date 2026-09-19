package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.dto.ScoreQuery;
import com.panjia.people.dto.ScoreVO;
import com.panjia.people.service.ScoreService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
