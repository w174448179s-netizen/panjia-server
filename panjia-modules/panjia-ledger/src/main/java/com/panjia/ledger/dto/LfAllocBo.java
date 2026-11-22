package com.panjia.ledger.dto;

import lombok.Data;

import java.util.List;

/**
 * 费用划归请求：整笔支出划归到一个组。
 */
@Data
public class LfAllocBo {

    private List<Long> ids;

    /** 目标组部门 ID（sys_dept 末级） */
    private Long expenseDeptId;
}
