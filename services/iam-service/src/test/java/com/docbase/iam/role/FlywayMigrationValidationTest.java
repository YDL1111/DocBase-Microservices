package com.docbase.iam.role;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实执行 V1→V6 Flyway 迁移，验证迁移脚本语法正确、可在数据库上成功运行，并
 * 覆盖真实的历史数据升级路径。
 *
 * <p>此前测试仅通过 schema-h2.sql 建表，从未真正执行过 Flyway 迁移，因此 V4 的 SQL
 * 语法错误（`*` 行注释）被漏掉。本测试以 H2（MySQL 模式）为靶，驱动 Flyway 顺序执行
 * V1→V6，断言：
 * <ol>
 *   <li>全部迁移成功完成（无语法/运行时错误）</li>
 *   <li>V4 的 is_system 列存在于 sys_role</li>
 *   <li>V4 的 INSERT IGNORE 补齐了 role_key='admin' 的超级管理员角色</li>
 *   <li>V5 为 sys_menu 添加 is_system 列</li>
 *   <li>V6 幂等（重复执行不报错）</li>
 *   <li>真实历史升级路径：先迁到 V3，按 V3 旧表结构插入引导脚本预置的 system_admin
 *       等角色（此时无 is_system 列），再继续迁到 V4，断言这些同一批历史记录被
 *       V4 的 UPDATE 标记为 is_system=1——绝不手工重写生产迁移 SQL</li>
 *   <li>菜单历史升级路径：迁到 V4 → 插入旧结构菜单（无 is_system 列）→ 继续 V5/V6，
 *       断言 V5 加列、V6 种子与标记均正确生效</li>
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
    void V1到V13迁移应全部成功执行() {
        DataSource ds = dataSource("flyway_full");
        MigrateResult result = flyway(ds, "classpath:db/migration").migrate();

        assertTrue(result.success, "V1→V13 迁移应全部成功");
        assertEquals(13, result.migrationsExecuted,
                "应顺序执行 V1 到 V13 共 13 个迁移");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // V1 建立了 service_metadata
        Integer metaCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_metadata WHERE service_name = 'iam-service'", Integer.class);
        assertNotNull(metaCount);
        assertEquals(1, metaCount);

        // V5 为 sys_menu 添加了 is_system 列
        Integer menuColCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SYS_MENU' AND COLUMN_NAME = 'IS_SYSTEM'",
                Integer.class);
        assertNotNull(menuColCount);
        assertEquals(1, menuColCount, "V5 should add is_system column to sys_menu");

        // V10 建立了独立的菜单所有者角色归属表 sys_menu_owner_role
        Integer morTables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_MENU_OWNER_ROLE'",
                Integer.class);
        assertNotNull(morTables);
        assertEquals(1, morTables, "V10 should create sys_menu_owner_role table");
        // 主键 (menu_id, role_id) 存在：通过插入重复行应违反唯一约束来间接验证。
        // 先确认表可写（空库回填不产生行）
        // V6/V7/V8/V9 的种子在 sys_role_menu 中建立了关联（如 system_admin 关联系统菜单），
        // V10 回填会把这些历史关联复制到归属表，因此空库迁移后归属表非空。
        // 这保证了升级（以及空库初始化）后，原有角色对其菜单的管理能力不丢失。
        Integer morRows = jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu_owner_role", Integer.class);
        assertNotNull(morRows);
        assertTrue(morRows > 0, "V10 回填应把种子建立的 sys_role_menu 关联复制到归属表（保证管理能力不丢失）");

        // V11 建立了 owner 生命周期互斥锁守卫表，并插入 id=1 守卫行
        Integer mutexTables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_MENU_OWNER_MUTEX'",
                Integer.class);
        assertNotNull(mutexTables);
        assertEquals(1, mutexTables, "V11 should create sys_menu_owner_mutex table");
        Integer guardRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_mutex WHERE id = 1", Integer.class);
        assertNotNull(guardRow);
        assertEquals(1, guardRow, "V11 should insert the id=1 guard row");

        // V12 补齐了"菜单管理"页面与 create/update/delete 按钮种子
        Integer systemMenuPage = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE router_name = 'SystemMenu' AND deleted = 0", Integer.class);
        assertNotNull(systemMenuPage);
        assertEquals(1, systemMenuPage, "V12 应创建 SystemMenu 页面菜单");
        Integer menuCreateBtn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:create' AND is_button = 1 AND deleted = 0", Integer.class);
        assertEquals(1, menuCreateBtn, "V12 应创建 system:menu:create 按钮");
        Integer menuUpdateBtn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:update' AND is_button = 1 AND deleted = 0", Integer.class);
        assertEquals(1, menuUpdateBtn, "V12 应创建 system:menu:update 按钮");
        Integer menuDeleteBtn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:delete' AND is_button = 1 AND deleted = 0", Integer.class);
        assertEquals(1, menuDeleteBtn, "V12 应创建 system:menu:delete 按钮");
        Integer menuListBtn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:list' AND is_button = 1 AND deleted = 0", Integer.class);
        assertEquals(1, menuListBtn, "system:menu:list 按钮（V6 创建）应仍仅 1 个，V12 不重复插入");
        // 新菜单均标记为系统保留
        Integer marked = jdbc.queryForObject(
                "SELECT is_system FROM sys_menu WHERE router_name = 'SystemMenu'", Integer.class);
        assertEquals(1, marked, "SystemMenu 页面应为 is_system=1");
        Integer btnMarked = jdbc.queryForObject(
                "SELECT is_system FROM sys_menu WHERE permission = 'system:menu:create'", Integer.class);
        assertEquals(1, btnMarked, "system:menu:create 按钮应为 is_system=1");
        // system_admin 已关联 SystemMenu 页面
        Integer roleLink = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id "
                        + "JOIN sys_menu m ON m.menu_id = rm.menu_id "
                        + "WHERE r.role_key = 'system_admin' AND m.router_name = 'SystemMenu'", Integer.class);
        assertEquals(1, roleLink, "system_admin 应关联 SystemMenu 页面");
        // 新关联的管理归属已补齐（V12 写入 owner 表）
        Integer ownerLink = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_role mor JOIN sys_menu m ON m.menu_id = mor.menu_id "
                        + "WHERE m.router_name = 'SystemMenu'", Integer.class);
        assertEquals(1, ownerLink, "V12 应把 SystemMenu 的管理归属写入 sys_menu_owner_role");

        // V13 建立组织树、用户组织归属、组织管理菜单与开放注册最小角色。
        Integer organizationTable = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_ORGANIZATION'", Integer.class);
        assertEquals(1, organizationTable, "V13 应建立 sys_organization");
        Integer userOrganizationColumn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SYS_USER' AND COLUMN_NAME = 'ORGANIZATION_ID'",
                Integer.class);
        assertEquals(1, userOrganizationColumn, "V13 应为 sys_user 增加 organization_id");
        Integer seededOrganizations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_organization WHERE organization_code IN ('docbase_hq','docbase_rd','docbase_ops') AND deleted = 0",
                Integer.class);
        assertEquals(3, seededOrganizations, "V13 应预置总部、研发中心和运营中心");
        Integer organizationMenu = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE router_name = 'SystemOrganization' AND deleted = 0", Integer.class);
        assertEquals(1, organizationMenu, "V13 应创建组织管理页面菜单");
        Integer organizationPermissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission IN ('system:org:list','system:org:create','system:org:update','system:org:delete') AND deleted = 0",
                Integer.class);
        assertEquals(5, organizationPermissions,
                "组织管理页面与查看按钮共享 list 权限，V13 应创建页面及四个按钮共五行");
        Integer registeredRole = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE role_key = 'registered_user' AND status = 1 AND deleted = 0",
                Integer.class);
        assertEquals(1, registeredRole, "V13 应创建自助注册固定最小权限角色");
        Integer registeredAdminPermissions = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id "
                        + "JOIN sys_menu m ON m.menu_id = rm.menu_id "
                        + "WHERE r.role_key = 'registered_user' AND (m.permission LIKE 'system:%' OR m.permission = 'admin:all')",
                Integer.class);
        assertEquals(0, registeredAdminPermissions, "自助注册角色不得获得系统管理权限");
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

    /* ========================= V5 / V6 菜单迁移 ========================= */

    @Test
    void V5应添加is_system列到sys_menu() {
        DataSource ds = dataSource("flyway_v5_col");
        flyway(ds, "classpath:db/migration").migrate();

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer colCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SYS_MENU' AND COLUMN_NAME = 'IS_SYSTEM'",
                Integer.class);
        assertNotNull(colCount);
        assertEquals(1, colCount, "V5 should add is_system column to sys_menu");

        // 默认值为 0
        jdbc.update("INSERT INTO sys_menu (menu_name, menu_type, router_name, path, status, remark) VALUES ('plain', 1, 'Plain', '/plain', 1, 'x')");
        Integer defaultVal = jdbc.queryForObject(
                "SELECT is_system FROM sys_menu WHERE router_name = 'Plain'", Integer.class);
        assertNotNull(defaultVal);
        assertEquals(0, defaultVal, "is_system 默认值应为 0");
    }

    /**
     * 验证菜单的真实历史升级路径：
     *
     * <ol>
     *   <li>先用 target=4 把 Flyway 迁到 V4（此时 sys_menu 还没有 is_system 列）</li>
     *   <li>按 V4 旧表结构插入系统管理菜单（无 is_system 列），模拟 050/060 引导脚本
     *       在 V4 时期已创建的 SystemManage/SystemUser/SystemRole 菜单</li>
     *   <li>再放开 target 到 V6，继续迁移——让 V5/V6 脚本作用于这些历史记录</li>
     *   <li>断言 V5 加列后这些历史菜单被 UPDATE 标记为 is_system=1，且 V6 幂等
     *       （已存在同名 router_name 时不重复插入）</li>
     * </ol>
     */
    @Test
    void V5V6应正确升级V4时期已存在的系统菜单() {
        DataSource ds = dataSource("flyway_menu_upgrade");

        // 第一步：迁到 V4（sys_menu 尚不含 is_system 列）
        Flyway flywayToV4 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .target("4")
                .load();
        MigrateResult toV4 = flywayToV4.migrate();
        assertTrue(toV4.success);
        assertEquals(4, toV4.migrationsExecuted, "应执行 V1、V2、V3、V4");

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 确认此时 sys_menu 没有 is_system 列
        Integer colBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SYS_MENU' AND COLUMN_NAME = 'IS_SYSTEM'",
                Integer.class);
        assertEquals(0, colBefore, "V4 时期 sys_menu 不应有 is_system 列");

        // 第二步：按 V4 旧表结构插入系统管理菜单（无 is_system 列），模拟历史数据
        jdbc.update("INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark) VALUES (0, '系统管理', 2, 'SystemManage', '/system', '', 0, 40, 1, 'dir')");
        jdbc.update("INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark) VALUES (0, '用户管理', 1, 'SystemUser', '/system/user', 'system:user:list', 0, 40, 1, 'menu')");
        jdbc.update("INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark) VALUES (0, '角色管理', 1, 'SystemRole', '/system/role', 'system:role:list', 0, 46, 1, 'menu')");

        // 第三步：放开 target 到 V6，继续迁移
        Flyway flywayToV6 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("6")
                .load();
        MigrateResult toV6 = flywayToV6.migrate();
        assertTrue(toV6.success, "V5/V6 迁移应成功升级历史菜单数据");
        assertEquals(2, toV6.migrationsExecuted, "本次应执行 V5、V6 共 2 个迁移");

        // 第四步：断言这些同一批历史记录被 V5 的 UPDATE 标记为 is_system=1
        for (String router : new String[]{"SystemManage", "SystemUser", "SystemRole"}) {
            Integer isSystem = jdbc.queryForObject(
                    "SELECT is_system FROM sys_menu WHERE router_name = ?", Integer.class, router);
            assertNotNull(isSystem, "router_name=" + router + " 应仍存在");
            assertEquals(1, isSystem,
                    "V5 的 UPDATE 应把 V4 时期已存在的 " + router + " 标记为 is_system=1");
        }

        // V6 幂等：已存在同名 router_name 的菜单不应被重复插入（仍各仅 1 行）
        for (String router : new String[]{"SystemManage", "SystemUser", "SystemRole"}) {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_menu WHERE router_name = ?", Integer.class, router);
            assertEquals(1, cnt, "router_name=" + router + " 在 V6 幂等插入后应仍仅 1 行");
        }
        // V6 新增了 system_admin 角色
        Integer adminRole = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role WHERE role_key = 'system_admin'", Integer.class);
        assertEquals(1, adminRole, "V6 应创建 system_admin 角色");
    }

    /**
     * 空库全新初始化路径（V1→V6 顺序执行，无历史数据）：
     * V6 创建 system_admin 角色时应直接写入 is_system=1（INSERT 含 is_system 列），
     * 且 V6 末尾的幂等 UPDATE 不会把它改坏。断言 system_admin.is_system=1。
     *
     * <p>覆盖审查 P0：空库场景下 system_admin 必须是系统保留角色。
     */
    @Test
    void V1到V6空库_system_admin_应为系统保留角色() {
        DataSource ds = dataSource("flyway_fresh_system_admin");
        MigrateResult result = flyway(ds, "classpath:db/migration").migrate();
        assertTrue(result.success, "V1→V6 迁移应全部成功");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer systemAdminIsSystem = jdbc.queryForObject(
                "SELECT is_system FROM sys_role WHERE role_key = 'system_admin'", Integer.class);
        assertNotNull(systemAdminIsSystem, "V6 应创建 system_admin 角色");
        assertEquals(1, systemAdminIsSystem,
                "空库 V1→V6 后 system_admin 应为 is_system=1（V6 INSERT 显式写入）");

        // 业务角色（V7 创建）同样由各自迁移显式标记 is_system=1
        for (String key : new String[]{"knowledge_admin", "ingest_admin", "ai_chat_admin"}) {
            Integer isSystem = jdbc.queryForObject(
                    "SELECT is_system FROM sys_role WHERE role_key = ?", Integer.class, key);
            assertNotNull(isSystem, "V7/V8/V9 应创建 " + key + " 角色");
            assertEquals(1, isSystem, key + " 应为 is_system=1");
        }
    }

    @Test
    void V5V6迁移应幂等_重复执行不报错() {
        DataSource ds = dataSource("flyway_menu_idempotent");
        Flyway f = flyway(ds, "classpath:db/migration");

        f.migrate();
        MigrateResult second = f.migrate();
        assertEquals(0, second.migrationsExecuted, "重复迁移不应再执行任何脚本");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 幂等后 is_system 列仍存在，系统菜单仍标记
        Integer col = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME = 'SYS_MENU' AND COLUMN_NAME = 'IS_SYSTEM'",
                Integer.class);
        assertEquals(1, col);
        Integer marked = jdbc.queryForObject(
                "SELECT is_system FROM sys_menu WHERE router_name = 'SystemManage'", Integer.class);
        assertEquals(1, marked, "幂等后 SystemManage 仍应为 is_system=1");
    }

    /**
     * 验证 V10 的真实历史升级路径（归属表回填）：
     *
     * <ol>
     *   <li>先用 target=9 把 Flyway 迁到 V9（此时还没有 sys_menu_owner_role 表）</li>
     *   <li>模拟升级前已存在的 sys_role_menu 关联（角色 system_admin 关联到某菜单）</li>
     *   <li>再放开 target 到 V10，继续迁移——让 V10 的回填 SQL 作用于这些历史关联</li>
     *   <li>断言这些历史关联被正确回填到 sys_menu_owner_role，
     *       保证升级后普通管理员不会失去对历史菜单的管理能力</li>
     * </ol>
     *
     * <p>空库全新初始化路径下，V10 回填不产生行（无历史 sys_role_menu），同样应成功。
     */
    @Test
    void V10应创建归属表并从历史sys_role_menu回填() {
        DataSource ds = dataSource("flyway_v10_backfill");

        // 第一步：迁到 V9（尚无 sys_menu_owner_role 表）
        Flyway flywayToV9 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .target("9")
                .load();
        MigrateResult toV9 = flywayToV9.migrate();
        assertTrue(toV9.success);
        assertEquals(9, toV9.migrationsExecuted, "应执行 V1–V9 共 9 个迁移");

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 确认 V9 时期还没有归属表
        Integer tableBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_MENU_OWNER_ROLE'",
                Integer.class);
        assertEquals(0, tableBefore, "V9 时期不应有 sys_menu_owner_role 表");

        // 第二步：模拟升级前已存在的 sys_role_menu 关联。
        // V6 创建了 system_admin 角色与 SystemManage 目录的关联。
        // 找出 system_admin 角色和一个未删除菜单，手工补一条 role_menu（模拟历史数据）。
        Long systemAdminRoleId = jdbc.queryForObject(
                "SELECT role_id FROM sys_role WHERE role_key = 'system_admin'", Long.class);
        assertNotNull(systemAdminRoleId, "V6 应创建 system_admin 角色");
        Long someMenuId = jdbc.queryForObject(
                "SELECT menu_id FROM sys_menu WHERE router_name = 'SystemUser' AND deleted = 0", Long.class);
        assertNotNull(someMenuId, "SystemUser 菜单应存在");
        // 若该关联已存在（V6 已建立），先记录存在性；否则插入一条模拟历史关联
        Integer existingLink = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND menu_id = ?",
                Integer.class, systemAdminRoleId, someMenuId);
        if (existingLink == 0) {
            jdbc.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                    systemAdminRoleId, someMenuId);
        }

        // 第三步：放开 target 到 V10，继续迁移
        Flyway flywayToV10 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("10")
                .load();
        MigrateResult toV10 = flywayToV10.migrate();
        assertTrue(toV10.success, "V10 迁移应成功升级历史数据");
        assertEquals(1, toV10.migrationsExecuted, "本次应执行 V10 一个迁移");

        // 第四步：断言归属表已创建，且历史关联已被回填
        Integer tableAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_MENU_OWNER_ROLE'",
                Integer.class);
        assertEquals(1, tableAfter, "V10 应创建 sys_menu_owner_role 表");
        Integer backfilled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_role WHERE role_id = ? AND menu_id = ?",
                Integer.class, systemAdminRoleId, someMenuId);
        assertNotNull(backfilled);
        assertTrue(backfilled >= 1, "V10 回填应把历史 sys_role_menu 关联复制到归属表");
    }

    @Test
    void V10迁移应幂等_重复执行不报错() {
        DataSource ds = dataSource("flyway_v10_idempotent");
        Flyway f = flyway(ds, "classpath:db/migration");

        f.migrate();
        MigrateResult second = f.migrate();
        assertEquals(0, second.migrationsExecuted, "重复迁移不应再执行任何脚本");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        // 幂等后归属表仍存在
        Integer tableCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_MENU_OWNER_ROLE'",
                Integer.class);
        assertEquals(1, tableCount, "幂等后 sys_menu_owner_role 表仍应存在");
    }

    /**
     * 验证 V11 的真实升级路径：
     *
     * <ol>
     *   <li>先用 target=10 把 Flyway 迁到 V10（此时尚无 sys_menu_owner_mutex 表）</li>
     *   <li>再放开 target 到 V11，继续迁移，让生产 V11 脚本创建互斥守卫表并插入
     *       守卫行</li>
     *   <li>断言 V11 建表 + id=1 守卫行均就绪</li>
     * </ol>
     */
    @Test
    void V10到V11升级迁移应创建互斥守卫表() {
        DataSource ds = dataSource("flyway_v11_upgrade");

        // 第一步：迁到 V10（尚无 sys_menu_owner_mutex 表）
        Flyway flywayToV10 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .target("10")
                .load();
        MigrateResult toV10 = flywayToV10.migrate();
        assertTrue(toV10.success);
        assertEquals(10, toV10.migrationsExecuted, "应执行 V1–V10 共 10 个迁移");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer tableBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_MENU_OWNER_MUTEX'",
                Integer.class);
        assertEquals(0, tableBefore, "V10 时期不应有 sys_menu_owner_mutex 表");

        // 第二步：放开 target 到 V11，继续迁移
        Flyway flywayToV11 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("11")
                .load();
        MigrateResult toV11 = flywayToV11.migrate();
        assertTrue(toV11.success, "V11 迁移应成功升级");
        assertEquals(1, toV11.migrationsExecuted, "本次仅应执行 V11 一个迁移");

        Integer tableAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'SYS_MENU_OWNER_MUTEX'",
                Integer.class);
        assertEquals(1, tableAfter, "V11 应创建 sys_menu_owner_mutex 表");
        Integer guardRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_mutex WHERE id = 1", Integer.class);
        assertEquals(1, guardRow, "V11 应插入 id=1 守卫行");
    }

    @Test
    void V11迁移应幂等_重复执行不报错() {
        DataSource ds = dataSource("flyway_v11_idempotent");
        Flyway f = flyway(ds, "classpath:db/migration");

        f.migrate();
        MigrateResult second = f.migrate();
        assertEquals(0, second.migrationsExecuted, "重复迁移不应再执行任何脚本");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer guardRow = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_mutex WHERE id = 1", Integer.class);
        assertEquals(1, guardRow, "幂等后 id=1 守卫行仍应存在");
    }

    /* ========================= V12 SystemMenu 种子迁移 ========================= */

    /**
     * 验证 V12 的真实升级路径与幂等性：
     *
     * <ol>
     *   <li>先用 target=11 把 Flyway 迁到 V11（此时尚无 SystemMenu 页面与
     *       system:menu:create/update/delete 按钮）</li>
     *   <li>模拟升级前已存在的 system:menu:create 按钮（is_system=0，无 system_admin
     *       关联），模拟手工/旧脚本创建的历史数据</li>
     *   <li>再放开 target 到 V12，继续迁移——V12 的 INSERT 守卫跳过已存在的
     *       system:menu:create，其余节点被创建；末尾 UPDATE 把历史按钮标记为 is_system=1</li>
     *   <li>断言：SystemMenu 页面 + 三个新按钮创建、已存在的按钮不重复插入、
     *       is_system 标记生效、system_admin 关联与 owner 归属补齐（仅限四个新节点）</li>
     *   <li>防权限/归属扩张：V12 前从 system_admin 撤销的旧菜单授权（sys_role_menu）
     *       与已转让给其他角色的 owner 在 V12 后保持原状，不被重新写回</li>
     * </ol>
     */
    @Test
    void V11到V12升级迁移应补齐SystemMenu种子且幂等() {
        DataSource ds = dataSource("flyway_v12_upgrade");

        // 第一步：迁到 V11（尚无 SystemMenu）
        Flyway flywayToV11 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .target("11")
                .load();
        MigrateResult toV11 = flywayToV11.migrate();
        assertTrue(toV11.success, "V1–V11 迁移应全部成功");
        assertEquals(11, toV11.migrationsExecuted, "应执行 V1–V11 共 11 个迁移");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer pageBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE router_name = 'SystemMenu'", Integer.class);
        assertEquals(0, pageBefore, "V11 时期不应有 SystemMenu 页面");

        // 第二步：模拟升级前已存在的 system:menu:create 按钮（无 is_system 标记、无关联），
        // 以及一个"被软删除"的 SystemMenu 页面行（应被 V12 重新种子恢复）
        Long systemRoot = jdbc.queryForObject(
                "SELECT menu_id FROM sys_menu WHERE router_name = 'SystemManage' AND menu_type = 2", Long.class);
        assertNotNull(systemRoot, "SystemManage 目录应存在");
        jdbc.update("INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark, is_system) "
                        + "VALUES (?, '新建菜单', 3, '', '', 'system:menu:create', 1, 53, 1, '历史按钮', 0)",
                systemRoot);
        // 软删除的 SystemMenu 行（deleted=1）：V12 的 NOT EXISTS 守卫（deleted=0）不应被它抑制，
        // 应重新插入一条未删除的 SystemMenu 页面
        jdbc.update("INSERT INTO sys_menu (parent_id, menu_name, menu_type, router_name, path, permission, is_button, sort_num, status, remark, is_system, deleted) "
                        + "VALUES (?, '菜单管理', 1, 'SystemMenu', '/system/menu', 'system:menu:list', 0, 52, 1, '已删除旧行', 1, 1)",
                systemRoot);

        // 模拟管理员在 V11 时代的既有授权调整（V12 必须保持，不得恢复）：
        //   a) 从 system_admin 撤销"用户管理"菜单的 sys_role_menu 关联；
        //   b) 把"角色管理"菜单的 owner 转让给 knowledge_admin（replaceOwners 语义：
        //      清空原 owner 行后写入新行）。
        Long systemAdminId = jdbc.queryForObject(
                "SELECT role_id FROM sys_role WHERE role_key = 'system_admin'", Long.class);
        Long systemUserMenuId = jdbc.queryForObject(
                "SELECT menu_id FROM sys_menu WHERE router_name = 'SystemUser'", Long.class);
        Long systemRoleMenuId = jdbc.queryForObject(
                "SELECT menu_id FROM sys_menu WHERE router_name = 'SystemRole'", Long.class);
        Long knowledgeAdminId = jdbc.queryForObject(
                "SELECT role_id FROM sys_role WHERE role_key = 'knowledge_admin'", Long.class);
        assertNotNull(systemAdminId);
        assertNotNull(systemUserMenuId);
        assertNotNull(systemRoleMenuId);
        assertNotNull(knowledgeAdminId, "V7 应创建 knowledge_admin 角色");
        // a) 撤销 system_admin 对 SystemUser 的授权（V6 种子已建立该关联）
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id = ? AND menu_id = ?",
                systemAdminId, systemUserMenuId);
        // b) 把 SystemRole 的 owner 转让给 knowledge_admin
        jdbc.update("DELETE FROM sys_menu_owner_role WHERE menu_id = ?", systemRoleMenuId);
        jdbc.update("INSERT INTO sys_menu_owner_role (menu_id, role_id) VALUES (?, ?)",
                systemRoleMenuId, knowledgeAdminId);

        // 第三步：放开 target 到 V12，继续迁移
        Flyway flywayToV12 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target("12")
                .load();
        MigrateResult toV12 = flywayToV12.migrate();
        assertTrue(toV12.success, "V12 迁移应成功升级历史数据");
        assertEquals(1, toV12.migrationsExecuted, "本次应执行 V12 一个迁移");

        // 第四步：断言种子结果
        // SystemMenu 页面被创建（软删旧行不抑制重新种子：未删除行应恰 1 条）
        Integer pageAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE router_name = 'SystemMenu' AND deleted = 0", Integer.class);
        assertEquals(1, pageAfter, "V12 应重新种子 SystemMenu 页面（软删旧行不抑制）");
        Integer pageDeleted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE router_name = 'SystemMenu' AND deleted = 1", Integer.class);
        assertEquals(1, pageDeleted, "软删旧行应保留（不被 UPDATE 复活）");
        // 已存在的 system:menu:create 按钮不重复插入（仍仅 1 行）
        Integer createCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:create'", Integer.class);
        assertEquals(1, createCount, "V12 不应重复插入已存在的 system:menu:create 按钮");
        // 历史按钮被标记为 is_system=1
        Integer createMarked = jdbc.queryForObject(
                "SELECT is_system FROM sys_menu WHERE permission = 'system:menu:create'", Integer.class);
        assertEquals(1, createMarked, "V12 末尾 UPDATE 应把历史 system:menu:create 标记为 is_system=1");
        // 新按钮 update/delete 被创建
        Integer updateCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:update' AND deleted = 0", Integer.class);
        assertEquals(1, updateCount, "V12 应创建 system:menu:update 按钮");
        Integer deleteCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:delete' AND deleted = 0", Integer.class);
        assertEquals(1, deleteCount, "V12 应创建 system:menu:delete 按钮");
        // system_admin 已关联 SystemMenu 页面与全部四个按钮
        Integer menuLinks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id "
                        + "JOIN sys_menu m ON m.menu_id = rm.menu_id "
                        + "WHERE r.role_key = 'system_admin' AND m.router_name = 'SystemMenu'", Integer.class);
        assertEquals(1, menuLinks, "system_admin 应关联 SystemMenu 页面");
        Integer btnLinks = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON r.role_id = rm.role_id "
                        + "JOIN sys_menu m ON m.menu_id = rm.menu_id "
                        + "WHERE r.role_key = 'system_admin' AND m.permission IN "
                        + "('system:menu:create','system:menu:update','system:menu:delete')", Integer.class);
        assertEquals(3, btnLinks, "system_admin 应关联三个菜单管理按钮");
        // owner 归属补齐：SystemMenu 页面 + 三个新按钮在归属表中有 system_admin 行
        Integer ownerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_role mor JOIN sys_menu m ON m.menu_id = mor.menu_id "
                        + "JOIN sys_role r ON r.role_id = mor.role_id "
                        + "WHERE r.role_key = 'system_admin' AND (m.router_name = 'SystemMenu' "
                        + "OR m.permission IN ('system:menu:create','system:menu:update','system:menu:delete'))",
                Integer.class);
        assertEquals(4, ownerCount, "V12 应补齐 SystemMenu 页面与三个按钮的管理归属");

        // 防权限/归属扩张：V12 不得恢复已撤销的授权与已转让的 owner
        Integer revokedLink = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ? AND menu_id = ?",
                Integer.class, systemAdminId, systemUserMenuId);
        assertEquals(0, revokedLink, "V12 不得恢复已从 system_admin 撤销的 SystemUser 授权");
        Integer transferredOwner = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_role WHERE menu_id = ? AND role_id = ?",
                Integer.class, systemRoleMenuId, knowledgeAdminId);
        assertEquals(1, transferredOwner, "转让后的 owner（knowledge_admin）应保持不变");
        Integer sysAdminReAdded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu_owner_role WHERE menu_id = ? AND role_id = ?",
                Integer.class, systemRoleMenuId, systemAdminId);
        assertEquals(0, sysAdminReAdded, "V12 不得把 system_admin 重新写回已转让的 SystemRole owner");
    }

    @Test
    void V12迁移应幂等_重复执行不报错() {
        DataSource ds = dataSource("flyway_v12_idempotent");
        Flyway f = flyway(ds, "classpath:db/migration");

        f.migrate();
        MigrateResult second = f.migrate();
        assertEquals(0, second.migrationsExecuted, "重复迁移不应再执行任何脚本");

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Integer pageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE router_name = 'SystemMenu'", Integer.class);
        assertEquals(1, pageCount, "幂等后 SystemMenu 页面仍应仅 1 行");
        Integer createCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_menu WHERE permission = 'system:menu:create'", Integer.class);
        assertEquals(1, createCount, "幂等后 system:menu:create 按钮仍应仅 1 行");
        Integer marked = jdbc.queryForObject(
                "SELECT is_system FROM sys_menu WHERE router_name = 'SystemMenu'", Integer.class);
        assertEquals(1, marked, "幂等后 SystemMenu 仍应为 is_system=1");
    }
}
