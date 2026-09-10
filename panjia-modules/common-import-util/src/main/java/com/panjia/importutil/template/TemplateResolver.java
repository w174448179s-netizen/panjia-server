package com.panjia.importutil.template;

import com.panjia.importutil.template.model.ImportTemplate;

/**
 * 模板解析 SPI（由业务域实现）。
 * <p>
 * 模板数据由业务域持久化：import 域存 {@code pj_import_template}，
 * people 域存 {@code pj_people_import_template}。工具层只定义契约，不负责存储。
 */
public interface TemplateResolver {

    /**
     * 取指定模板编码的最新激活版本。
     *
     * @param templateCode 模板编码
     * @return 模板定义
     */
    ImportTemplate resolve(String templateCode);

    /**
     * 取指定模板编码的指定版本。
     *
     * @param templateCode 模板编码
     * @param version      模板版本
     * @return 模板定义
     */
    ImportTemplate resolve(String templateCode, String version);
}
