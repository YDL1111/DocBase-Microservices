package com.docbase.iam.menu;

/**
 * 获取 owner 生命周期互斥锁（sys_menu_owner_mutex）前的回调接缝。
 *
 * <p>生产环境由 {@link NoopOwnerLifecycleLockHook}（no-op）实现；测试通过
 * {@code @MockitoBean} 把该 bean 替换为固定交错逻辑（latch 暂停/放行），从而在
 * 不向生产单例暴露可变 public 钩子的前提下，精确构造"锁前暂停→另一操作提交→
 * 放行"的危险并发时序。
 */
public interface OwnerLifecycleLockHook {

    /** 在 {@code lockGuardRow()} 之前回调。 */
    void beforeLock();
}
