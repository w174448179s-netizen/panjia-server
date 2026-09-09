package com.panjia.people.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 岗位选项（岗位多选下拉用）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostOption {

    /** 岗位 ID */
    private Long postId;

    /** 岗位名（= 角色名） */
    private String postName;
}
