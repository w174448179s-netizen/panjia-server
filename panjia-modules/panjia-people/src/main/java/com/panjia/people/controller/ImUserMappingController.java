package com.panjia.people.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.panjia.people.dto.ImUserMappingBindDTO;
import com.panjia.people.dto.ImUserMappingQuery;
import com.panjia.people.dto.ImUserMappingVO;
import com.panjia.people.service.impl.ImUserMappingService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 账号映射管理（渠道无关：钉钉 / 企微 / 飞书）。
 * <p>
 * 映射由渠道同步自动生成（按工号匹配），本页提供查询与人工绑定/解绑，
 * 供钉钉工作通知、待办推送按本地账号定位渠道 userid。
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/people/im-mapping")
public class ImUserMappingController extends BaseController {

    private final ImUserMappingService imUserMappingService;

    /**
     * 分页查询映射列表。
     */
    @SaCheckPermission("people:im:list")
    @GetMapping("/list")
    public R<PageResult<ImUserMappingVO>> list(ImUserMappingQuery query, PageQuery pageQuery) {
        return R.ok(imUserMappingService.pageList(query, pageQuery));
    }

    /**
     * 人工绑定：把渠道账号绑定到指定员工。
     */
    @SaCheckPermission("people:im:bind")
    @Log(title = "IM账号映射", businessType = BusinessType.UPDATE)
    @PutMapping("/{mappingId}/bind")
    public R<Void> bind(@PathVariable Long mappingId, @Validated @RequestBody ImUserMappingBindDTO dto) {
        imUserMappingService.bind(mappingId, dto.getEmployeeId());
        return R.ok("绑定成功");
    }

    /**
     * 解绑：清空员工/账号关联，回到未匹配状态。
     */
    @SaCheckPermission("people:im:bind")
    @Log(title = "IM账号映射", businessType = BusinessType.UPDATE)
    @PutMapping("/{mappingId}/unbind")
    public R<Void> unbind(@PathVariable Long mappingId) {
        imUserMappingService.unbind(mappingId);
        return R.ok("已解绑");
    }
}
