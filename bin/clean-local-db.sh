#!/bin/bash
# 清理本地 PostgreSQL 数据库（Docker 容器）
# 按 schema 删除所有用户表，保留系统对象
# 用法：./bin/clean-local-db.sh

set -e

DB_CONTAINER="postgres"
DB_USER="root"
DB_NAME="postgres"

echo "⚠️  即将清空本地数据库 $DB_NAME 中所有用户表"
echo "⚠️  此操作不可恢复！"
read -p "确认继续？(y/N): " confirm
if [[ ! "$confirm" =~ ^[Yy]$ ]]; then
    echo "已取消"
    exit 0
fi

echo "正在查询所有用户 schema..."
SCHEMAS=$(docker exec $DB_CONTAINER psql -U $DB_USER -d $DB_NAME -t -c "
SELECT string_agg(schema_name, ' ')
FROM information_schema.schemata
WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
  AND schema_name NOT LIKE 'pg_temp_%'
  AND schema_name NOT LIKE 'pg_toast_temp_%';
")

echo "待清理的 schema: $SCHEMAS"
echo "正在清理..."

docker exec -i $DB_CONTAINER psql -U $DB_USER -d $DB_NAME <<'EOF'
DO $$
DECLARE
    schema_rec RECORD;
    table_rec RECORD;
    seq_rec RECORD;
    view_rec RECORD;
    func_rec RECORD;
    type_rec RECORD;
BEGIN
    -- 遍历所有非系统 schema
    FOR schema_rec IN
        SELECT schema_name
        FROM information_schema.schemata
        WHERE schema_name NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
          AND schema_name NOT LIKE 'pg_temp_%'
          AND schema_name NOT LIKE 'pg_toast_temp_%'
    LOOP
        -- 删除所有表（CASCADE 自动删外键、索引、触发器）
        FOR table_rec IN
            SELECT tablename FROM pg_tables
            WHERE schemaname = schema_rec.schema_name
              AND tableowner = current_user
        LOOP
            EXECUTE format('DROP TABLE IF EXISTS %I.%I CASCADE', schema_rec.schema_name, table_rec.tablename);
        END LOOP;

        -- 删除所有序列
        FOR seq_rec IN
            SELECT sequence_name FROM information_schema.sequences
            WHERE sequence_schema = schema_rec.schema_name
        LOOP
            EXECUTE format('DROP SEQUENCE IF EXISTS %I.%I CASCADE', schema_rec.schema_name, seq_rec.sequence_name);
        END LOOP;

        -- 删除所有视图
        FOR view_rec IN
            SELECT table_name FROM information_schema.views
            WHERE table_schema = schema_rec.schema_name
        LOOP
            EXECUTE format('DROP VIEW IF EXISTS %I.%I CASCADE', schema_rec.schema_name, view_rec.table_name);
        END LOOP;

        -- 删除所有函数
        FOR func_rec IN
            SELECT proname, oidvectortypes(proargtypes) as args
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = schema_rec.schema_name
              AND proowner = current_user::regrole
        LOOP
            BEGIN
                EXECUTE format('DROP FUNCTION IF EXISTS %I.%I(%s) CASCADE',
                    schema_rec.schema_name, func_rec.proname, func_rec.args);
            EXCEPTION WHEN OTHERS THEN
                -- 忽略个别函数删除失败（如扩展函数）
            END;
        END LOOP;

        -- 删除所有自定义类型
        FOR type_rec IN
            SELECT typname FROM pg_type t
            JOIN pg_namespace n ON t.typnamespace = n.oid
            WHERE n.nspname = schema_rec.schema_name
              AND typtype IN ('e', 'c')
              AND typowner = current_user::regrole
        LOOP
            EXECUTE format('DROP TYPE IF EXISTS %I.%I CASCADE', schema_rec.schema_name, type_rec.typname);
        END LOOP;
    END LOOP;
END $$;
EOF

echo ""
echo "✅ 数据库已清理完成"
echo ""
echo "剩余表数量:"
docker exec $DB_CONTAINER psql -U $DB_USER -d $DB_NAME -t -c "
SELECT count(*) FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast');
"
echo ""
echo "下次启动应用时，Flyway 会自动重新创建所有表"
