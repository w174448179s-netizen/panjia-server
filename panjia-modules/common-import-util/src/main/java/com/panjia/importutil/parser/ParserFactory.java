package com.panjia.importutil.parser;

import com.panjia.importutil.dto.ParsedSheet;
import com.panjia.importutil.exception.ImportUtilException;
import com.panjia.importutil.template.model.ImportTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 解析器路由：按文件名扩展名选择 {@link FileParser} 实现。
 */
@Component
public class ParserFactory {

    private final List<FileParser> parsers;

    public ParserFactory(List<FileParser> parsers) {
        this.parsers = parsers;
    }

    /**
     * 解析文件（自动路由 XLSX/XLS/CSV）。
     */
    public ParsedSheet parse(InputStream in, ImportTemplate template, String originalFilename) {
        FileParser parser = parsers.stream()
            .filter(p -> p.supports(originalFilename))
            .findFirst()
            .orElseThrow(() -> new ImportUtilException("不支持的文件类型: " + originalFilename));
        return parser.parse(in, template, originalFilename);
    }
}
