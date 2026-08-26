package com.panjia.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

/**
 * 盘家实体基类
 * <p>
 * 统一使用雪花 ID（IdType.ASSIGN_ID），不暴露自增主键，
 * 为未来多租户演进提供主键无冲突保障。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PanjiaBaseEntity extends BaseEntity {

    /**
     * 主键，雪花 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

}
