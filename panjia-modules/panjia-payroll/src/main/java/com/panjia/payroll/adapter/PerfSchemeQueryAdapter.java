package com.panjia.payroll.adapter;

import com.panjia.contracts.dto.PerfSchemeDTO;
import com.panjia.contracts.port.PerfSchemeQueryPort;
import com.panjia.payroll.domain.PerfScheme;
import com.panjia.payroll.mapper.PerfSchemeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 绩效方案查询适配器：方案配置表（pj_payroll_perf_scheme）→ 员工域指派校验。
 * 员工域（panjia-people）保存 PERF_SCHEME 事实前经本端口校验方案存在性与适用岗位。
 */
@Component
@RequiredArgsConstructor
public class PerfSchemeQueryAdapter implements PerfSchemeQueryPort {

    private final PerfSchemeMapper perfSchemeMapper;

    @Override
    public List<PerfSchemeDTO> listSchemes() {
        return perfSchemeMapper.selectList(null).stream()
            .map(this::toDto)
            .toList();
    }

    private PerfSchemeDTO toDto(PerfScheme s) {
        PerfSchemeDTO dto = new PerfSchemeDTO();
        dto.setCode(s.getCode());
        dto.setName(s.getName());
        dto.setSchemeType(s.getSchemeType());
        dto.setApplicableRole(s.getApplicableRole());
        dto.setIsDefault(s.getIsDefault());
        return dto;
    }
}
