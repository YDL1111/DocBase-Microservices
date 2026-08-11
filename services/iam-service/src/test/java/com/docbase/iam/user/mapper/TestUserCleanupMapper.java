package com.docbase.iam.user.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 集成测试专用的物理删除能力（绕过 @TableLogic 逻辑删除）。
 *
 * 位于 src/test，不会进入生产镜像。提供两条仅测试需要的破坏性能力：
 * <ul>
 *   <li>{@link #deletePhysicallyById} — 物理删除指定用户行。逻辑删除只把 deleted
 *       置 1、行仍物理存在，唯一索引（如 username）仍会阻止重新插入同名用户，
 *       导致跨测试类复用 H2 时 DuplicateKey。清理残留行时调用。</li>
 *   <li>{@link #deleteAllPhysically} — 物理清空 sys_user 表。并发测试用它保证
 *       "仅存在两个有效管理员"的前提（其它测试遗留的 is_admin=1 用户会扩大有效
 *       管理员集合，使并发停用测试的前提失效）。</li>
 * </ul>
 *
 * 这些方法具有破坏性（可绕过逻辑删除、清空整表），故严禁置于生产 Mapper。
 */
@Mapper
public interface TestUserCleanupMapper {

    @Delete("DELETE FROM sys_user WHERE user_id = #{userId}")
    int deletePhysicallyById(@Param("userId") Long userId);

    @Delete("DELETE FROM sys_user")
    int deleteAllPhysically();
}
