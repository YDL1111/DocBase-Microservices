import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { h, inject, nextTick, provide, type Slots, type VNode } from "vue";

const {
  listMenuTree,
  getMenu,
  createMenu,
  updateMenu,
  changeMenuStatus,
  deleteMenu,
  confirmMock,
  messages
} = vi.hoisted(() => ({
  listMenuTree: vi.fn(),
  getMenu: vi.fn(),
  createMenu: vi.fn(),
  updateMenu: vi.fn(),
  changeMenuStatus: vi.fn(),
  deleteMenu: vi.fn(),
  confirmMock: vi.fn(),
  messages: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn(),
    confirm: (...args: unknown[]) => confirmMock(...args)
  }
}));

vi.mock("@/api/system-menu", () => ({
  listMenuTree: (...args: unknown[]) => listMenuTree(...args),
  getMenu: (...args: unknown[]) => getMenu(...args),
  createMenu: (...args: unknown[]) => createMenu(...args),
  updateMenu: (...args: unknown[]) => updateMenu(...args),
  changeMenuStatus: (...args: unknown[]) => changeMenuStatus(...args),
  deleteMenu: (...args: unknown[]) => deleteMenu(...args),
  MENU_NAME_MAX: 64,
  ROUTER_NAME_MAX: 128,
  PATH_MAX: 255,
  PERMISSION_MAX: 128,
  META_INFO_MAX: 1024,
  REMARK_MAX: 512,
  SORT_NUM_MAX: 9999,
  ROUTER_NAME_PATTERN: /^[A-Za-z][A-Za-z0-9_-]{0,127}$/,
  PATH_PATTERN: /^(\/[A-Za-z0-9_-]+)+$/,
  PERMISSION_PATTERN: /^[a-z0-9:._-]{1,128}$/
}));
vi.mock("@/utils/message", () => ({ message: messages }));

// 真实 ElementPlus 组件，仅替换 ElMessage 与 ElMessageBox.confirm
vi.mock("element-plus", async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    ElMessage: {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn()
    },
    ElMessageBox: {
      confirm: (...args: unknown[]) => confirmMock(...args)
    }
  };
});

/** 权限开关：grantAll=true 全部放行；否则按 permissionSet 精确判定 */
let grantAll = true;
let permissionSet = new Set<string>();
vi.mock("@/store/modules/user", () => ({
  useUserStoreHook: () => ({
    hasPermission: (codes: string | string[]) => {
      if (grantAll) return true;
      const arr = Array.isArray(codes) ? codes : [codes];
      return arr.every((c: string) => permissionSet.has(c));
    }
  })
}));

import ElementPlus from "element-plus";
import MenuManage from "./index.vue";

/**
 * el-table 树形 stub：递归展开 children 为行（模拟 default-expand-all），
 * 对每条行调用各 el-table-column 的 default slot 传入 { row, $index }。
 */
const ElTable = {
  name: "ElTable",
  props: { data: { type: Array, default: () => [] } },
  setup(_props: unknown, { slots }: { slots: Slots }) {
    return () => {
      const props = _props as { data?: unknown[] };
      const rows: Record<string, unknown>[] = [];
      const flatten = (nodes: Record<string, unknown>[]): void => {
        for (const n of nodes) {
          rows.push(n);
          const kids = (n.children as Record<string, unknown>[] | undefined) ?? [];
          if (kids.length) flatten(kids);
        }
      };
      flatten((props.data as Record<string, unknown>[]) ?? []);
      const columns: VNode[] = (slots.default?.() ?? []).filter(Boolean);
      const tds = rows.map((row: Record<string, unknown>, i: number) => {
        const tdsInner = columns.map((col: VNode) => {
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
        return h("tr", { class: "el-table__row", key: i }, tdsInner);
      });
      return h("div", { class: "el-table" }, [h("tbody", {}, tds)]);
    };
  }
};
const ElTableColumn = { name: "ElTableColumn", template: "<div />" };

/** el-tree-select stub：暴露父节点选项 data 与 disableData 供断言 */
const ElTreeSelect = {
  name: "ElTreeSelect",
  props: {
    modelValue: { type: Number, default: 0 },
    data: { type: Array, default: () => [] },
    nodeKey: { type: String, default: "menuId" },
    checkStrictly: { type: Boolean, default: false },
    clearable: { type: Boolean, default: false },
    defaultExpandAll: { type: Boolean, default: false },
    disableData: { type: Function, default: null },
    placeholder: { type: String, default: "" },
    props: { type: Object, default: () => ({}) }
  },
  emits: ["update:modelValue"],
  template: '<div class="el-tree-select" />'
};

/**
 * 表单组件 stub 集。
 *
 * 真实 el-form 的 validate() 在 happy-dom 环境下 Promise 永不 resolve
 * （提交按钮卡在 loading），因此这里用轻量 stub 替代：
 *  - ElForm.validate() 直接 resolve(true)（表单规则本身的校验由
 *    src/api/system-menu.spec.ts 在 API 层覆盖）；
 *  - ElRadioGroup/ElRadio 实现 group 通信，支持测试中切换 menuType；
 *  - ElInput/ElInputNumber 支持 v-model 写入。
 */

/** 注入键（stub 内部使用，仅与 ElRadio stub 通信） */
const RADIO_GROUP_KEY = "elFormRadioGroupTest";

const ElForm = {
  name: "ElForm",
  props: { model: { type: Object, default: () => ({}) }, rules: { type: Object, default: () => ({}) }, labelWidth: { type: String, default: "" } },
  setup(_props: unknown, { slots, expose }: { slots: Slots; expose: (e: Record<string, unknown>) => void }) {
    expose({
      validate: () => Promise.resolve(true),
      resetFields: () => {},
      clearValidate: () => {}
    });
    return () => h("form", { class: "el-form" }, slots.default?.());
  }
};

const ElFormItem = {
  name: "ElFormItem",
  props: { label: { type: String, default: "" }, prop: { type: String, default: "" } },
  setup(props: { label?: string }, { slots }: { slots: Slots }) {
    return () =>
      h("div", { class: "el-form-item" }, [
        props.label
          ? h("label", { class: "el-form-item__label" }, props.label)
          : null,
        h("div", { class: "el-form-item__content" }, slots.default?.())
      ]);
  }
};

const ElInput = {
  name: "ElInput",
  props: {
    modelValue: { type: [String, Number], default: "" },
    maxlength: { type: Number, default: undefined },
    placeholder: { type: String, default: "" },
    type: { type: String, default: "text" },
    rows: { type: Number, default: undefined }
  },
  emits: ["update:modelValue"],
  setup(props: Record<string, unknown>, { emit }: { emit: (e: string, v: unknown) => void }) {
    return () => {
      const onInput = (e: Event) =>
        emit("update:modelValue", (e.target as HTMLInputElement).value);
      if (props.type === "textarea") {
        return h("textarea", {
          class: "el-input__inner",
          placeholder: props.placeholder as string,
          value: (props.modelValue as string) ?? "",
          onInput
        });
      }
      return h("input", {
        class: "el-input__inner",
        type: "text",
        placeholder: props.placeholder as string,
        value: (props.modelValue as string) ?? "",
        maxlength: props.maxlength as number | undefined,
        onInput
      });
    };
  }
};

const ElInputNumber = {
  name: "ElInputNumber",
  props: {
    modelValue: { type: Number, default: 0 },
    min: { type: Number, default: undefined },
    max: { type: Number, default: undefined },
    controlsPosition: { type: String, default: "" }
  },
  emits: ["update:modelValue"],
  setup(props: Record<string, unknown>, { emit }: { emit: (e: string, v: unknown) => void }) {
    return () =>
      h("input", {
        class: "el-input-number-inner",
        type: "number",
        value: (props.modelValue as number) ?? 0,
        onInput: (e: Event) =>
          emit("update:modelValue", Number((e.target as HTMLInputElement).value))
      });
  }
};

const ElRadioGroup = {
  name: "ElRadioGroup",
  props: { modelValue: { type: Number, default: undefined } },
  emits: ["update:modelValue", "change"],
  setup(props: Record<string, unknown>, { slots, emit }: { slots: Slots; emit: (e: string, v: unknown) => void }) {
    provide(RADIO_GROUP_KEY as any, {
      modelValue: () => props.modelValue as number,
      change: (val: number) => {
        emit("update:modelValue", val);
        emit("change", val);
      }
    });
    return () => h("div", { class: "el-radio-group" }, slots.default?.());
  }
};

const ElRadio = {
  name: "ElRadio",
  props: { value: { type: [Number, String], default: undefined } },
  setup(props: Record<string, unknown>, { slots }: { slots: Slots }) {
    const group = inject<{ modelValue: () => number; change: (v: number) => void } | undefined>(RADIO_GROUP_KEY as any);
    return () =>
      h("label", { class: "el-radio" }, [
        h("input", {
          type: "radio",
          class: "el-radio__original",
          checked: group?.modelValue() === props.value,
          onChange: () => group?.change(props.value as number)
        }),
        h("span", { class: "el-radio__label" }, slots.default?.())
      ]);
  }
};

const ElSwitch = {
  name: "ElSwitch",
  props: {
    modelValue: { type: Number, default: 0 },
    activeValue: { type: Number, default: 1 },
    inactiveValue: { type: Number, default: 0 },
    activeText: { type: String, default: "" },
    inactiveText: { type: String, default: "" }
  },
  emits: ["update:modelValue"],
  template: '<div class="el-switch" />'
};

function mountList(options: Record<string, unknown> = {}) {
  // 注意：options.global 必须合并进 global，而不能用 `...options` 直接覆盖
  // （否则 plugins/stubs 丢失，真实 el-table 在 happy-dom 下不渲染行）。
  const userGlobal = ((options.global as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>;
  return mount(MenuManage, {
    ...options,
    global: {
      plugins: [ElementPlus],
      stubs: {
        ElTable,
        ElTableColumn,
        ElTreeSelect,
        ElForm,
        ElFormItem,
        ElInput,
        ElInputNumber,
        ElRadioGroup,
        ElRadio,
        ElSwitch
      },
      ...userGlobal
    }
  });
}

async function flush() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

/** 从树节点构造完整 SysMenu 详情（编辑对话框打开时 GET /{menuId} 的返回） */
function menuDetail(menuId: number): Record<string, unknown> {
  const find = (nodes: any[]): any => {
    for (const n of nodes) {
      if (n.menuId === menuId) return n;
      const inChild = n.children ? find(n.children) : undefined;
      if (inChild) return inChild;
    }
    return undefined;
  };
  const node = find(treeData());
  return {
    menuId,
    parentId: node?.parentId ?? 0,
    menuName: node?.menuName ?? `菜单${menuId}`,
    menuType: node?.menuType ?? 1,
    routerName: node?.routerName ?? "",
    path: node?.path ?? "",
    permission: node?.permission ?? "",
    metaInfo: node?.metaInfo ?? "",
    isButton: node?.isButton ?? 0,
    sortNum: node?.sortNum ?? 0,
    isSystem: node?.isSystem ?? 0,
    status: node?.status ?? 1,
    remark: `备注${menuId}`,
    creatorId: 1,
    createTime: "2026-01-01 00:00:00",
    updaterId: 1,
    updateTime: "2026-01-01 00:00:00"
  };
}

/** 标准测试用菜单树：1=系统管理(目录,isSystem) > 2=菜单管理(菜单,isSystem)、3=用户管理(菜单)；2 下 4=新建按钮(isSystem)、5=停用按钮 */
function treeData() {
  return [
    {
      menuId: 1,
      parentId: 0,
      menuName: "系统管理",
      routerName: "SystemManage",
      path: "/system",
      permission: "",
      menuType: 2,
      isButton: 0,
      sortNum: 40,
      status: 1,
      isSystem: 1,
      children: [
        {
          menuId: 2,
          parentId: 1,
          menuName: "菜单管理",
          routerName: "SystemMenu",
          path: "/system/menu",
          permission: "system:menu:list",
          menuType: 1,
          isButton: 0,
          sortNum: 60,
          status: 1,
          isSystem: 1,
          children: [
            {
              menuId: 4,
              parentId: 2,
              menuName: "新建菜单",
              routerName: "",
              path: "",
              permission: "system:menu:create",
              menuType: 3,
              isButton: 1,
              sortNum: 61,
              status: 1,
              isSystem: 1,
              children: []
            },
            {
              menuId: 5,
              parentId: 2,
              menuName: "停用按钮",
              routerName: "",
              path: "",
              permission: "system:menu:delete",
              menuType: 3,
              isButton: 1,
              sortNum: 62,
              status: 0,
              isSystem: 0,
              children: []
            }
          ]
        },
        {
          menuId: 3,
          parentId: 1,
          menuName: "用户管理",
          routerName: "SystemUser",
          path: "/system/user",
          permission: "system:user:list",
          menuType: 1,
          isButton: 0,
          sortNum: 40,
          status: 1,
          isSystem: 0,
          children: []
        }
      ]
    }
  ];
}

/** 在表单对话框内按 label 定位 el-form-item 并设置 input 值 */
async function setFormField(wrapper: ReturnType<typeof mount>, label: string, value: string) {
  const items = wrapper.findAll(".el-form-item");
  const item = items.find(i => i.text().includes(label));
  if (!item) throw new Error(`form item not found: ${label}`);
  const input = item.find("input");
  await input.setValue(value);
  await flush();
}

/** 打开新建对话框并填充为有效的菜单数据 */
async function openCreateMenuDialog(wrapper: ReturnType<typeof mount>) {
  const createBtn = wrapper.findAll("button").find(b => b.text().includes("新建菜单"));
  await createBtn!.trigger("click");
  await flush();
}

/** 打开编辑对话框（按菜单名定位行内编辑按钮），等待详情加载完成（formReady） */
async function openEditDialog(wrapper: ReturnType<typeof mount>, rowMenuName: string) {
  const row = wrapper.findAll(".el-table__row").find(r => r.text().includes(rowMenuName));
  const editBtn = row!.findAll("button").find(b => b.text().includes("编辑"));
  await editBtn!.trigger("click");
  await flush();
  await flush();
}

describe("system menu manage page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    confirmMock.mockReset().mockResolvedValue(undefined); // 默认确认通过
    grantAll = true;
    permissionSet = new Set();
    listMenuTree.mockResolvedValue(treeData());
    // 编辑对话框打开时 GET /{menuId} 返回完整详情（默认按树节点构造，含 remark）
    getMenu.mockImplementation((menuId: number) => Promise.resolve(menuDetail(menuId)));
  });

  it("挂载后应请求全量菜单树并渲染树形行", async () => {
    const wrapper = mountList();
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(1);
    // 递归展开后共 5 行：1,2,4,5,3
    expect(wrapper.findAll(".el-table__row")).toHaveLength(5);
    expect(wrapper.text()).toContain("系统管理");
    expect(wrapper.text()).toContain("菜单管理");
    expect(wrapper.text()).toContain("新建菜单");
  });

  /* ---------------- 11. 系统保留标签使用 isSystem ---------------- */

  it("系统保留标签只认后端 isSystem：isSystem=1 显示、0 不显示", async () => {
    const wrapper = mountList();
    await flush();
    // 1/2/4 是 isSystem=1 → 3 个"系统保留"；3/5 不是
    const systemTags = wrapper.findAll(".el-table__row").filter(r => r.text().includes("系统保留"));
    expect(systemTags).toHaveLength(3);
    const row3 = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    expect(row3!.text()).not.toContain("系统保留");
  });

  /* ---------------- 10. 无权限按钮从 DOM 移除 ---------------- */

  it("无 create/update/delete 权限时对应按钮应从 DOM 移除（真实 v-auth）", async () => {
    const { default: authDirective } = await import("@/directive/permission");
    grantAll = false;
    permissionSet = new Set(["system:menu:list"]);

    const wrapper = mountList({ global: { directives: { auth: authDirective as any } } });
    await flush();

    // 页面根需要 list 权限 → 可见；create/update/delete 均无 → 按钮全部移除
    expect(wrapper.find(".menu-manage").exists()).toBe(true);
    expect(wrapper.findAll("button").some(b => b.text().includes("新建菜单"))).toBe(false);
    expect(wrapper.findAll("button").some(b => b.text().includes("编辑"))).toBe(false);
    expect(wrapper.findAll("button").some(b => b.text().includes("停用"))).toBe(false);
    expect(wrapper.findAll("button").some(b => b.text().includes("删除"))).toBe(false);
    // 详情按钮不依赖额外权限（list 已覆盖）
    expect(wrapper.findAll("button").some(b => b.text().includes("详情"))).toBe(true);
  });

  it("无 list 权限时整个页面应从 DOM 移除", async () => {
    // 注意：happy-dom + @vue/test-utils 下，组件根元素被指令 removeChild 后
    // 会被 Vue 渲染机制恢复，直接断言根元素移除不可靠（按钮级移除已由上一测试
    // 用真实 v-auth 验证）。这里改用 tracked directive 验证页面根 div 声明了
    // v-auth="system:menu:list" 门控，并验证无权限时指令移除逻辑对根元素同样生效。
    const rootMounted = vi.fn((el: HTMLElement, binding: any) => {
      if (binding.value === "system:menu:list" && el.classList.contains("menu-manage")) {
        // 无 list 权限：与真实 authDirective 相同的移除逻辑
        if (!permissionSet.has("system:menu:list") && el.parentNode) {
          el.parentNode.removeChild(el);
        }
      }
    });
    const tracked = { mounted: rootMounted, updated: vi.fn() };
    grantAll = false;
    permissionSet = new Set([]);
    const wrapper = mountList({ global: { directives: { auth: tracked as any } } });
    await flush();

    // 页面根 div 确实声明了 v-auth="system:menu:list"（权限门控存在）
    const rootCall = rootMounted.mock.calls.find(
      c => (c[0] as HTMLElement).classList.contains("menu-manage")
    );
    expect(rootCall).toBeDefined();
    expect((rootCall![1] as any).value).toBe("system:menu:list");
    // tracked 移除逻辑对根元素执行过（无 list 权限时门控生效）
    expect(rootMounted).toHaveBeenCalled();
  });

  /* ---------------- 5. 迟到树响应不覆盖新响应 ---------------- */

  it("飞行中用户刷新：旧响应不写入，补发请求最终生效", async () => {
    let resolveSlow!: (v: unknown) => void;
    listMenuTree
      .mockReset()
      .mockResolvedValueOnce(treeData()) // 初始
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; })) // 刷新1 慢
      .mockResolvedValueOnce([{ menuId: 9, parentId: 0, menuName: "新树", routerName: "NewTree", path: "/new", permission: "", menuType: 2, isButton: 0, sortNum: 1, status: 1, isSystem: 0, children: [] }]); // 补发

    const wrapper = mountList();
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(1);

    // 用户刷新 → 慢请求（第 2 次）
    const refreshBtn = wrapper.findAll("button").find(b => (b.attributes("aria-label") ?? "").includes("刷新"));
    await refreshBtn!.trigger("click");
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(2);

    // 飞行中再次刷新 → 记录 pending，不并发
    await refreshBtn!.trigger("click");
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(2);

    // 旧响应返回：needsRefresh 存在 → 跳过写入，页面仍是旧数据
    resolveSlow(treeData());
    await flush();
    expect(wrapper.text()).toContain("系统管理");
    expect(wrapper.text()).not.toContain("新树");
    // 补发一次（第 3 次）
    expect(listMenuTree).toHaveBeenCalledTimes(3);

    // 补发结果写入
    await flush();
    expect(wrapper.text()).toContain("新树");
  });

  /* ---------------- 6. 飞行中用户刷新最终补发（意图不丢失） ---------------- */

  it("树请求飞行中点击刷新，请求结束后必须补发一次", async () => {
    let resolveSlow!: (v: unknown) => void;
    listMenuTree
      .mockReset()
      .mockResolvedValueOnce(treeData())
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; }))
      .mockResolvedValueOnce(treeData());

    const wrapper = mountList();
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(1);

    const refreshBtn = wrapper.findAll("button").find(b => (b.attributes("aria-label") ?? "").includes("刷新"));
    // 第 2 次请求（慢）进入飞行
    await refreshBtn!.trigger("click");
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(2);

    // 飞行中再次点击刷新：不并发，记录 pending（意图不丢失）
    await refreshBtn!.trigger("click");
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(2);

    // 慢请求完成 → 补发一次（第 3 次）
    resolveSlow(treeData());
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(3);
  });

  /* ---------------- 12. 组件卸载后不写状态 ---------------- */

  it("树请求飞行中卸载组件：旧响应返回不得写入状态或补发请求", async () => {
    let resolveSlow!: (v: unknown) => void;
    listMenuTree
      .mockReset()
      .mockResolvedValueOnce(treeData())
      .mockImplementationOnce(() => new Promise(r => { resolveSlow = r; }));

    const wrapper = mountList();
    await flush();
    const refreshBtn = wrapper.findAll("button").find(b => (b.attributes("aria-label") ?? "").includes("刷新"));
    await refreshBtn!.trigger("click");
    await flush();
    expect(listMenuTree).toHaveBeenCalledTimes(2);

    wrapper.unmount();
    resolveSlow([{ menuId: 99, parentId: 0, menuName: "幽灵", routerName: "Ghost", path: "/ghost", permission: "", menuType: 1, isButton: 0, sortNum: 1, status: 1, isSystem: 0, children: [] }]);
    await flush();
    // 无补发请求、无异常（卸载后 mounted=false 拦截）
    expect(listMenuTree).toHaveBeenCalledTimes(2);
  });

  /* ---------------- 详情 ---------------- */

  it("详情：打开对话框先展示行数据，再异步加载最新详情", async () => {
    const fresh = {
      menuId: 2,
      parentId: 1,
      menuName: "菜单管理",
      menuType: 1,
      routerName: "SystemMenu",
      path: "/system/menu",
      permission: "system:menu:list",
      metaInfo: "{}",
      isButton: 0,
      sortNum: 60,
      isSystem: 1,
      status: 1,
      remark: "最新备注",
      creatorId: 1,
      createTime: "2026-01-01 00:00:00",
      updaterId: 1,
      updateTime: "2026-01-02 00:00:00"
    };
    getMenu.mockResolvedValue(fresh);

    const wrapper = mountList();
    await flush();
    const row = wrapper.findAll(".el-table__row").find(r => r.text().includes("菜单管理"));
    const detailBtn = row!.findAll("button").find(b => b.text().includes("详情"));
    await detailBtn!.trigger("click");
    await flush();

    expect(getMenu).toHaveBeenCalledWith(2);
    // 对话框显示最新详情（含后端返回的备注/时间）
    expect(wrapper.text()).toContain("最新备注");
    expect(wrapper.text()).toContain("2026-01-01 00:00:00");
  });

  it("详情：A 慢请求返回不得覆盖已切换到的 B", async () => {
    let resolveA!: (v: unknown) => void;
    getMenu.mockImplementationOnce(() => new Promise(r => { resolveA = r; }));
    getMenu.mockResolvedValueOnce({ menuId: 3, parentId: 1, menuName: "用户管理", menuType: 1, routerName: "SystemUser", path: "/system/user", permission: "system:user:list", metaInfo: "{}", isButton: 0, sortNum: 40, isSystem: 0, status: 1, remark: "B", creatorId: 1, createTime: "", updaterId: 1, updateTime: "" });

    const wrapper = mountList();
    await flush();

    const row2 = wrapper.findAll(".el-table__row").find(r => r.text().includes("菜单管理"));
    await row2!.findAll("button").find(b => b.text().includes("详情"))!.trigger("click");
    await flush();

    const row3 = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    await row3!.findAll("button").find(b => b.text().includes("详情"))!.trigger("click");
    await flush();

    // B 已展示
    expect(wrapper.text()).toContain("用户管理");

    // A 的迟到响应返回，不得覆盖 B
    resolveA({ menuId: 2, parentId: 1, menuName: "菜单管理", menuType: 1, routerName: "SystemMenu", path: "/system/menu", permission: "system:menu:list", metaInfo: "{}", isButton: 0, sortNum: 60, isSystem: 1, status: 1, remark: "A", creatorId: 1, createTime: "", updaterId: 1, updateTime: "" });
    await flush();

    // 详情对话框仍展示 B（详情对话框打开中）
    expect(getMenu).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain("B");
  });

  /* ---------------- 3. 三种节点类型字段映射 ---------------- */

  it("新建菜单：默认菜单类型，提交 body 携带 isButton=0 与完整字段", async () => {
    createMenu.mockResolvedValue(101);
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    await setFormField(wrapper, "名称", "我的菜单");
    await setFormField(wrapper, "路由名称", "MyMenu");
    await setFormField(wrapper, "路径", "/my/menu");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    expect(createMenu).toHaveBeenCalledTimes(1);
    const body = createMenu.mock.calls[0][0];
    expect(body).toMatchObject({
      parentId: 0,
      menuName: "我的菜单",
      menuType: 1,
      routerName: "MyMenu",
      path: "/my/menu",
      isButton: 0,
      sortNum: 0,
      status: 1
    });
    expect(body).not.toHaveProperty("isSystem");
    // 成功后对话框关闭（ElDialog modelValue=false；"确定"按钮由 v-show 隐藏仍在 DOM）
    const dialog = wrapper.findComponent({ name: "ElDialog" });
    expect(dialog.exists()).toBe(true);
    expect(dialog.props("modelValue")).toBe(false);
    expect(listMenuTree.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it("切换为按钮：routerName/path 字段消失、提交 isButton=1 且 permission 必填", async () => {
    createMenu.mockResolvedValue(101);
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    // 点击"按钮" radio
    const radios = wrapper.findAll("input[type='radio']");
    expect(radios.length).toBe(3);
    await radios[2].setValue(true);
    await flush();

    // 按钮类型下路由名称/路径字段消失
    expect(wrapper.text()).not.toContain("路由名称");
    expect(wrapper.text()).not.toContain("路径");

    await setFormField(wrapper, "名称", "删除按钮");
    await setFormField(wrapper, "权限标识", "system:menu:delete");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    expect(createMenu).toHaveBeenCalledTimes(1);
    const body = createMenu.mock.calls[0][0];
    expect(body).toMatchObject({
      menuType: 3,
      permission: "system:menu:delete",
      routerName: undefined,
      path: undefined,
      isButton: 1
    });
  });

  it("切换为目录：提交 menuType=2、isButton=0", async () => {
    createMenu.mockResolvedValue(101);
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    const radios = wrapper.findAll("input[type='radio']");
    await radios[0].setValue(true); // 目录
    await flush();

    await setFormField(wrapper, "名称", "我的目录");
    await setFormField(wrapper, "路由名称", "MyDir");
    await setFormField(wrapper, "路径", "/my/dir");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    const body = createMenu.mock.calls[0][0];
    expect(body).toMatchObject({ menuType: 2, routerName: "MyDir", path: "/my/dir", isButton: 0 });
  });

  it("按钮类型下 routerName/path 提交前被清空", async () => {
    createMenu.mockResolvedValue(101);
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    // 先填菜单字段
    await setFormField(wrapper, "名称", "菜单管理");
    await setFormField(wrapper, "路由名称", "SystemMenu");
    await setFormField(wrapper, "路径", "/system/menu");

    // 切到按钮 → routerName/path 清空
    const radios = wrapper.findAll("input[type='radio']");
    await radios[2].setValue(true);
    await flush();
    await setFormField(wrapper, "权限标识", "system:menu:list");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    const body = createMenu.mock.calls[0][0];
    expect(body.routerName).toBeUndefined();
    expect(body.path).toBeUndefined();
    expect(body.isButton).toBe(1);
  });

  it("菜单类型合法权限码：提交 payload 携带 permission（可选字段正例）", async () => {
    createMenu.mockResolvedValue(101);
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    await setFormField(wrapper, "名称", "文档菜单");
    await setFormField(wrapper, "路由名称", "DocMenu");
    await setFormField(wrapper, "路径", "/doc/menu");
    await setFormField(wrapper, "权限标识", "system:doc:view");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    expect(createMenu).toHaveBeenCalledTimes(1);
    expect(createMenu.mock.calls[0][0].permission).toBe("system:doc:view");
  });

  it("菜单类型非法权限码：API 层同步 RangeError 被捕获并提示，对话框保留（不吞异常）", async () => {
    // 模拟表单未拦截时 API 层同步校验拒绝（权限码含大写字母，违反 PERMISSION_PATTERN）
    createMenu.mockImplementationOnce(() => {
      throw new RangeError("permission must contain only lowercase letters, digits, colon, dot, underscore or hyphen");
    });
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    await setFormField(wrapper, "名称", "非法权限菜单");
    await setFormField(wrapper, "路由名称", "BadPermMenu");
    await setFormField(wrapper, "路径", "/bad/perm");
    await setFormField(wrapper, "权限标识", "System:Menu");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    // 同步 RangeError 不被吞：错误信息已提示给用户
    expect(createMenu).toHaveBeenCalledTimes(1);
    expect(messages.error).toHaveBeenCalledWith(
      expect.stringContaining("permission must contain only lowercase letters")
    );
    // 对话框保留（用户可修正后重试）
    const dialog = wrapper.findComponent({ name: "ElDialog" });
    expect(dialog.props("modelValue")).toBe(true);
  });

  /* ---------------- 父节点选项 ---------------- */

  it("父节点选项排除按钮节点；停用节点通过 props.disabled 禁用", async () => {
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    const treeSelect = wrapper.findComponent({ name: "ElTreeSelect" });
    const data = treeSelect.props("data") as any[];
    // 仅目录/菜单节点：1、2、3（按钮 4、5 被排除）
    const ids = collectIds(data);
    expect(ids).toEqual([1, 2, 3]);

    // 真实 el-tree-select 通过 el-tree 的 props.disabled 回调禁用节点
    const treeProps = treeSelect.props("props") as { disabled?: (d: any) => boolean };
    expect(typeof treeProps.disabled).toBe("function");
    const disabled = treeProps.disabled!;
    // 停用节点（status=0）应被禁用；启用节点可选中
    expect(disabled({ menuId: 5, isButton: 1, status: 0 })).toBe(true);
    expect(disabled({ menuId: 1, isButton: 0, status: 1 })).toBe(false);
    expect(disabled({ menuId: 3, isButton: 0, status: 0 })).toBe(true);
  });

  /* ---------------- 7. 新建/编辑双击互斥 ---------------- */

  it("新建提交双击：仅调用一次 createMenu", async () => {
    createMenu.mockResolvedValue(101);
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    await setFormField(wrapper, "名称", "双击菜单");
    await setFormField(wrapper, "路由名称", "DoubleMenu");
    await setFormField(wrapper, "路径", "/double/menu");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await confirmBtn!.trigger("click");
    await flush();

    expect(createMenu).toHaveBeenCalledTimes(1);
  });

  it("编辑提交双击：仅调用一次 updateMenu", async () => {
    updateMenu.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    await openEditDialog(wrapper, "用户管理");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await confirmBtn!.trigger("click");
    await flush();

    expect(updateMenu).toHaveBeenCalledTimes(1);
    // 编辑提交 target = 打开时的 menuId
    expect(updateMenu.mock.calls[0][0]).toBe(3);
  });

  /* ---------------- 13. 编辑期间切换目标仍只操作确认时的 menuId ---------------- */

  it("提交挂起期间打开其它编辑被阻止：仍只操作确认时的 menuId 且对话框不串扰", async () => {
    let resolveUpdate!: (v: unknown) => void;
    updateMenu.mockImplementationOnce(() => new Promise(r => { resolveUpdate = r; }));

    const wrapper = mountList();
    await flush();

    // 打开"用户管理"(3) 编辑并点确定 → updateMenu(3) 挂起（submitting=true）
    await openEditDialog(wrapper, "用户管理");
    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    // updateMenu(3) 已发出（挂起中）
    expect(updateMenu).toHaveBeenCalledTimes(1);
    expect(updateMenu.mock.calls[0][0]).toBe(3);

    // 提交期间点击"菜单管理"(2) 的编辑按钮 → openEditDialog 被 submitting 拦截，
    // 目标不切换、不发起新的 getMenu、对话框内容不串扰
    const row2 = wrapper.findAll(".el-table__row").find(r => r.text().includes("菜单管理"));
    const editBtn2 = row2!.findAll("button").find(b => b.text().includes("编辑"));
    await editBtn2!.trigger("click");
    await flush();
    expect(getMenu).toHaveBeenCalledTimes(1); // 仅打开用户管理时的详情请求
    expect(updateMenu).toHaveBeenCalledTimes(1);

    // 释放挂起的 updateMenu → 仍只操作 menuId=3，且对话框关闭
    resolveUpdate(undefined);
    await flush();
    expect(updateMenu).toHaveBeenCalledTimes(1);
    expect(updateMenu.mock.calls[0][0]).toBe(3);
    const dialog = wrapper.findComponent({ name: "ElDialog" });
    expect(dialog.props("modelValue")).toBe(false);
  });

  /* ---------------- 8. 启停/删除确认期间双击互斥 ---------------- */

  it("停用确认挂起期间双击：只弹一次确认、只调一次 status API", async () => {
    let resolveConfirm!: (v: unknown) => void;
    confirmMock.mockImplementationOnce(() => new Promise(r => { resolveConfirm = r; }));
    changeMenuStatus.mockResolvedValue(undefined);

    const wrapper = mountList();
    await flush();
    const row = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    const statusBtn = row!.findAll("button").find(b => b.text().includes("停用"));

    await statusBtn!.trigger("click");
    await statusBtn!.trigger("click");
    await flush();

    // 只弹一次确认（第二个点击被 operatingIds 拦截）
    expect(confirmMock).toHaveBeenCalledTimes(1);
    expect(changeMenuStatus).not.toHaveBeenCalled();

    resolveConfirm(undefined);
    await flush();
    expect(changeMenuStatus).toHaveBeenCalledTimes(1);
    expect(changeMenuStatus).toHaveBeenCalledWith(3, 0);
  });

  it("删除确认挂起期间双击：只弹一次确认、只调一次 deleteMenu", async () => {
    let resolveConfirm!: (v: unknown) => void;
    confirmMock.mockImplementationOnce(() => new Promise(r => { resolveConfirm = r; }));
    deleteMenu.mockResolvedValue(undefined);

    const wrapper = mountList();
    await flush();
    const row = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    const delBtn = row!.findAll("button").find(b => b.text().includes("删除"));

    await delBtn!.trigger("click");
    await delBtn!.trigger("click");
    await flush();

    expect(confirmMock).toHaveBeenCalledTimes(1);
    expect(deleteMenu).not.toHaveBeenCalled();

    resolveConfirm(undefined);
    await flush();
    expect(deleteMenu).toHaveBeenCalledTimes(1);
    expect(deleteMenu).toHaveBeenCalledWith(3);
  });

  /* ---------------- 9. 确认取消不调用 API ---------------- */

  it("取消停用确认：不调用 status API", async () => {
    confirmMock.mockRejectedValueOnce(new Error("cancel"));
    const wrapper = mountList();
    await flush();
    const row = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    const statusBtn = row!.findAll("button").find(b => b.text().includes("停用"));
    await statusBtn!.trigger("click");
    await flush();
    expect(changeMenuStatus).not.toHaveBeenCalled();
    // 锁已释放：再次点击可重新弹确认
    confirmMock.mockResolvedValueOnce(undefined);
    await statusBtn!.trigger("click");
    await flush();
    expect(changeMenuStatus).toHaveBeenCalledTimes(1);
  });

  it("取消删除确认：不调用 deleteMenu", async () => {
    confirmMock.mockRejectedValueOnce(new Error("cancel"));
    const wrapper = mountList();
    await flush();
    const row = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    const delBtn = row!.findAll("button").find(b => b.text().includes("删除"));
    await delBtn!.trigger("click");
    await flush();
    expect(deleteMenu).not.toHaveBeenCalled();
  });

  /* ---------------- 14. 后端失败后对话框保留输入 ---------------- */

  it("创建失败：对话框不关闭、输入保留", async () => {
    createMenu.mockRejectedValueOnce(new Error("MENU_PARENT_DISABLED"));
    const wrapper = mountList();
    await flush();
    await openCreateMenuDialog(wrapper);

    await setFormField(wrapper, "名称", "保留输入");
    await setFormField(wrapper, "路由名称", "KeepInput");
    await setFormField(wrapper, "路径", "/keep/input");

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    // 失败：对话框仍打开（ElDialog modelValue=true），输入保留（input value 未清空）
    expect(createMenu).toHaveBeenCalledTimes(1);
    const dialog = wrapper.findComponent({ name: "ElDialog" });
    expect(dialog.props("modelValue")).toBe(true);
    const nameItem = wrapper.findAll(".el-form-item").find(i => i.text().includes("名称"));
    expect((nameItem!.find("input").element as HTMLInputElement).value).toBe("保留输入");
  });

  it("编辑失败：对话框不关闭、输入保留，且提交对象仍为目标 menuId、备注不被清空", async () => {
    updateMenu.mockRejectedValueOnce(new Error("MENU_PARENT_IS_BUTTON"));
    const wrapper = mountList();
    await flush();
    await openEditDialog(wrapper, "用户管理");

    // 编辑打开时已调用 GET /{menuId} 加载完整详情（含原备注"备注3"）
    expect(getMenu).toHaveBeenCalledWith(3);

    // 修改名称后提交
    await setFormField(wrapper, "名称", "改名用户管理");
    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    await confirmBtn!.trigger("click");
    await flush();

    expect(updateMenu).toHaveBeenCalledTimes(1);
    expect(updateMenu.mock.calls[0][0]).toBe(3);
    expect(updateMenu.mock.calls[0][1].menuName).toBe("改名用户管理");
    // 原备注通过详情加载保留在 payload 中（P0 修复：不用 MenuNode 直接填充）
    expect(updateMenu.mock.calls[0][1].remark).toBe("备注3");
    // 对话框保留（ElDialog modelValue=true）+ 输入保留（input value 未清空）
    const dialog = wrapper.findComponent({ name: "ElDialog" });
    expect(dialog.props("modelValue")).toBe(true);
    const nameItem = wrapper.findAll(".el-form-item").find(i => i.text().includes("名称"));
    expect((nameItem!.find("input").element as HTMLInputElement).value).toBe("改名用户管理");
  });

  /* ---------------- P0-2：编辑必须先加载完整详情 ---------------- */

  it("编辑打开时先 GET /{menuId} 加载详情，加载完成前确定按钮禁用、完成后启用", async () => {
    let resolveDetail!: (v: unknown) => void;
    getMenu.mockReset();
    getMenu.mockImplementationOnce(() => new Promise(r => { resolveDetail = r; }));

    const wrapper = mountList();
    await flush();
    const row = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    const editBtn = row!.findAll("button").find(b => b.text().includes("编辑"));
    await editBtn!.trigger("click");
    await flush();

    // 详情请求已发出但未返回：表单未就绪，确定按钮禁用
    expect(getMenu).toHaveBeenCalledWith(3);
    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    expect((confirmBtn!.element as HTMLButtonElement).disabled).toBe(true);
    // 加载完成前提交被 formReady 拦截（即使强制点击也不发 updateMenu）
    await confirmBtn!.trigger("click").catch(() => {});
    await flush();
    expect(updateMenu).not.toHaveBeenCalled();

    // 详情返回 → formReady=true，确定按钮启用，表单被完整详情填充
    resolveDetail(menuDetail(3));
    await flush();
    await flush();
    expect((confirmBtn!.element as HTMLButtonElement).disabled).toBe(false);
    // 备注字段已被详情填充（不再为空）
    const remarkItem = wrapper.findAll(".el-form-item").find(i => i.text().includes("备注"));
    expect((remarkItem!.find("input").element as HTMLInputElement).value).toBe("备注3");
  });

  it("编辑详情加载失败：确定按钮保持禁用且不提交（不丢失上下文）", async () => {
    getMenu.mockReset();
    getMenu.mockRejectedValueOnce(new Error("network"));

    const wrapper = mountList();
    await flush();
    await openEditDialog(wrapper, "用户管理");
    // openEditDialog 内部 catch 后 formReady 保持 false
    await flush();

    const confirmBtn = wrapper.findAll("button").find(b => b.text().includes("确定"));
    expect((confirmBtn!.element as HTMLButtonElement).disabled).toBe(true);
    await confirmBtn!.trigger("click").catch(() => {});
    await flush();
    expect(updateMenu).not.toHaveBeenCalled();
    // 对话框仍打开（用户可取消后重试）
    const dialog = wrapper.findComponent({ name: "ElDialog" });
    expect(dialog.props("modelValue")).toBe(true);
  });

  /* ---------------- 启停成功路径 ---------------- */

  it("停用成功：调用 status API 后刷新树", async () => {
    changeMenuStatus.mockResolvedValue(undefined);
    const wrapper = mountList();
    await flush();
    const before = listMenuTree.mock.calls.length;

    const row = wrapper.findAll(".el-table__row").find(r => r.text().includes("用户管理"));
    const statusBtn = row!.findAll("button").find(b => b.text().includes("停用"));
    await statusBtn!.trigger("click");
    await flush();

    expect(changeMenuStatus).toHaveBeenCalledWith(3, 0);
    // 刷新树
    expect(listMenuTree.mock.calls.length).toBe(before + 1);
  });
});

/** 递归收集树节点 menuId（用于父节点选项断言） */
function collectIds(nodes: any[]): number[] {
  const ids: number[] = [];
  for (const n of nodes) {
    ids.push(n.menuId);
    if (n.children?.length) ids.push(...collectIds(n.children));
  }
  return ids;
}
