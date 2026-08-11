import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { h, nextTick, type Slots, type VNode } from "vue";

const {
  listUsers,
  createUser,
  updateUser,
  deleteUser,
  changeUserStatus,
  resetPassword,
  getUserRoles,
  confirmMock,
  messages,
  hasPermission
} = vi.hoisted(() => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  updateUser: vi.fn(),
  deleteUser: vi.fn(),
  changeUserStatus: vi.fn(),
  resetPassword: vi.fn(),
  getUserRoles: vi.fn(),
  confirmMock: vi.fn(),
  messages: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() },
  hasPermission: vi.fn()
}));

vi.mock("@/api/system-user", () => ({
  listUsers: (...args: unknown[]) => listUsers(...args),
  createUser: (...args: unknown[]) => createUser(...args),
  updateUser: (...args: unknown[]) => updateUser(...args),
  deleteUser: (...args: unknown[]) => deleteUser(...args),
  changeUserStatus: (...args: unknown[]) => changeUserStatus(...args),
  resetPassword: (...args: unknown[]) => resetPassword(...args),
  getUserRoles: (...args: unknown[]) => getUserRoles(...args)
}));
vi.mock("@/utils/message", () => ({ message: messages }));

// 保留真实 ElementPlus 组件，仅替换 ElMessageBox/ElMessage（避免真实弹窗）
vi.mock("element-plus", async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    ElMessageBox: {
      confirm: (...args: unknown[]) => confirmMock(...args),
      alert: vi.fn(),
      prompt: vi.fn()
    },
    ElMessage: {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn()
    }
  };
});

import ElementPlus from "element-plus";
import UserList from "./index.vue";

/**
 * el-table 在 happy-dom 下无法正确按行分发 default slot 的 row 作用域
 * （实测只渲染一行且 row 绑定错位）。这里用渲染函数 stub 模拟 el-table 的
 * 核心行为：对每条数据行，调用各 el-table-column 的 default slot 并传入
 * { row, $index }，从而保证行级按钮的 row 绑定正确、可测。
 */
const ElTable = {
  name: "ElTable",
  props: { data: { type: Array, default: () => [] } },
  setup(props: { data?: unknown[] }, { slots }: { slots: Slots }) {
    return () => {
      const columns: VNode[] = (slots.default?.() ?? []).filter(Boolean);
      const rows = (props.data as Record<string, unknown>[]).map(
        (row: Record<string, unknown>, i: number) => {
          const tds = columns.map((col: VNode) => {
            const colChildren = col.children as any;
            const defaultSlot =
              typeof colChildren === "function"
                ? colChildren
                : colChildren?.default;
            let content: unknown;
            if (defaultSlot) {
              content = defaultSlot({ row, $index: i });
            } else if (typeof col.props?.prop === "string") {
              content = String(row[col.props.prop] ?? "");
            } else {
              content = [];
            }
            return h("td", { class: "el-table-column-cell" }, content as any);
          });
          return h("tr", { class: "el-table__row", key: i }, tds);
        }
      );
      return h("div", { class: "el-table" }, [h("tbody", {}, rows)]);
    };
  }
};
const ElTableColumn = { name: "ElTableColumn", template: "<div />" };

/** 挂载用户管理页：真实 ElementPlus + el-table stub */
function mountList(options: Record<string, unknown> = {}) {
  return mount(UserList, {
    global: {
      plugins: [ElementPlus],
      stubs: { ElTable, ElTableColumn, ...((options.global as any)?.stubs ?? {}) },
      ...(options.global as any)
    },
    ...options
  });
}

function result(records: any[] = []) {
  return { records, total: records.length, current: 1, size: 10, pages: 1 };
}

async function flush() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

function sampleUser(id: number, status = 1) {
  return {
    userId: id,
    username: `user${id}`,
    nickname: `昵称${id}`,
    email: `user${id}@test.com`,
    phoneNumber: "13800000000",
    sex: 1,
    status,
    remark: "",
    createTime: "2026-01-01 00:00:00",
    updateTime: "2026-01-01 00:00:00"
  };
}

describe("system user manage page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    confirmMock.mockReset().mockResolvedValue(undefined); // 默认确认通过
    hasPermission.mockReturnValue(true);
    listUsers.mockResolvedValue(result([sampleUser(1), sampleUser(2)]));
  });

  it("挂载后应请求用户列表（current=1, size=10）", async () => {
    const wrapper = mountList();
    await flush();
    expect(listUsers).toHaveBeenCalledWith({ current: 1, size: 10, username: undefined });
    expect(wrapper.findAll(".el-table__row")).toHaveLength(2);
  });

  it("无 create 权限时，新建按钮应从 DOM 移除（真实 v-auth 行为）", async () => {
    // 仅授予 list 权限，不授予 create
    const { default: authDirective } = await import("@/directive/permission");
    hasPermission.mockImplementation((code: string) => code === "system:user:list");
    const wrapper = mountList({ global: { directives: { auth: authDirective } } });
    await flush();
    // 新建按钮（需要 create 权限）应被 v-auth 从 DOM 移除
    expect(wrapper.text()).not.toContain("新建用户");
  });

  it("确认取消：confirm reject 时不应调用 deleteUser", async () => {
    confirmMock.mockReset();
    confirmMock.mockRejectedValueOnce(new Error("cancel"));
    const wrapper = mountList();
    await flush();
    const deleteBtn = wrapper.findAll("button").find(b => b.text().includes("删除"));
    expect(deleteBtn).toBeTruthy();
    await deleteBtn!.trigger("click");
    await flush();
    expect(deleteUser).not.toHaveBeenCalled();
    expect(listUsers).toHaveBeenCalledOnce(); // 仅初始加载
  });

  it("删除成功：confirm 通过后调用 deleteUser 并刷新列表", async () => {
    deleteUser.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    const deleteBtn = wrapper.findAll("button").find(b => b.text().includes("删除"));
    await deleteBtn!.trigger("click");
    await flush();
    expect(deleteUser).toHaveBeenCalledWith(1);
    expect(messages.success).toHaveBeenCalledWith("删除成功");
    // 初始加载 + 删除后刷新 = 2 次
    expect(listUsers).toHaveBeenCalledTimes(2);
  });

  it("启停操作：confirm 通过后调用 changeUserStatus（启用→停用）", async () => {
    changeUserStatus.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    // 第一个用户 status=1(启用) → 按钮为"停用"，目标 status=0
    const statusBtn = wrapper.findAll("button").find(b => b.text().includes("停用"));
    expect(statusBtn).toBeTruthy();
    await statusBtn!.trigger("click");
    await flush();
    expect(changeUserStatus).toHaveBeenCalledWith(1, 0);
  });

  it("异步乱序：后发先至的旧响应应被 requestSeq 丢弃", async () => {
    let resolveSlow!: (value: ReturnType<typeof result>) => void;
    let resolveFast!: (value: ReturnType<typeof result>) => void;
    // 第 1 次：初始挂载加载（立即解析）；第 2/3 次：两次查询（延迟解析）
    listUsers
      .mockReset()
      .mockResolvedValueOnce(result([sampleUser(1), sampleUser(2)]))
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            resolveSlow = resolve;
          })
      )
      .mockImplementationOnce(
        () =>
          new Promise(resolve => {
            resolveFast = resolve;
          })
      );

    const wrapper = mountList();
    await flush();

    // 触发两次查询（第二次覆盖第一次，requestSeq 递增）
    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await nextTick();
    await searchBtn!.trigger("click");
    await nextTick();

    // 慢请求（第一次查询）先返回，此时已有 pending 的新意图，
    // 旧响应必须绝不写入（连短暂展示都不允许），仅由 pending 补发
    resolveSlow(result([sampleUser(99)]));
    await flush();

    // 关键断言：旧筛选结果 user99 绝不能出现在页面上
    expect(wrapper.text()).not.toContain("user99");

    // 快请求（第二次查询）后返回，决定最终数据
    resolveFast(result([sampleUser(3)]));
    await flush();

    // 最终列表应为第二次查询的结果（userId=3），而非第一次（userId=99）
    expect(wrapper.findAll(".el-table__row")).toHaveLength(1);
    expect(wrapper.text()).toContain("user3");
    expect(wrapper.text()).not.toContain("user99");
  });

  it("三次连续意图：补发请求飞行期间产生的第三次意图不能丢失，最终只执行最新参数", async () => {
    let resolveSlow!: (v: ReturnType<typeof result>) => void; // 第 1 次查询（意图 1）
    let resolveSlow2!: (v: ReturnType<typeof result>) => void; // 第 2 次查询（pending 补发，承载意图 2）
    let resolveFast!: (v: ReturnType<typeof result>) => void; // 第 3 次查询（意图 3，在补发飞行期间产生）
    // 4 次调用：挂载 + 三次用户意图
    listUsers
      .mockReset()
      .mockResolvedValueOnce(result([sampleUser(1), sampleUser(2)])) // 挂载
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; })) // 意图 1 "first"
      .mockImplementationOnce(() => new Promise(r => { resolveSlow2 = r; })) // pending 补发 "second"
      .mockImplementationOnce(() => new Promise(r => { resolveFast = r; })); // 意图 3 "third"

    const wrapper = mountList();
    await flush();
    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));
    const input = wrapper.find("input");

    // 意图 1：进入飞行
    await input.setValue("first");
    await searchBtn!.trigger("click");
    await nextTick();
    // 意图 2：在意图 1 飞行中触发 → 记录 pending，不发新请求
    await input.setValue("second");
    await searchBtn!.trigger("click");
    await nextTick();

    // 意图 1 返回 → 跳过写入，以 "second" 参数补发（第 3 次调用）
    resolveSlow(result([sampleUser(99)]));
    await flush();
    expect(listUsers).toHaveBeenCalledTimes(3);
    expect(listUsers).toHaveBeenLastCalledWith({ current: 1, size: 10, username: "second" });

    // 意图 3：在补发请求（"second"）飞行期间触发 → 再次记录 pending
    await input.setValue("third");
    await searchBtn!.trigger("click");
    await nextTick();

    // 补发请求（"second"）返回 → 跳过写入（已有 intent 3 的 pending），
    // finishFetch 再次调用 maybeFirePendingRefresh，以 "third" 发起第 4 次调用
    resolveSlow2(result([sampleUser(3)]));
    await flush();
    expect(listUsers).toHaveBeenCalledTimes(4);
    expect(listUsers).toHaveBeenLastCalledWith({ current: 1, size: 10, username: "third" });

    // 意图 3 返回 → 写入最终数据
    resolveFast(result([sampleUser(4)]));
    await flush();

    // 最终结果应为最新一次意图（userId=4）；旧数据 user99 / user3 从未写入
    expect(wrapper.findAll(".el-table__row")).toHaveLength(1);
    expect(wrapper.text()).toContain("user4");
    expect(wrapper.text()).not.toContain("user99");
    expect(wrapper.text()).not.toContain("user3");
  });

  it("卸载后不应补发网络请求（mounted 守卫）", async () => {
    let resolveSlow!: (v: ReturnType<typeof result>) => void;
    listUsers
      .mockReset()
      .mockResolvedValueOnce(result([sampleUser(1), sampleUser(2)])) // 挂载
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; })) // 查询（延迟）
      .mockResolvedValueOnce(result([sampleUser(5)])); // 可能的补发

    const wrapper = mountList();
    await flush();
    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));

    await searchBtn!.trigger("click");
    await nextTick();
    const callsBeforeUnmount = listUsers.mock.calls.length;

    // 卸载组件
    wrapper.unmount();
    // 延迟的请求返回
    resolveSlow(result([sampleUser(99)]));
    await flush();

    // 卸载后不应再发起新的列表请求（补发被 mounted 守卫拦截）
    expect(listUsers.mock.calls.length).toBe(callsBeforeUnmount);
  });

  it("分页变化应触发带新 current 的请求", async () => {
    const wrapper = mountList();
    await flush();
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    await pagination.vm.$emit("current-change", 2);
    await flush();
    expect(listUsers).toHaveBeenLastCalledWith({ current: 2, size: 10, username: undefined });
  });

  it("用户名筛选：输入后点查询应以 username 请求并重置到第一页", async () => {
    const wrapper = mountList();
    await flush();
    const input = wrapper.find("input");
    await input.setValue("alice");
    await flush();
    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await flush();
    expect(listUsers).toHaveBeenLastCalledWith({ current: 1, size: 10, username: "alice" });
  });
});