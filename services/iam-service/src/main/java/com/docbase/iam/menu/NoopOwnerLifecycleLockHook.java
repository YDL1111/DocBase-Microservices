package com.docbase.iam.menu;

import org.springframework.stereotype.Component;

/**
 * owner 生命周期互斥锁回调的生产默认实现（no-op）。
 *
 * <p>生产环境无需任何锁前回调；仅测试通过 {@code @MockitoBean} 替换本 bean。
 */
@Component
public class NoopOwnerLifecycleLockHook implements OwnerLifecycleLockHook {

    @Override
    public void beforeLock() {
        // 生产环境无操作
    }
}
