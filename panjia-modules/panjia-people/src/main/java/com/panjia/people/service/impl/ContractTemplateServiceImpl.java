package com.panjia.people.service.impl;

import com.panjia.people.domain.ContractTemplate;
import com.panjia.people.mapper.ContractTemplateMapper;
import com.panjia.people.service.ContractTemplateService;
import lombok.RequiredArgsConstructor;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.service.ISysOssService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContractTemplateServiceImpl implements ContractTemplateService {

    private static final List<String> ALL_TYPES = List.of("LABOR", "PARTTIME", "OTHER");
    private static final Map<String, String> TYPE_LABELS = Map.of(
        "LABOR", "劳动合同",
        "PARTTIME", "兼职合同",
        "OTHER", "其他协议"
    );

    private final ContractTemplateMapper templateMapper;
    private final ISysOssService ossService;

    @Override
    public List<ContractTemplateVO> listAll() {
        Map<String, ContractTemplate> byType = templateMapper.selectList(null).stream()
            .collect(Collectors.toMap(ContractTemplate::getContractType, Function.identity()));
        return ALL_TYPES.stream().map(type -> {
            ContractTemplate t = byType.get(type);
            return new ContractTemplateVO(
                type,
                TYPE_LABELS.getOrDefault(type, type),
                t == null,
                t != null ? t.getOssId() : null,
                t != null ? t.getUrl() : null,
                t != null ? t.getOriginalName() : null
            );
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveTemplate(String contractType, Long ossId, Long operatorId) {
        if (!ALL_TYPES.contains(contractType)) {
            throw new IllegalArgumentException("非法合同类型：" + contractType);
        }
        SysOssVo oss = ossService.getById(ossId);
        if (oss == null) {
            throw new IllegalArgumentException("OSS 文件不存在，ossId=" + ossId);
        }
        ContractTemplate t = new ContractTemplate();
        t.setContractType(contractType);
        t.setOssId(ossId);
        t.setUrl(oss.getUrl());
        t.setOriginalName(oss.getOriginalName());
        t.setUpdateBy(operatorId);
        t.setUpdateTime(LocalDateTime.now());
        // 主键存在则更新，否则插入
        if (templateMapper.selectById(contractType) != null) {
            templateMapper.updateById(t);
        } else {
            templateMapper.insert(t);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(String contractType) {
        ContractTemplate t = templateMapper.selectById(contractType);
        if (t != null && t.getOssId() != null) {
            ossService.deleteWithValidByIds(List.of(t.getOssId()), true);
        }
        templateMapper.deleteById(contractType);
    }
}
