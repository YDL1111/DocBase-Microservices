import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { h, nextTick, reactive, watch, type Slots, type VNode } from "vue";

const {
  listRoles,
  getRole,
  createRole,
  updateRole,
  deleteRole,
  changeRoleStatus,
  getRoleMenuIds,
  assignRoleMenus,
  listMenuTree,
  confirmMock,
  messages,
  hasPermission
} = vi.hoisted(() => ({
  listRoles: vi.fn(),
  getRole: vi.fn(),
  createRole: vi.fn(),
  updateRole: vi.fn(),
  deleteRole: vi.fn(),
  changeRoleStatus: vi.fn(),
  getRoleMenuIds: vi.fn(),
  assignRoleMenus: vi.fn(),
  listMenuTree: vi.fn(),
  confirmMock: vi.fn(),
  messages: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
    confirm: (...args: unknown[]) => confirmMock(...args)
  },
  hasPermission: vi.fn()
}));

vi.mock("@/api/role", () => ({
  listRoles: (...args: unknown[]) => listRoles(...args),
  getRole: (...args: unknown[]) => getRole(...args),
  createRole: (...args: unknown[]) => createRole(...args),
  updateRole: (...args: unknown[]) => updateRole(...args),
  deleteRole: (...args: unknown[]) => deleteRole(...args),
  changeRoleStatus: (...args: unknown[]) => changeRoleStatus(...args),
  getRoleMenuIds: (...args: unknown[]) => getRoleMenuIds(...args),
  assignRoleMenus: (...args: unknown[]) => assignRoleMenus(...args),
  listMenuTree: (...args: unknown[]) => listMenuTree(...args),
  MAX_ROLE_MENUS: 500
}));
vi.mock("@/utils/message", () => ({ message: messages }));

// 保留真实 ElementPlus 组件，仅替换 ElMessage（避免真实弹窗）
vi.mock("element-plus", async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    ElMessage: {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn()
    }
  };
});

import ElementPlus from "element-plus";
import RoleList from "./index.vue";

/**
 * 测试用全局开关：模拟 el-tree 实例始终未挂载/不可用（P0 回归）。
 * 默认 true（树可用）；特定测试置 false 以触发 setCheckedKeys 抛异常。
 */
let treeRefReady = true;

/**
 * el-table 在 happy-dom 下无法正确按行分发 default slot 的 row 作用域。
 * 这里用渲染函数 stub 模拟 el-table 的核心行为：对每条数据行，调用各
 * el-table-column 的 default slot 并传入 { row, $index }，保证行级按钮的
 * row 绑定正确、可测。
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

/**
 * 高保真 el-tree stub，真实复现 Element Plus check-strictly 行为：
 *  - setCheckedKeys(ids) 仅勾选指定节点（不级联到后代）；
 *  - getCheckedKeys() 返回被显式勾选的节点；
 *  - getHalfCheckedKeys()：严格模式下勾选叶子不会让父节点变为半选（indeterminate），
 *    因此返回空数组——与真实 Element Plus 行为一致（页面改为根据 menuTree 显式补齐祖先，
 *    不再依赖此方法）。
 *
 * 这允许我们断言：回显父目录 ID 不会额外选中未授权子菜单（P0-1）。
 */
const ElTree = {
  name: "ElTree",
  props: {
    data: { type: Array, default: () => [] },
    nodeKey: { type: String, default: "menuId" },
    showCheckbox: { type: Boolean, default: false },
    checkStrictly: { type: Boolean, default: false }
  },
  setup(props: any) {
    const state = reactive<{
      checked: Set<number>;
      nodes: Map<number, any>;
      childrenOf: Map<number, number[]>;
      parentOf: Map<number, number>;
    }>({
      checked: new Set(),
      nodes: new Map(),
      childrenOf: new Map(),
      parentOf: new Map()
    });

    function indexTree(nodes: any[], parentId: number | null = null): void {
      for (const n of nodes) {
        state.nodes.set(n[props.nodeKey], n);
        if (parentId !== null) state.parentOf.set(n[props.nodeKey], parentId);
        const childKey = "children";
        const kids = n[childKey] ?? [];
        state.childrenOf.set(n[props.nodeKey], kids.map((c: any) => c[props.nodeKey]));
        if (kids.length) indexTree(kids, n[props.nodeKey]);
      }
    }

    watch(
      () => props.data,
      (d: any[]) => {
        state.nodes.clear();
        state.childrenOf.clear();
        state.parentOf.clear();
        if (d?.length) indexTree(d);
      },
      { immediate: true }
    );

    function setCheckedKeys(ids: number[]): void {
      // 模拟树实例未就绪（refReady=false）：真实场景下 el-tree 初始化失败时调用会异常
      if (!treeRefReady) throw new Error("tree not ready");
      state.checked = new Set(ids ?? []);
    }

    function getCheckedKeys(): number[] {
      return [...state.checked];
    }

    /**
     * 严格模式下，勾选叶子不会产生半选父节点，返回空数组（真实 Element Plus 行为）。
     * 页面的祖先补全由 menuTree 的 parentId 链显式计算，不依赖此方法。
     */
    function getHalfCheckedKeys(): number[] {
      return props.checkStrictly ? [] : deriveHalfChecked();
    }

    // 非严格模式下的半选推导（保留以备非严格场景使用）
    function deriveHalfChecked(): number[] {
      const half: number[] = [];
      for (const [nodeId, kids] of state.childrenOf) {
        if (state.checked.has(nodeId)) continue;
        const checkedDescendant = kids.some((c: number) => state.checked.has(c));
        if (checkedDescendant) half.push(nodeId);
      }
      return half;
    }

    // 返回 render + 方法：Vue 会识别 render 属性作为渲染函数，其余作为暴露绑定
    return {
      render: () => h("div", { class: "el-tree" }),
      setCheckedKeys,
      getCheckedKeys,
      getHalfCheckedKeys
    };
  }
};

/** 挂载角色管理页：真实 ElementPlus + el-table stub */
function mountList(options: Record<string, unknown> = {}) {
  return mount(RoleList, {
    global: {
      plugins: [ElementPlus],
      stubs: { ElTable, ElTableColumn, ElTree, ...((options.global as any)?.stubs ?? {}) },
      ...(options.global as any)
    },
    ...options
  });
}

/** 标准测试用菜单树：1=系统管理(目录) > 2=用户管理、3=角色管理；4=删除用户按钮 */
function menuTreeData() {
  return [
    {
      menuId: 1,
      parentId: 0,
      menuName: "系统管理",
      routerName: "SystemManage",
      menuType: 2,
      isButton: 0,
      children: [
        {
          menuId: 2,
          parentId: 1,
          menuName: "用户管理",
          routerName: "SystemUser",
          menuType: 1,
          isButton: 0,
          children: [
            { menuId: 4, parentId: 2, menuName: "删除用户", routerName: "", menuType: 3, isButton: 1, children: [] }
          ]
        },
        { menuId: 3, parentId: 1, menuName: "角色管理", routerName: "SystemRole", menuType: 1, isButton: 0, children: [] }
      ]
    }
  ];
}

/** 打开菜单授权对话框并等待加载就绪 */
async function openMenuDialogReady(wrapper: ReturnType<typeof mount>, ids = [3]) {
  listMenuTree.mockResolvedValue(menuTreeData());
  getRoleMenuIds.mockResolvedValue(ids);
  const menuBtn = wrapper.findAll("button").find(b => b.text().includes("分配菜单"));
  await menuBtn!.trigger("click");
  await flush();
}

function result(records: any[] = []) {
  return { records, total: records.length, current: 1, size: 10, pages: 1 };
}

async function flush() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

function sampleRole(id: number, status = 1, isSystem = 0) {
  return {
    roleId: id,
    roleName: `role${id}`,
    roleKey: `role_key_${id}`,
    roleSort: id,
    dataScope: 1,
    status,
    isSystem,
    remark: "",
    createTime: "2026-01-01 00:00:00",
    updateTime: "2026-01-01 00:00:00"
  };
}

describe("system role manage page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    confirmMock.mockReset().mockResolvedValue(undefined); // 默认确认通过
    hasPermission.mockReturnValue(true);
    listRoles.mockResolvedValue(result([sampleRole(1), sampleRole(2)]));
  });

  it("挂载后应请求角色列表（current=1, size=10）", async () => {
    const wrapper = mountList();
    await flush();
    expect(listRoles).toHaveBeenCalledWith({ current: 1, size: 10, roleName: undefined });
    expect(wrapper.findAll(".el-table__row")).toHaveLength(2);
  });

  it("无 create 权限时，新建按钮应从 DOM 移除（真实 v-auth 行为）", async () => {
    const { default: authDirective } = await import("@/directive/permission");
    hasPermission.mockImplementation((code: string) => code === "system:role:list");
    const wrapper = mountList({ global: { directives: { auth: authDirective } } });
    await flush();
    // 新建按钮（需要 create 权限）应被 v-auth 从 DOM 移除
    expect(wrapper.text()).not.toContain("新建角色");
  });

  it("确认取消：confirm reject 时不应调用 deleteRole", async () => {
    confirmMock.mockReset();
    confirmMock.mockRejectedValueOnce(new Error("cancel"));
    const wrapper = mountList();
    await flush();
    const deleteBtn = wrapper.findAll("button").find(b => b.text().includes("删除"));
    expect(deleteBtn).toBeTruthy();
    await deleteBtn!.trigger("click");
    await flush();
    expect(deleteRole).not.toHaveBeenCalled();
    expect(listRoles).toHaveBeenCalledOnce(); // 仅初始加载
  });

  it("删除成功：confirm 通过后调用 deleteRole 并刷新列表", async () => {
    deleteRole.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    const deleteBtn = wrapper.findAll("button").find(b => b.text().includes("删除"));
    await deleteBtn!.trigger("click");
    await flush();
    expect(deleteRole).toHaveBeenCalledWith(1);
    expect(messages.success).toHaveBeenCalledWith("删除成功");
    // 初始加载 + 删除后刷新 = 2 次
    expect(listRoles).toHaveBeenCalledTimes(2);
  });

  it("删除双击：confirm 弹出前已上锁，只弹一个确认框", async () => {
    deleteRole.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    const deleteBtn = wrapper.findAll("button").find(b => b.text().includes("删除"));
    expect(deleteBtn).toBeTruthy();
    // 快速双击：第二次点击时 operatingIds 已包含该 roleId，应被拦截
    await deleteBtn!.trigger("click");
    await deleteBtn!.trigger("click");
    await flush();
    // confirm 只被调用一次（第二次点击在弹出 confirm 前被互斥锁拦截）
    expect(confirmMock).toHaveBeenCalledOnce();
  });

  it("启停操作：confirm 通过后调用 changeRoleStatus（启用→停用）", async () => {
    changeRoleStatus.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    // 第一个角色 status=1(启用) → 按钮为"停用"，目标 status=0
    const statusBtn = wrapper.findAll("button").find(b => b.text().includes("停用"));
    expect(statusBtn).toBeTruthy();
    await statusBtn!.trigger("click");
    await flush();
    expect(changeRoleStatus).toHaveBeenCalledWith(1, 0);
  });

  it("异步乱序：后发先至的旧响应应被 requestSeq 丢弃", async () => {
    let resolveSlow!: (value: ReturnType<typeof result>) => void;
    let resolveFast!: (value: ReturnType<typeof result>) => void;
    listRoles
      .mockReset()
      .mockResolvedValueOnce(result([sampleRole(1), sampleRole(2)]))
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

    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await nextTick();
    await searchBtn!.trigger("click");
    await nextTick();

    // 慢请求（第一次查询）先返回，旧响应必须绝不写入
    resolveSlow(result([sampleRole(99)]));
    await flush();
    expect(wrapper.text()).not.toContain("role99");

    // 快请求（第二次查询）后返回，决定最终数据
    resolveFast(result([sampleRole(3)]));
    await flush();

    expect(wrapper.findAll(".el-table__row")).toHaveLength(1);
    expect(wrapper.text()).toContain("role3");
    expect(wrapper.text()).not.toContain("role99");
  });

  it("三次连续意图：补发请求飞行期间产生的第三次意图不能丢失，最终只执行最新参数", async () => {
    let resolveSlow!: (v: ReturnType<typeof result>) => void;
    let resolveSlow2!: (v: ReturnType<typeof result>) => void;
    let resolveFast!: (v: ReturnType<typeof result>) => void;
    listRoles
      .mockReset()
      .mockResolvedValueOnce(result([sampleRole(1), sampleRole(2)]))
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; }))
      .mockImplementationOnce(() => new Promise(r => { resolveSlow2 = r; }))
      .mockImplementationOnce(() => new Promise(r => { resolveFast = r; }));

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
    resolveSlow(result([sampleRole(99)]));
    await flush();
    expect(listRoles).toHaveBeenCalledTimes(3);
    expect(listRoles).toHaveBeenLastCalledWith({ current: 1, size: 10, roleName: "second" });

    // 意图 3：在补发请求（"second"）飞行期间触发 → 再次记录 pending
    await input.setValue("third");
    await searchBtn!.trigger("click");
    await nextTick();

    // 补发请求（"second"）返回 → 跳过写入（已有意图 3 的 pending），
    // finishFetch 再次调用 maybeFirePendingRefresh，以 "third" 发起第 4 次调用
    resolveSlow2(result([sampleRole(3)]));
    await flush();
    expect(listRoles).toHaveBeenCalledTimes(4);
    expect(listRoles).toHaveBeenLastCalledWith({ current: 1, size: 10, roleName: "third" });

    // 意图 3 返回 → 写入最终数据
    resolveFast(result([sampleRole(4)]));
    await flush();

    expect(wrapper.findAll(".el-table__row")).toHaveLength(1);
    expect(wrapper.text()).toContain("role4");
    expect(wrapper.text()).not.toContain("role99");
    expect(wrapper.text()).not.toContain("role3");
  });

  it("卸载后不应补发网络请求（mounted 守卫）", async () => {
    let resolveSlow!: (v: ReturnType<typeof result>) => void;
    listRoles
      .mockReset()
      .mockResolvedValueOnce(result([sampleRole(1), sampleRole(2)]))
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; }))
      .mockResolvedValueOnce(result([sampleRole(5)]));

    const wrapper = mountList();
    await flush();
    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));

    await searchBtn!.trigger("click");
    await nextTick();
    const callsBeforeUnmount = listRoles.mock.calls.length;

    wrapper.unmount();
    resolveSlow(result([sampleRole(99)]));
    await flush();

    // 卸载后不应再发起新的列表请求（补发被 mounted 守卫拦截）
    expect(listRoles.mock.calls.length).toBe(callsBeforeUnmount);
  });

  it("分页变化应触发带新 current 的请求", async () => {
    const wrapper = mountList();
    await flush();
    const pagination = wrapper.findComponent({ name: "ElPagination" });
    await pagination.vm.$emit("current-change", 2);
    await flush();
    expect(listRoles).toHaveBeenLastCalledWith({ current: 2, size: 10, roleName: undefined });
  });

  it("角色名称筛选：输入后点查询应以 roleName 请求并重置到第一页", async () => {
    const wrapper = mountList();
    await flush();
    const input = wrapper.find("input");
    await input.setValue("alice");
    await flush();
    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await flush();
    expect(listRoles).toHaveBeenLastCalledWith({ current: 1, size: 10, roleName: "alice" });
  });

  it("打开菜单授权对话框应并行请求全量菜单树与角色已选菜单", async () => {
    const wrapper = mountList();
    await flush();
    await openMenuDialogReady(wrapper);
    expect(listMenuTree).toHaveBeenCalledOnce();
    expect(getRoleMenuIds).toHaveBeenCalledWith(1);
  });

  it("系统保留角色应显示系统保留标识", async () => {
    listRoles.mockReset();
    listRoles.mockResolvedValue(result([sampleRole(1, 1, 1), sampleRole(2, 1, 0)]));
    const wrapper = mountList();
    await flush();
    expect(wrapper.text()).toContain("系统保留");
  });

  // ---------------------------------------------------------------------------
  // 新增：P0/P1 修复后的 6 类真实组件测试
  // ---------------------------------------------------------------------------

  it("[P0-1] 回显父目录 ID 不会级联选中未授权子菜单（check-strictly）", async () => {
    // 角色仅保存了"系统管理"(menuId=1) 父目录，未授权"删除用户"(menuId=4)
    const wrapper = mountList();
    await flush();
    await openMenuDialogReady(wrapper, [1]);

    const tree = wrapper.findComponent({ name: "ElTree" });
    // 被勾选的只有父目录 1，绝不能包含未授权的子菜单 4（删除用户）
    expect(tree.vm.getCheckedKeys()).toEqual([1]);
    expect(tree.vm.getCheckedKeys()).not.toContain(4);
  });

  it("[P0-2] 加载中点击确定不发请求（按钮禁用）", async () => {
    // 让菜单树请求挂起，模拟加载中
    let resolveTree!: (v: any) => void;
    listMenuTree.mockReset();
    listMenuTree.mockImplementation(() => new Promise(r => { resolveTree = r; }));
    getRoleMenuIds.mockResolvedValue([3]);

    const wrapper = mountList();
    await flush();
    const menuBtn = wrapper.findAll("button").find(b => b.text().includes("分配菜单"));
    await menuBtn!.trigger("click");
    await flush();

    // 加载中：确定按钮应被禁用
    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    expect((confirmBtn?.element as HTMLButtonElement).disabled).toBe(true);

    // 即使强制点击，也不应发出 assignRoleMenus
    await confirmBtn!.trigger("click").catch(() => {});
    expect(assignRoleMenus).not.toHaveBeenCalled();

    // 加载完成后按钮恢复可用（waitFor 处理内部 nextTick）
    resolveTree(menuTreeData());
    await vi.waitFor(() => {
      expect((confirmBtn?.element as HTMLButtonElement).disabled).toBe(false);
    });
  });

  it("[P0-2] 任一加载请求失败后点击确定不发请求", async () => {
    // getRoleMenuIds 失败 → 对话框保持未就绪
    listMenuTree.mockResolvedValue(menuTreeData());
    getRoleMenuIds.mockReset();
    getRoleMenuIds.mockRejectedValue(new Error("403"));

    const wrapper = mountList();
    await flush();
    const menuBtn = wrapper.findAll("button").find(b => b.text().includes("分配菜单"));
    await menuBtn!.trigger("click");
    await flush();

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    // 加载失败 → 确定按钮仍禁用（menuReady 为 false）
    expect((confirmBtn?.element as HTMLButtonElement).disabled).toBe(true);
    await confirmBtn!.trigger("click").catch(() => {});
    expect(assignRoleMenus).not.toHaveBeenCalled();
  });

  it("[menu isolation] A 请求晚于 B 返回时，A 不改变 B 的树与勾选", async () => {
    const wrapper = mountList();
    await flush();

    let resolveA!: (v: any) => void;
    let resolveAIds!: (v: number[]) => void;
    // 第一次打开（角色 A=1）：挂起两个请求
    listMenuTree.mockImplementationOnce(() => new Promise(r => { resolveA = r; }));
    getRoleMenuIds.mockImplementationOnce(() => new Promise(r => { resolveAIds = r; }));
    const menuBtn = wrapper.findAll("button").find(b => b.text().includes("分配菜单"));
    await menuBtn!.trigger("click");
    await nextTick();

    // 立即第二次打开（角色 B=2）：立即解析
    listMenuTree.mockResolvedValueOnce(menuTreeData());
    getRoleMenuIds.mockResolvedValueOnce([3]);
    await menuBtn!.trigger("click");
    await flush();

    // B 已就绪：确定按钮可用（waitFor 等待树挂载 + menuReady 置位）
    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await vi.waitFor(() => {
      expect((confirmBtn?.element as HTMLButtonElement).disabled).toBe(false);
    });

    // A 的迟到响应返回 → 不应改变当前属于 B 的勾选
    resolveA(menuTreeData());
    resolveAIds([4]); // A 勾选了"删除用户"
    await flush();

    const tree = wrapper.findComponent({ name: "ElTree" });
    // 当前目标仍是 B（勾选=[3]），A 的迟到响应绝不写入（seq 已失效）
    await vi.waitFor(() => {
      expect(tree.vm.getCheckedKeys()).toEqual([3]);
    });
    expect(tree.vm.getCheckedKeys()).not.toContain(4);
  });

  it("[P1-2] 卸载后飞行中的列表响应不触发补发也不写状态", async () => {
    let resolveSlow!: (v: ReturnType<typeof result>) => void;
    listRoles
      .mockReset()
      .mockResolvedValueOnce(result([sampleRole(1), sampleRole(2)])) // 挂载
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; })) // 延迟
      .mockResolvedValueOnce(result([sampleRole(5)])); // 可能的补发

    const wrapper = mountList();
    await flush();

    // 触发一次查询后立即卸载
    const searchBtn = wrapper.findAll("button").find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await nextTick();
    const callsBeforeUnmount = listRoles.mock.calls.length;

    wrapper.unmount();

    // 飞行中的响应返回一个不同的数据集
    resolveSlow(result([sampleRole(88)]));
    await flush();

    // 卸载后：补发被 mountedRef 守卫拦截，不会产生新的列表请求
    expect(listRoles.mock.calls.length).toBe(callsBeforeUnmount);
  });

  it("[P1-1] 仅缺 menu:list 时，分配菜单按钮应被移除（三权限交集）", async () => {
    const { default: authDirective } = await import("@/directive/permission");
    // 拥有 role:update 与 role:list，但缺少 menu:list
    hasPermission.mockImplementation(
      (code: string | string[]) => {
        const codes = Array.isArray(code) ? code : [code];
        const held = ["system:role:update", "system:role:list"];
        return codes.every(c => held.includes(c));
      }
    );
    const wrapper = mountList({ global: { directives: { auth: authDirective } } });
    await flush();
    // 分配菜单需要三权限交集，缺少 menu:list → 按钮被移除
    expect(wrapper.text()).not.toContain("分配菜单");
  });

  // ---------------------------------------------------------------------------
  // 新增回归测试（本轮 P0/P1 修复）
  // ---------------------------------------------------------------------------

  it("[P0 regression] 树实例始终未挂载时，确定按钮始终禁用且不调用授权 API", async () => {
    // 模拟 el-tree 初始化失败：setCheckedKeys 抛异常 → menuReady 必须保持 false
    treeRefReady = false;
    try {
      const wrapper = mountList();
      await flush();
      listMenuTree.mockResolvedValue(menuTreeData());
      getRoleMenuIds.mockResolvedValue([3]);
      const menuBtn = wrapper.findAll("button").find(b => b.text().includes("分配菜单"));
      await menuBtn!.trigger("click");
      await flush();

      const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
      // 树未就绪 → 确定按钮必须禁用
      expect((confirmBtn?.element as HTMLButtonElement).disabled).toBe(true);
      // 即使强制点击，也不应发出 assignRoleMenus（避免清空全部菜单）
      await confirmBtn!.trigger("click").catch(() => {});
      expect(assignRoleMenus).not.toHaveBeenCalled();
    } finally {
      treeRefReady = true; // 恢复，避免污染后续测试
    }
  });

  it("[P1 regression] 严格模式下仅勾选叶子，提交结果显式包含全部祖先但不含兄弟", async () => {
    assignRoleMenus.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    // 仅勾选"删除用户"(menuId=4，叶子)，不勾选其兄弟与叔父
    await openMenuDialogReady(wrapper, [4]);

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await vi.waitFor(() => {
      expect((confirmBtn?.element as HTMLButtonElement).disabled).toBe(false);
    });
    await confirmBtn!.trigger("click");
    await flush();

    // 提交结果应包含叶子 4 及其全部祖先 2（用户管理）、1（系统管理），但不含兄弟 3（角色管理）
    expect(assignRoleMenus).toHaveBeenCalledWith(1, { menuIds: expect.arrayContaining([4, 2, 1]) });
    const submitted = (assignRoleMenus as any).mock.calls[0][1].menuIds as number[];
    expect(submitted).not.toContain(3); // 兄弟节点不应出现
    expect(new Set(submitted).size).toBe(submitted.length); // 无重复
  });
});
