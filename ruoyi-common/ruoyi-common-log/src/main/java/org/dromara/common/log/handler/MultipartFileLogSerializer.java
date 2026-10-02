package org.dromara.common.log.handler;

import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * 操作日志专用 MultipartFile 序列化器。
 * <p>
 * 默认序列化会把 MultipartFile 当普通 Bean 展开（resource/inputStream 等），
 * 其中 {@code MultipartFileResource#getURI()} 对临时上传文件解析 URL 必然抛
 * "cannot be resolved to URL"，导致整条操作日志记录失败。此序列化器只输出
 * 文件摘要（表单字段名/原始文件名/类型/大小/是否空），既保证日志可记录，
 * 又保留导入审计所需的文件名与大小信息，不触碰文件流。
 */
public class MultipartFileLogSerializer extends StdSerializer<MultipartFile> {

    /**
     * 单例实例。
     */
    public static final MultipartFileLogSerializer INSTANCE = new MultipartFileLogSerializer();

    /**
     * 构造序列化器。
     */
    protected MultipartFileLogSerializer() {
        super(MultipartFile.class);
    }

    @Override
    public void serialize(MultipartFile value, JsonGenerator gen, SerializationContext provider) {
        if (value == null) {
            gen.writeNull();
            return;
        }
        gen.writeStartObject();
        gen.writeStringProperty("name", value.getName());
        gen.writeStringProperty("originalFilename", value.getOriginalFilename());
        gen.writeStringProperty("contentType", value.getContentType());
        gen.writeNumberProperty("size", value.getSize());
        gen.writeBooleanProperty("empty", value.isEmpty());
        gen.writeEndObject();
    }
}
