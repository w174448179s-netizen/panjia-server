package com.panjia.people.service;

import com.panjia.people.domain.ContractTemplate;

import java.util.List;

/**
 * 合同自定义模板服务。
 * <p>
 * 客户可上传 HTML 模板（{{占位符}}），按合同类型存储；未上传则使用系统内置默认模板。
 */
public interface ContractTemplateService {

    /** 查询全部模板配置（含未自定义的类型，标记 useDefault=true） */
    List<ContractTemplateVO> listAll();

    /** 保存/更新某类型的自定义模板 */
    void saveTemplate(String contractType, Long ossId, Long operatorId);

    /** 删除某类型的自定义模板（恢复默认） */
    void deleteTemplate(String contractType);

    /** 模板配置视图 */
    record ContractTemplateVO(
        String contractType,
        String contractTypeLabel,
        boolean useDefault,
        Long ossId,
        String url,
        String originalName
    ) {}
}
