package com.panjia.importutil.parser;

import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.template.model.ImportTemplate;

import java.io.InputStream;

/**
 * 文件解析 SPI（按文件扩展名路由具体实现）。
 */
public interface FileParser {

    /**
     * 是否支持该文件名（按扩展名判断）。
     *
     * @param originalFilename 原始文件名
     * @return true 表示支持
     */
    boolean supports(String originalFilename);

    /**
     * 解析文件为内存 Sheet（含基础类型转换错误，不落库）。
     *
     * @param in               文件输入流
     * @param template         模板定义（列名→字段映射）
     * @param originalFilename 原始文件名
     * @return 解析结果
     */
    ParsedSheet parse(InputStream in, ImportTemplate template, String originalFilename);
}
