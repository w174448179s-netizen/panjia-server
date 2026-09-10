# common-import-util · 公共导入工具模块 · 详细设计

> **版本：** V1.0（落地版）
> **编制日期：** 2026 年 9 月
> **定位：** 纯技术工具模块，承担**文件解析、模板解析、原始文件二进制归档、基础格式校验**。位于**工具层**，不属于任何业务域。
> **依赖方：** `panjia-import`（业务单据导入）、`panjia-people`（员工主数据导入）—— 两个业务域均可依赖本模块。
> **核心约束：** **只输出内存 DTO，不落任何业务库表**；不含业务聚合根、不含状态机、不做业务语义校验。
> **读者：** 编码负责人、两个业务域开发者。

---

## 〇、定位：工具层 vs 业务层（一句话区分）

| 层 | 模块 | 负责 | 产出 |
|----|------|------|------|
| **工具层** | `common-import-util` | 把 Excel 变成"内存里的行数据"，把文件存到对象存储 | **内存 DTO**（`ParsedSheet`） |
| **业务层** | `panjia-import` | 单据业务：批次 / RawData / NormalizedRecord / 状态机 / SUPERSEDED | 业务表记录 |
| **业务层** | `panjia-people` | 员工主数据业务：导入批次 / 员工 / salary_fact / 账户 / 变更 | 业务表记录 |

> **关键分界**：工具层**不知道**"这是业绩还是员工"，也**不知道**"员工工号必须唯一"；它只知道"第 3 列叫 `employee_code`、类型是 string、不能为空"。

---

## 一、职责边界（冻结）

### 1.1 本模块**负责**（4 项）

| 能力 | 说明 |
|------|------|
| **文件解析** | 读取 XLSX / XLS / CSV，按表头解析为行数据 |
| **模板解析** | 读取列映射配置（列名 → 字段、类型、必填、枚举、格式） |
| **原始文件归档** | 文件二进制存 MinIO / S3 / 本地，返回 `storagePath` |
| **基础格式校验** | 类型可转（string→numeric/date/bool）、必填非空、枚举合法、长度/正则 |

### 1.2 本模块**不负责**（硬红线）

| 不做 | 归属 |
|------|------|
| 数据库写入（任何业务表） | 业务域 |
| 业务聚合根 / 状态机 / 业务流程编排 | 业务域 |
| **业务语义校验**（工号唯一、门店存在、师傅工号存在、跨月检测） | 业务域 |
| 员工匹配、部门建树、账户创建 | `panjia-people` |
| 归一化（NormalizedRecord）、折算、口径判断 | `panjia-performance` / 业务域 |
| 批次管理、SUPERSEDED、重归一化 | 业务域 |
| 事件发布 / Outbox | 业务域 |

> **CI 校验**：`common-import-util` 模块**禁止出现** `import org.springframework.jdbc`、`@Mapper`、`@Repository`、任何 `pj_*` 表名字符串。

---

## 二、包结构

```
common-import-util/
├── config/          ImportUtilProperties（存储类型、最大行数、临时目录）
├── parser/          ★ 文件解析
│   ├── FileParser.java              接口（SPI，按扩展名路由）
│   ├── XlsxFileParser.java          EasyExcel/POI 实现
│   ├── CsvFileParser.java
│   └── ParserFactory.java
├── template/        ★ 模板解析
│   ├── TemplateResolver.java        接口
│   ├── DefaultTemplateResolver.java 读列定义 → ColumnDef 列表
│   └── model/
│       ├── ImportTemplate.java      模板定义（编码/版本/列定义）
│       └── ColumnDef.java           列定义（colName/field/type/required/enum/format）
├── archive/         ★ 原始文件归档
│   ├── FileArchiver.java            接口
│   ├── MinioFileArchiver.java
│   ├── LocalFileArchiver.java
│   └── ArchiveResult.java           (storagePath, fileHash, size)
├── validate/        ★ 基础格式校验
│   ├── BasicValidator.java          接口
│   ├── DefaultBasicValidator.java   必填/类型/枚举/长度/正则
│   └── FieldError.java              (rowNo, field, rawValue, reason)
├── convert/         类型转换（string → typed）
│   └── TypeConverter.java
├── exception/       ImportUtilException / ParseException / TemplateException
└── dto/             ★ 输出（内存 DTO，无持久化）
    ├── ParsedSheet.java   ★ 主输出
    ├── ParsedRow.java
    └── ParseContext.java
```

---

## 三、核心 API（内存 DTO）

### 3.1 输出 DTO

```java
/** 解析结果（纯内存，不含任何业务语义） */
public class ParsedSheet {
    private String templateCode;          // 使用的模板编码
    private String templateVersion;       // 模板版本（调用方快照冻结用）
    private List<String> headers;         // 原始表头
    private List<ParsedRow> rows;         // 解析后的行
    private List<FieldError> errors;      // 基础格式错误（不阻断，交业务域决策）
    private int totalRows;
    private int errorRows;
}

public class ParsedRow {
    private int rowNo;                                  // 原始行号（1-based，含表头偏移）
    private Map<String, Object> values;                 // field → 类型转换后的值
    private Map<String, String> rawValues;              // field → 原始字符串（审计/回溯）
    private boolean valid;                              // 基础格式是否通过
}
```

> **`rawValues` 保留原始字符串**：业务域落 RawData 时用它存 `raw_json`，保证列映射变更后仍可回溯。

### 3.2 四个核心接口

```java
/** 1. 文件解析 */
public interface FileParser {
    ParsedSheet parse(InputStream in, ImportTemplate template, String originalFilename);
}

/** 2. 模板解析 */
public interface TemplateResolver {
    ImportTemplate resolve(String templateCode);          // 取最新激活版本
    ImportTemplate resolve(String templateCode, String version); // 取指定版本
}

/** 3. 原始文件归档 */
public interface FileArchiver {
    ArchiveResult archive(byte[] content, String originalFilename, String bizDir);
}

/** 4. 基础格式校验 */
public interface BasicValidator {
    List<FieldError> validate(ParsedSheet sheet, ImportTemplate template);
}
```

### 3.3 模板定义（`ColumnDef`）

```java
public class ColumnDef {
    private String colName;      // Excel 表头名（中文）
    private String field;        // 目标字段名
    private String type;         // STRING / INT / DECIMAL / DATE / BOOL
    private boolean required;    // 基础必填（非业务必填）
    private List<String> enumValues;  // 枚举白名单（可空）
    private String pattern;      // 正则
    private Integer maxLength;
    private String dateFormat;   // 如 yyyy-MM-dd
}
```

> 模板数据由**业务域**提供并持久化（import 域存 `pj_import_template`，people 域存 `pj_people_import_template`），工具层只负责**解析与使用**，不负责存储。

---

## 四、调用时序（两个业务域共用）

```
业务域（import / people）
  │
  ├─ 1. FileArchiver.archive(bytes, name, dir)        → storagePath + fileHash
  │
  ├─ 2. TemplateResolver.resolve(templateCode)        → ImportTemplate(列定义)
  │
  ├─ 3. FileParser.parse(in, template, name)          → ParsedSheet（含基础错误）
  │
  ├─ 4. BasicValidator.validate(sheet, template)      → List<FieldError>
  │
  └─ 5. 【业务域接手】自己落业务表、做业务校验、跑状态机
        - 用 row.rawValues 存 raw_json（审计锚点）
        - 用 row.values  做业务加工
        - 用 errors      生成自己的 ImportIssue / 员工导入 issue
```

**示例（people 域调用）：**

```java
// people 域 EmployeeImportServiceImpl
public Long importEmployees(MultipartFile file) {
    // ① 工具层：归档 + 解析 + 基础校验
    ArchiveResult ar = fileArchiver.archive(file.getBytes(), file.getOriginalFilename(), "people");
    ImportTemplate tpl = templateResolver.resolve("EMPLOYEE");
    ParsedSheet sheet = fileParser.parse(file.getInputStream(), tpl, file.getOriginalFilename());
    List<FieldError> errs = basicValidator.validate(sheet, tpl);

    // ② 业务层：建自己的批次 + 落 pj_people_import_raw + 业务校验 + 单一大事务落地
    return doImportInOneTransaction(sheet, errs, ar, tpl);
}
```

---

## 五、与两个业务域的依赖关系

```
                ┌──────────────────────┐
                │  common-import-util  │  ← 工具层（无业务，无 DB）
                └──────────┬───────────┘
                           │ 被依赖（单向）
              ┌────────────┴────────────┐
              ▼                         ▼
      ┌───────────────┐        ┌────────────────┐
      │ panjia-import │        │ panjia-people  │
      │  业务单据      │        │  员工主数据     │
      └───────┬───────┘        └───────┬────────┘
              │ 只读 EmployeeQueryPort  │
              └──────────► people ◄─────┘
```

**依赖铁律：**
- `common-import-util` **不依赖**任何业务域（零反向依赖）；
- 两个业务域**各自独立**实现批次 / RawData / 状态机 / SUPERSEDED，**业务层不复用**；
- 只有**工具层**复用 —— 这是本次重构的核心价值：解析能力一份，业务模型两套。

---

## 六、配置项

```yaml
panjia:
  import-util:
    storage-type: minio        # minio / local
    max-rows: 5000             # 单文件最大行数（防大事务）
    temp-dir: /tmp/panjia-import
    minio:
      endpoint: ${MINIO_ENDPOINT}
      bucket: panjia-import
```

---

## 七、编码任务清单

| 任务 | 内容 | 关键点 |
|------|------|--------|
| Task-U-1 | `ImportTemplate` / `ColumnDef` 模型 + 模板解析器 | 模板由业务域提供，工具层只解析 |
| Task-U-2 | `XlsxFileParser`（EasyExcel）+ `CsvFileParser` | 大文件流式读，防 OOM；保留原始字符串 |
| Task-U-3 | `FileArchiver`（MinIO + Local 双实现） | 返回 storagePath + fileHash；**只存不删** |
| Task-U-4 | `TypeConverter` + `BasicValidator` | 必填/类型/枚举/长度/正则；输出 `FieldError` |
| Task-U-5 | `ParsedSheet` / `ParsedRow` DTO | 含 `rawValues` 供业务域存 `raw_json` |
| Task-U-6 | CI 校验脚本 | 禁止 jdbc / @Mapper / 业务表名出现在工具层 |

---

## 八、架构决策记录（ADR）

| 编号 | 决策 | 结论与理由 |
|------|------|-----------|
| **UTIL-001** | **工具层与业务层分离** | 文件解析/模板解析/归档/基础校验是纯技术能力，与"业绩还是员工"无关；抽出后两个业务域共用一份解析能力，业务模型各自独立 |
| **UTIL-002** | **只输出内存 DTO，不落库** | 落库是业务域事务的一部分；工具层落库会破坏业务事务原子性（员工导入要求单一大原子事务） |
| **UTIL-003** | **不做业务语义校验** | 工号唯一、门店存在等依赖业务上下文与数据库状态；工具层无 DB，做不了也不该做 |
| **UTIL-004** | **业务层不复用，仅工具层复用** | 主数据与单据的状态机、SUPERSEDED 策略语义不同（主数据不可物理删、单据逻辑失效），强行复用会混杂；保护 V1.8 基线 ADR-007/021/022 |
| **UTIL-005** | **保留 `rawValues` 原始字符串** | 列映射变更后仍可回溯原始文件，是审计与重归一化的锚点 |

---

## 九、风险

| 风险 | 说明 | 缓解 |
|------|------|------|
| 大文件 OOM | Excel 全量加载 | 流式解析（EasyExcel）+ `max-rows` 限制 |
| 模板版本漂移 | 解析用 A 版本、业务用 B 版本 | 业务域在**批次创建时**快照冻结 `templateVersion`（两域一致约定） |
| 存储不可用 | MinIO 故障 | 双实现（local 兜底）+ 归档失败即中止导入 |

---

**全文完 · V1.0 落地版**
