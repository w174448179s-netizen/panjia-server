package com.panjia.people.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一键新建店组虚拟人结果。
 */
@Data
public class VirtualEmployeeInitResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 新建虚拟人的店组编码 */
    private List<String> createdCodes = new ArrayList<>();

    /** 已存在而跳过的店组编码 */
    private List<String> skippedCodes = new ArrayList<>();

    /** 编码被其它部门占用而失败的店组编码 */
    private List<String> conflictedCodes = new ArrayList<>();
}
