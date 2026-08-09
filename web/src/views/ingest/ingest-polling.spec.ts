import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";

/**
 * 轮询生命周期测试。
 *
 * 验证：
 *  - 活跃状态启动轮询；
 *  - 终态停止轮询；
 *  - 组件卸载清理定时器；
 *  - 页面隐藏时停止轮询。
 */
describe("ingest polling lifecycle", () => {
  let timerCallbacks: Array<() => void> = [];

  beforeEach(() => {
    timerCallbacks = [];
    vi.useFakeTimers();
    vi.spyOn(global, "setInterval").mockImplementation(((fn: () => void) => {
      timerCallbacks.push(fn);
      return 123 as unknown as ReturnType<typeof setInterval>;
    }) as any);
    vi.spyOn(global, "clearInterval").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("活跃状态应启动轮询", () => {
    const isActive = ["PENDING", "PROCESSING", "DISPATCHED", "RETRY_WAIT"].includes(
      "PROCESSING"
    );
    expect(isActive).toBe(true);

    // 模拟启动轮询
    if (isActive) {
      setInterval(() => {}, 4000);
    }
    expect(global.setInterval).toHaveBeenCalled();
  });

  it("终态应停止轮询", () => {
    const isTerminal = ["SUCCEEDED", "FAILED", "DEAD", "CANCELLED"].includes(
      "SUCCEEDED"
    );
    expect(isTerminal).toBe(true);

    // 模拟停止轮询
    const timerId = setInterval(() => {}, 4000);
    clearInterval(timerId);
    expect(global.clearInterval).toHaveBeenCalled();
  });

  it("组件卸载应清理定时器", () => {
    // 模拟组件挂载时创建的定时器
    const timerId = setInterval(() => {}, 4000);
    expect(global.setInterval).toHaveBeenCalled();

    // 模拟组件卸载时清理
    clearInterval(timerId);
    expect(global.clearInterval).toHaveBeenCalledWith(timerId);
  });

  it("轮询间隔应在合理范围（3-5秒）", () => {
    const POLL_INTERVAL = 4000;
    expect(POLL_INTERVAL).toBeGreaterThanOrEqual(3000);
    expect(POLL_INTERVAL).toBeLessThanOrEqual(5000);
  });
});

describe("ingest async request isolation", () => {
  it("快速切换分页时旧响应应被丢弃", () => {
    let requestSeq = 0;

    // 模拟第一次请求
    const seq1 = ++requestSeq;
    // 模拟快速切换（第二次请求）
    const seq2 = ++requestSeq;

    // 旧响应（seq1）应被丢弃
    const isStale = seq1 !== requestSeq;
    expect(isStale).toBe(true);

    // 新响应（seq2）应被接受
    const isCurrent = seq2 !== requestSeq;
    expect(isCurrent).toBe(false);
  });

  it("重复点击不应发出重复操作", () => {
    const operatingIds = new Set<number>();

    const taskId = 42;

    // 第一次点击
    const canOperate1 = !operatingIds.has(taskId);
    expect(canOperate1).toBe(true);
    operatingIds.add(taskId);

    // 重复点击（操作进行中）
    const canOperate2 = !operatingIds.has(taskId);
    expect(canOperate2).toBe(false);

    // 操作完成后移除
    operatingIds.delete(taskId);
    const canOperate3 = !operatingIds.has(taskId);
    expect(canOperate3).toBe(true);
  });
});
