package com.docbase.iam.role;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实执行 V1→V4 Flyway 迁移，验证迁移脚本语法正确、可在数据库上成功运行，并
 * 覆盖真实的历史数据升级路径。
 *
 * <p>此前测试仅通过 schema-h2.sql 建表，从未真正执行过 Flyway 迁移，因此 V4 的 SQL
 * 语法错误（`*` 行注释）被漏掉。本测试以 H2（MySQL 模式）为靶，驱动 Flyway 顺序执行
 * V1→V4，断言：
 * <ol>
 *   <li>全部迁移成功完成（无语法/运行时错误）</li>
 *   <li>V4 的 is_system 列存在于 sys_role</li>
 *   <li>V4 的 INSERT IGNORE 补齐了 role_key='admin' 的超级管理员角色</li>
 *   <li>真实历史升级路径：先迁到 V3，按 V3 旧表结构插入引导脚本预置的 system_admin
 *       等角色（此时无 is_system 列），再继续迁到 V4，断言这些同一批历史记录被
 *       V4 的 UPDATE 标记为 is_system=1——绝不手工重写生产迁移 SQL</li>
 * </ol>
 *
 * <p>使用独立的纯净内存库（DB_CLOSE_DELAY=-1 但每次新名），避免污染共享测试库。
 */
class FlywayMigrationValidationTest {

    private DataSource dataSource(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        // 不启用 DATABASE_TO_LOWER：H2 默认把无引号标识符转为大写，与 MySQL 行为一致，
        // 迁移脚本中的 sys_role / is_system 在 information_schema 中即 SYS_ROLE / IS_SYSTEM。
        ds.setUrl("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    private Flyway flyway(DataSource ds, String location) {
        return Flyway.configure()
                .dataSource(ds)
                .locations(location)
                .baselineOnMigrate(true)
                .load();
    }

    @Test
    void V1到V4迁移应全部成功执行() {
        DataSource ds = dataSource("flyway_full");
        MigrateResult result = flyway(ds, "classpath:db/migration").migrate();

        assertTrue(result.success, "V1→V4 迁移应全部成功");
        assertEquals(4, result.migrationsExecuted, "应顺序执行 V1、V2、V3、V4 共 4 个迁移");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // V1 建立了 service_metadata
        Integer metaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_metadata WHERE service_name = 'iam-service'", Integer.class);
        assertNotNull(metaCount);
        assertEquals(1, metaCount);
    }

    @Test
    void V4应添加is_system列并补齐admin角色() {
        DataSource ds = dataSource("flyway_v4");
        flyway(ds, "classpath:db/migration").migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // is_system 列存在
        Integer colCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SYS_ROLE' AND COLUMN_NAME = 'IS_SYSTEM'",
                Integer.class);
        assertNotNull(colCount);
        assertEquals(1, colCount, "V4 should add is_system column to sys_role");

        // V4 的 INSERT IGNORE 补齐 role_key='admin' 的超级管理员角色并标记 is_system=1。
        // 注意：system_admin/knowledge_admin 等由 database/bootstrap/*.sql 引导脚本创建
        // （非 Flyway 迁移），故纯 V1→V4 迁移后仅存在 admin 角色。
        Integer adminIsSystem = jdbc.queryForObject(
                "SELECT is_system FROM sys_role WHERE role_key = 'admin'", Integer.class);
        assertNotNull(adminIsSystem, "role_key=admin should exist after V4 INSERT IGNORE");
        assertEquals(1, adminIsSystem, "role_key=admin should be marked is_system=1 after V4");
    }

    /**
     * 验证真实的历史数据升级路径：
     *
     * <ol>
     *   <li>先用 target=3 把 Flyway 迁到 V3（此时 sys_role 还没有 is_system 列）</li>
     *   <li>模拟 database/bootstrap/*.sql 在 V3 时期已创建的引导角色：system_admin、
     *       knowledge_admin、ingest_admin、ai_chat_admin（按 V3 旧表结构插入，
     *       不写 is_system 列——当时该列不存在）</li>
     *   <li>再把 target 放开到 V4，继续迁移，让生产 V4 脚本的 ALTER + UPDATE + INSERT
     *       作用于这些同一批历史记录</li>
     *   <li>断言这些历史记录全部被 V4 的 UPDATE 标记为 is_system=1，且 admin 被
     *       INSERT IGNORE 补齐</li>
     * </ol>
     *
     * <p>绝不手工复制生产迁移 SQL，升级逻辑完全由 Flyway 驱动真实 V4 脚本完成。
     */
    @Test
    void V4应正确升级V3时期已存在的引导角色() {
        DataSource ds = dataSource("flyway_upgrade");

        // 第一步：迁到 V3（sys_role 尚不含 is_system 列）
        Flyway flywayToV3 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .target("3")
                .load();
        MigrateResult toV3 = flywayToV3.migrate();
        assertTrue(toV3.success);
        assertEquals(3, toV3.migrationsExecuted, "应执行 V1、V2、V3");

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 第二步：按 V3 旧表结构插入引导脚本预置的角色（无 is_system 列），模拟历史数据
        // 这些 role_key 与 database/bootstrap/*.sql 引导脚本创建的一致。
        for (String key : new String[]{"system_admin", "knowledge_admin", "ingest_admin", "ai_chat_admin"}) {
            jdbc.update("INSERT INTO sys_role (role_name, role_key, role_sort, status, remark) VALUES (?, ?, 1, 1, ?)",
                    "bootstrap_" + key, key, "pre-existing role created by bootstrap scripts at V3");
        }
        // 确认此时 sys_role 没有 is_system 列，is_system 列数应为 0
        Integer colBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SYS_ROLE' AND COLUMN_NAME = 'IS_SYSTEM'",
                Integer.class);
        assertEquals(0, colBefore, "V3 时期 sys_role 不应有 is_system 列");

        // 第三步：放开 target 到 V4，继续迁移——让生产 V4 脚本作用于这些历史记录
        Flyway flywayToV4 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("4")
                .load();
        MigrateResult toV4 = flywayToV4.migrate();
        assertTrue(toV4.success, "V4 迁移应成功升级历史数据");
        assertEquals(1, toV4.migrationsExecuted, "本次仅应执行 V4 一个迁移");

        // 第四步：断言这些同一批历史记录被 V4 的 UPDATE 标记为 is_system=1
        for (String key : new String[]{"system_admin", "knowledge_admin", "ingest_admin", "ai_chat_admin"}) {
            Integer isSystem = jdbc.queryForObject(
                    "SELECT is_system FROM sys_role WHERE role_key = ?", Integer.class, key);
            assertNotNull(isSystem, "role_key=" + key + " 应仍存在");
            assertEquals(1, isSystem,
                    "V4 的 UPDATE 应把 V3 时期已存在的 " + key + " 标记为 is_system=1");
        }
        // V4 的 INSERT IGNORE 补齐 admin
        Integer adminIsSystem = jdbc.queryForObject(
                "SELECT is_system FROM sys_role WHERE role_key = 'admin'", Integer.class);
        assertNotNull(adminIsSystem, "role_key=admin 应被 V4 INSERT IGNORE 补齐");
        assertEquals(1, adminIsSystem);
    }

    @Test
    void V4迁移应幂等_重复执行不报错() {
        DataSource ds = dataSource("flyway_idempotent");
        Flyway flyway = flyway(ds, "classpath:db/migration");

        // 首次迁移
        flyway.migrate();
        // 第二次迁移（schema_history 已记录，应无操作但也不报错）
        MigrateResult second = flyway.migrate();
        assertEquals(0, second.migrationsExecuted, "重复迁移不应再执行任何脚本");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer isSystem = jdbc.queryForObject(
                "SELECT is_system FROM sys_role WHERE role_key = 'admin'", Integer.class);
        assertEquals(1, isSystem);
    }
}
