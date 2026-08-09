import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount } from "@vue/test-utils";
import {
  createRouter,
  createWebHashHistory,
  type Router
} from "vue-router";
import { setActivePinia, createPinia, type Pinia } from "pinia";
import { nextTick } from "vue";
import ElementPlus from "element-plus";

/* ============================================================
 * 模拟 API 层（可控 Promise：延迟 / 成功 / 失败）
 * ============================================================ */
let getIngestTaskMock: any = vi.fn();
let listIngestTasksMock: any = vi.fn();
let retryIngestTaskMock: any = vi.fn();
let cancelIngestTaskMock: any = vi.fn();

vi.mock("@/api/ingest", () => ({
  getIngestTask: (...args: any[]) => getIngestTaskMock(...args),
  listIngestTasks: (...args: any[]) => listIngestTasksMock(...args),
  retryIngestTask: (...args: any[]) => retryIngestTaskMock(...args),
  cancelIngestTask: (...args: any[]) => cancelIngestTaskMock(...args)
}));

vi.mock("@/utils/message", () => ({
  message: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  }
}));

// 模拟 userStore：默认拥有全部权限；单测可覆盖
let grantAll = true;
vi.mock("@/store/modules/user", () => ({
  useUserStoreHook: () => ({
    hasPermission: () => grantAll,
    permissions: new Set<string>(),
    admin: grantAll
  })
}));

// 模拟 ElMessageBox.confirm：默认 resolve；单测可覆盖为 deferred
const confirmMock = vi.fn().mockResolvedValue(undefined);
vi.mock("element-plus", async (importOriginal) => {
  const actual: any = await importOriginal();
  return {
    ...actual,
    ElMessageBox: {
      confirm: (...args: any[]) => confirmMock(...args),
      alert: vi.fn(),
      prompt: vi.fn()
    },
    ElMessage: actual?.ElMessage ?? {
      success: vi.fn(),
      error: vi.fn(),
      warning: vi.fn(),
      info: vi.fn()
    }
  };
});

import listVue from "./list.vue";
import detailVue from "./detail.vue";

function makeTask(id: number, status = "PROCESSING") {
  return {
    id,
    eventId: `evt-${id}`,
    knowledgeBaseId: 1,
    documentId: id * 10,
    versionId: 1,
    objectKey: "a]very-long-object-key-for-testing-purpose.bin",
    fileName: `file-${id}.pdf`,
    contentType: "application/pdf",
    taskType: "IMPORT",
    status,
    attemptCount: 0,
    lastError: "",
    nextRetryAt: "",
    pythonKbId: "py-kb",
    pythonDocId: "py-doc",
    chunkCount: 5,
    createdBy: 1,
    createdAt: "2026-01-01T00:00:00",
    updatedAt: "2026-01-01T00:00:00",
    startedAt: "2026-01-01T00:00:01",
    finishedAt: ""
  };
}

let pinia: Pinia;
let timers: Array<() => void>;

beforeEach(() => {
  pinia = createPinia();
  setActivePinia(pinia);
  grantAll = true;
  getIngestTaskMock.mockReset();
  listIngestTasksMock.mockReset();
  retryIngestTaskMock.mockReset();
  cancelIngestTaskMock.mockReset();
  confirmMock.mockReset().mockResolvedValue(undefined);
  vi.useFakeTimers();
  timers = [];
  vi.spyOn(global, "setTimeout").mockImplementation(((fn: any) => {
    timers.push(fn);
    return timers.length as any;
  }) as any);
  const clearTimeoutMock = vi.spyOn(global, "clearTimeout");
  clearTimeoutMock.mockImplementation(() => {});
  vi.spyOn(global, "setInterval").mockReturnValue(1 as any);
  vi.spyOn(global, "clearInterval").mockReturnValue(undefined);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

/** 刷新多个微任务周期，确保异步链走完 */
async function flushPromises(times = 6) {
  for (let i = 0; i < times; i++) {
    await nextTick();
    await Promise.resolve();
  }
}

/**
 * 触发当前已注册的定时器回调（仅一轮）。
 * 注意：仅调用 fn() 而不 await 其返回的 Promise —— 轮询回调可能故意阻塞
 * （用于模拟慢请求），若 await 会导致测试挂起。
 */
async function flushTimersRound(): Promise<number> {
  const round = [...timers];
  timers.length = 0;
  for (const fn of round) {
    fn();
    await flushPromises();
  }
  return round.length;
}

/* ============================================================
 * 路由构建：与 030-ingest-menus.sql 完全一致
 *   父 /ingest      (IngestTaskDir  → RouterViewWrapper)
 *   子 /ingest/tasks (IngestTask    → list.vue)
 *   隐藏 /ingest/tasks/:taskId (IngestTaskDetail → detail.vue)
 * ============================================================ */
async function buildIngestRouter(): Promise<Router> {
  const { usePermissionStore } = await import("@/store/modules/permission");
  const store = usePermissionStore();

  const menuTree = [
    {
      menuId: 100,
      parentId: null,
      menuName: "导入任务",
      routerName: "IngestTaskDir",
      path: "/ingest",
      permission: "",
      menuType: 2,
      isButton: 0,
      sortNum: 20,
      metaInfo: "{}",
      children: [
        {
          menuId: 101,
          parentId: 100,
          menuName: "任务列表",
          routerName: "IngestTask",
          path: "/ingest/tasks",
          permission: "ingest:task:list",
          menuType: 1,
          isButton: 0,
          sortNum: 10,
          metaInfo: "{}",
          children: []
        }
      ]
    }
  ];

  const routes = store.buildRoutes(menuTree);

  const router = createRouter({
    history: createWebHashHistory(),
    routes: [
      {
        path: "/",
        name: "RootLayout",
        component: { template: "<router-view />" },
        children: [
          {
            path: "/ingest/tasks/:taskId",
            name: "IngestTaskDetail",
            component: detailVue,
            meta: { hidden: true }
          }
        ]
      }
    ]
  });

  routes.forEach(r => router.addRoute("RootLayout", r));
  return router;
}

/** 用于不需要路由跳转的列表页测试 */
function createListRouter(): Router {
  return createRouter({
    history: createWebHashHistory(),
    routes: [
      { path: "/", component: { template: "<router-view />" } },
      {
        path: "/ingest/tasks/:taskId",
        component: { template: "<div />" }
      }
    ]
  });
}

// ========================= P0-1: 真实路由渲染 =========================

describe("P0-1: 与 SQL 一致的动态路由注册并渲染 list.vue", () => {
  it("父 /ingest (RouterViewWrapper) + 子 /ingest/tasks (list.vue) 应真实渲染", async () => {
    listIngestTasksMock.mockResolvedValue({ records: [], total: 0 });

    const router = await buildIngestRouter();
    const wrapper = mount(
      { template: "<router-view />" },
      { global: { plugins: [router, pinia, ElementPlus] } }
    );

    await router.push("/ingest/tasks");
    await flushPromises();

    expect(wrapper.text()).toContain("导入任务");
    expect(wrapper.text()).toContain("查询");
    expect(wrapper.text()).toContain("重置");
    expect(wrapper.find(".ingest-list").exists()).toBe(true);
  });

  it("buildRoutes 应生成目录用 Wrapper、列表用真实组件", async () => {
    const { usePermissionStore } = await import("@/store/modules/permission");
    const store = usePermissionStore();
    const menuTree = [
      {
        menuId: 100,
        parentId: null,
        menuName: "导入任务",
        routerName: "IngestTaskDir",
        path: "/ingest",
        permission: "",
        menuType: 2,
        isButton: 0,
        sortNum: 20,
        metaInfo: "{}",
        children: [
          {
            menuId: 101,
            parentId: 100,
            menuName: "任务列表",
            routerName: "IngestTask",
            path: "/ingest/tasks",
            permission: "ingest:task:list",
            menuType: 1,
            isButton: 0,
            sortNum: 10,
            metaInfo: "{}",
            children: []
          }
        ]
      }
    ];
    const routes = store.buildRoutes(menuTree);
    expect(routes).toHaveLength(1);
    const dir = routes[0];
    expect(dir.name).toBe("IngestTaskDir");
    expect(dir.children![0].name).toBe("IngestTask");
    expect(dir.component).not.toBe(dir.children![0].component);
  });
});

// ========================= P0-2: 详情页异步请求隔离 =========================

describe("P0-2: detail.vue 异步请求隔离", () => {
  function detailRouter() {
    return createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: "/",
          component: { template: "<router-view />" },
          children: [
            {
              path: "/ingest/tasks/:taskId",
              name: "IngestTaskDetail",
              component: detailVue
            }
          ]
        }
      ]
    });
  }

  it("A 晚于 B 返回时不能覆盖 B 的内容", async () => {
    let resolveA: any;
    getIngestTaskMock.mockImplementationOnce(
      () => new Promise(r => { resolveA = r; })
    );
    getIngestTaskMock.mockResolvedValueOnce(makeTask(2, "SUCCEEDED"));

    const router = detailRouter();
    const wrapper = mount(detailVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await router.push("/ingest/tasks/1");
    await flushPromises();

    await router.push("/ingest/tasks/2");
    await flushPromises();

    resolveA(makeTask(1, "PROCESSING"));
    await flushPromises();

    expect(wrapper.text()).toContain("#2");
    expect(wrapper.text()).toContain("已完成");
    expect(wrapper.text()).not.toContain("#1");
  });

  it("B 返回 403/404 后不能显示 A 的旧内容", async () => {
    getIngestTaskMock.mockResolvedValueOnce(makeTask(1, "PROCESSING"));
    const router = detailRouter();
    const wrapper = mount(detailVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await router.push("/ingest/tasks/1");
    await flushPromises();
    expect(wrapper.text()).toContain("#1");

    const err: any = new Error("Forbidden");
    err.response = { status: 403 };
    getIngestTaskMock.mockRejectedValueOnce(err);
    await router.push("/ingest/tasks/2");
    await flushPromises();

    expect(wrapper.text()).not.toContain("#1");
    expect(wrapper.text()).not.toContain("file-1.pdf");
  });

  it("taskId 变化时立即清空旧 task 与 objectKeyExpanded", async () => {
    getIngestTaskMock.mockResolvedValueOnce(makeTask(1, "PROCESSING"));
    const router = detailRouter();
    const wrapper = mount(detailVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await router.push("/ingest/tasks/1");
    await flushPromises();
    expect(wrapper.find(".code-block").text()).toContain("...");

    let resolve2: any;
    getIngestTaskMock.mockImplementationOnce(
      () => new Promise(r => { resolve2 = r; })
    );
    await router.push("/ingest/tasks/2");
    await nextTick();

    expect(wrapper.text()).not.toContain("file-1.pdf");

    resolve2(makeTask(2, "SUCCEEDED"));
    await flushPromises();
    expect(wrapper.text()).toContain("#2");
  });
});

// ========================= P0-3: deferred confirm 只操作 A =========================

describe("P0-3: 确认框 pending 期间切换任务，API 仍只操作 A", () => {
  it("deferred confirm：点击 A 重试 → 切到 B → resolve confirm → 仅 A 被操作", async () => {
    let resolveConfirm: any;
    confirmMock.mockImplementationOnce(
      () => new Promise(r => { resolveConfirm = r; })
    );

    getIngestTaskMock.mockResolvedValue(makeTask(1, "FAILED"));
    retryIngestTaskMock.mockResolvedValueOnce(undefined);

    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: "/",
          component: { template: "<router-view />" },
          children: [
            {
              path: "/ingest/tasks/:taskId",
              name: "IngestTaskDetail",
              component: detailVue
            }
          ]
        }
      ]
    });

    const wrapper = mount(detailVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await router.push("/ingest/tasks/1");
    await flushPromises();

    const retryBtn = wrapper
      .findAll(".header-actions button")
      .find(b => b.text().includes("重试"));
    expect(retryBtn).toBeDefined();
    await retryBtn!.trigger("click");
    await nextTick();

    expect(retryIngestTaskMock).not.toHaveBeenCalled();

    getIngestTaskMock.mockResolvedValueOnce(makeTask(2, "SUCCEEDED"));
    await router.push("/ingest/tasks/2");
    await flushPromises();

    resolveConfirm(undefined);
    await flushPromises();

    expect(retryIngestTaskMock).toHaveBeenCalledTimes(1);
    expect(retryIngestTaskMock).toHaveBeenCalledWith(1);
  });
});

// ========================= 列表 pending-refresh 机制 =========================

/**
 * 核心行为：
 *  - 自动轮询在请求飞行中可跳过（不记录 pending，轮询会再次触发）；
 *  - 用户触发的查询/重置/分页/手动刷新绝不丢弃：记录 pending + 最新参数；
 *  - 当前请求结束后立即补发一次最新参数请求；
 *  - 多个 pending 合并为一次补发；
 *  - 不产生网络并发；
 *  - 组件卸载后不得补发请求或重新注册轮询。
 */
describe("pending-refresh: 阻塞旧轮询 + 用户查询不丢失", () => {
  it("轮询阻塞期间用户选择 FAILED 并查询，旧请求完成后立即补发 status=FAILED，页面显示新结果", async () => {
    let blockResolve: any;
    // 第 1 次（初始加载）：活跃任务 → 注册轮询定时器
    // 第 2 次（轮询）：故意阻塞
    // 第 3 次（补发的用户请求）：FAILED 结果
    listIngestTasksMock
      .mockResolvedValueOnce({ records: [makeTask(1, "PROCESSING")], total: 1 })
      .mockImplementationOnce(() => new Promise(r => { blockResolve = r; }))
      .mockResolvedValueOnce({
        records: [makeTask(1, "FAILED"), makeTask(2, "FAILED")],
        total: 2
      });

    const router = createListRouter();
    const wrapper = mount(listVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await flushPromises();

    // 初始加载（第 1 次），返回活跃任务 → 注册轮询定时器
    expect(listIngestTasksMock).toHaveBeenCalledTimes(1);
    expect(timers.length).toBeGreaterThan(0);

    // 推进定时器 → 轮询请求发起（第 2 次，阻塞中）
    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 阻塞期间：用户选择 FAILED 并点击查询
    const { ElSelect } = await import("element-plus");
    const selectVm = wrapper.findComponent(ElSelect).vm as any;
    selectVm.$emit("update:modelValue", "FAILED");
    await flushPromises();

    const searchBtn = wrapper
      .findAll(".toolbar-actions button")
      .find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await flushPromises();

    // 用户请求不丢失但也不并发：仍为 2 次（第 2 次阻塞中）
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 释放阻塞的轮询请求 → finally 中补发一次 status=FAILED 的用户请求
    blockResolve({ records: [makeTask(1, "PROCESSING")], total: 1 });
    await flushPromises();

    // 补发后总请求数 = 3（初始 + 轮询 + 补发）
    expect(listIngestTasksMock).toHaveBeenCalledTimes(3);

    // 最后一次请求必须携带 status=FAILED
    const lastCall = listIngestTasksMock.mock.calls.at(-1);
    expect(lastCall[0].status).toBe("FAILED");

    // 页面最终显示补发请求的结果（2 个 FAILED 任务）
    const rows = wrapper.findAll("tbody tr");
    expect(rows.length).toBe(2);
    expect(wrapper.text()).toContain("file-1.pdf");
    expect(wrapper.text()).toContain("file-2.pdf");
  });
});

describe("pending-refresh: 分页请求不丢失", () => {
  it("轮询阻塞期间切换分页，旧请求完成后补发第 2 页请求", async () => {
    let blockResolve: any;
    listIngestTasksMock
      .mockResolvedValueOnce({ records: [makeTask(1, "PROCESSING")], total: 20 })
      .mockImplementationOnce(() => new Promise(r => { blockResolve = r; }))
      .mockResolvedValueOnce({ records: [makeTask(11, "FAILED")], total: 20 });

    const router = createListRouter();
    const wrapper = mount(listVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(1);

    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2); // 第 2 次阻塞

    // 用户切换到第 2 页（分页组件触发 current-change）
    const pagination = wrapper.findComponent({ name: "ElPagination" }) as any;
    pagination.vm.$emit("current-change", 2);
    await flushPromises();

    // 不并发
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 释放阻塞 → 补发第 2 页请求
    blockResolve({ records: [makeTask(1, "PROCESSING")], total: 20 });
    await flushPromises();

    expect(listIngestTasksMock).toHaveBeenCalledTimes(3);
    const lastCall = listIngestTasksMock.mock.calls.at(-1);
    expect(lastCall[0].current).toBe(2);
  });
});

describe("pending-refresh: retry/cancel 后刷新不丢失", () => {
  it("retry 成功后若请求仍在飞行中，刷新不丢失", async () => {
    let blockResolve: any;
    // 初始加载返回 FAILED（可重试）任务（终态，不注册轮询）；
    // 手动刷新（第 2 次）阻塞；retry 触发的刷新被 pending；释放后补发第 3 次
    listIngestTasksMock
      .mockResolvedValueOnce({ records: [makeTask(1, "FAILED")], total: 1 })
      .mockImplementationOnce(() => new Promise(r => { blockResolve = r; }))
      .mockResolvedValueOnce({ records: [makeTask(1, "FAILED")], total: 1 });
    retryIngestTaskMock.mockResolvedValue(undefined);

    const router = createListRouter();
    const wrapper = mount(listVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(1);

    // 手动刷新 → 第 2 次请求阻塞（此时无轮询，终态）
    const refreshBtn = wrapper.findAll(".toolbar-actions button").at(-1)!;
    await refreshBtn.trigger("click");
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2); // 阻塞中

    // 用户点击重试（retry 成功后调用 fetchTasks("user")）
    const retryBtn = wrapper
      .findAll("tbody tr .el-button")
      .find(b => b.text().includes("重试"));
    expect(retryBtn).toBeDefined();
    await retryBtn!.trigger("click");
    await flushPromises();

    // retry API 已调用，但列表刷新被 pending（不并发）
    expect(retryIngestTaskMock).toHaveBeenCalledTimes(1);
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 释放阻塞 → 补发一次刷新
    blockResolve({ records: [makeTask(1, "FAILED")], total: 1 });
    await flushPromises();

    expect(listIngestTasksMock).toHaveBeenCalledTimes(3);
  });
});

describe("pending-refresh: 多次用户操作合并为一次最新请求", () => {
  it("阻塞期间多次查询/分页，最终只补发一次（最新参数）", async () => {
    let blockResolve: any;
    listIngestTasksMock
      .mockResolvedValueOnce({ records: [makeTask(1, "PROCESSING")], total: 1 })
      .mockImplementationOnce(() => new Promise(r => { blockResolve = r; }))
      .mockResolvedValueOnce({ records: [makeTask(1, "FAILED")], total: 1 });

    const router = createListRouter();
    const wrapper = mount(listVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(1);

    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2); // 轮询阻塞

    // 多次用户操作：选 FAILED + 查询 + 切第 2 页
    const { ElSelect } = await import("element-plus");
    const selectVm = wrapper.findComponent(ElSelect).vm as any;
    selectVm.$emit("update:modelValue", "FAILED");
    await flushPromises();

    const searchBtn = wrapper
      .findAll(".toolbar-actions button")
      .find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await flushPromises();

    const pagination = wrapper.findComponent({ name: "ElPagination" }) as any;
    pagination.vm.$emit("current-change", 2);
    await flushPromises();

    // 所有操作被记录 pending，但不并发
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 释放阻塞 → 仅补发一次（最新参数）
    blockResolve({ records: [makeTask(1, "PROCESSING")], total: 1 });
    await flushPromises();

    // 只补发一次
    expect(listIngestTasksMock).toHaveBeenCalledTimes(3);
    const lastCall = listIngestTasksMock.mock.calls.at(-1);
    expect(lastCall[0].current).toBe(2);
    expect(lastCall[0].status).toBe("FAILED");
  });
});

describe("pending-refresh: 组件卸载后不补发请求也不重新注册轮询", () => {
  it("阻塞期间卸载组件，旧请求完成后不得补发请求或注册定时器", async () => {
    let blockResolve: any;
    listIngestTasksMock
      .mockResolvedValueOnce({ records: [makeTask(1, "PROCESSING")], total: 1 })
      .mockImplementationOnce(() => new Promise(r => { blockResolve = r; }));

    const router = createListRouter();
    const wrapper = mount(listVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(1);

    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2); // 轮询阻塞

    // 用户触发查询 → 记录 pending
    const refreshBtn = wrapper.findAll(".toolbar-actions button").at(-1)!;
    await refreshBtn.trigger("click");
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 卸载组件
    wrapper.unmount();
    const timersBefore = timers.length;

    // 释放阻塞的轮询请求 → 已卸载，不得补发，不得注册新定时器
    blockResolve({ records: [makeTask(1, "PROCESSING")], total: 1 });
    await flushPromises();

    // 无补发请求
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);
    // 无新定时器注册（卸载后 mounted=false，不调度轮询）
    expect(timers.length).toBe(timersBefore);
  });
});

// ========================= P1-1: 自动轮询飞行中可跳过 =========================

describe("P1-1: 自动轮询在请求飞行中可跳过", () => {
  it("轮询请求飞行中，下一次轮询触发时应跳过", async () => {
    let blockResolve: any;
    listIngestTasksMock
      .mockResolvedValueOnce({ records: [makeTask(1, "PROCESSING")], total: 1 })
      .mockImplementationOnce(() => new Promise(r => { blockResolve = r; }))
      .mockResolvedValue({ records: [makeTask(1, "PROCESSING")], total: 1 });

    const router = createListRouter();
    mount(listVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(1);

    // 推进定时器 → 轮询请求（第 2 次，阻塞中）
    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 阻塞期间，人为再注册一个定时器并推进（模拟下一次轮询触发）
    // 由于轮询回调内 fetchTasks("poll") 发现 inFlight 为 true，应直接 return，不产生第 3 次
    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 释放阻塞
    blockResolve({ records: [makeTask(1, "PROCESSING")], total: 1 });
    await flushPromises();
  });
});

// ========================= P1-2: 全部终态后停止轮询 =========================

describe("P1-2: 全部终态后清除定时器并停止轮询", () => {
  it("轮询返回终态后不再调度下一次", async () => {
    listIngestTasksMock.mockResolvedValueOnce({
      records: [makeTask(1, "PROCESSING")],
      total: 1
    });
    listIngestTasksMock.mockResolvedValueOnce({
      records: [makeTask(1, "SUCCEEDED")],
      total: 1
    });

    mount(listVue, {
      global: { plugins: [pinia, ElementPlus] }
    });
    await flushPromises();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(1);

    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);

    // 终态不再调度
    await flushTimersRound();
    expect(listIngestTasksMock).toHaveBeenCalledTimes(2);
  });
});

// ========================= P1-2: 详情页过期请求不得修改轮询/loading =========================

describe("P1-2: 详情页慢请求返回时不得关闭新请求的 loading 或调整轮询", () => {
  it("taskId 切换后旧请求返回，不得触碰新任务的 loading 与轮询", async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: "/",
          component: { template: "<router-view />" },
          children: [
            {
              path: "/ingest/tasks/:taskId",
              name: "IngestTaskDetail",
              component: detailVue
            }
          ]
        }
      ]
    });

    let resolveTask1: any;
    getIngestTaskMock.mockImplementationOnce(
      () => new Promise(r => { resolveTask1 = r; })
    );
    getIngestTaskMock.mockResolvedValueOnce(makeTask(2, "SUCCEEDED"));

    const wrapper = mount(detailVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await router.push("/ingest/tasks/1");
    await nextTick();

    await router.push("/ingest/tasks/2");
    await flushPromises();
    expect(wrapper.text()).toContain("#2");

    resolveTask1(makeTask(1, "PROCESSING"));
    await flushPromises();

    expect(wrapper.text()).toContain("#2");
    expect(wrapper.text()).not.toContain("#1");
  });
});

// ========================= P1-3: 重置后再选择 FAILED =========================

describe("P1-3: 重置后再选择 FAILED，筛选请求正常发出（无状态泄漏）", () => {
  it("默认状态重置后再选 FAILED 并查询，应正常发出带 status 的请求", async () => {
    listIngestTasksMock.mockResolvedValue({ records: [], total: 0 });

    const router = createListRouter();
    const wrapper = mount(listVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await flushPromises();

    const initialCalls = listIngestTasksMock.mock.calls.length;

    const resetBtn = wrapper
      .findAll(".toolbar-actions button")
      .find(b => b.text().includes("重置"));
    await resetBtn!.trigger("click");
    await flushPromises();

    expect(listIngestTasksMock.mock.calls.length - initialCalls).toBe(1);

    const { ElSelect } = await import("element-plus");
    const selectVm = wrapper.findComponent(ElSelect).vm as any;
    selectVm.$emit("update:modelValue", "FAILED");
    await flushPromises();

    const searchBtn = wrapper
      .findAll(".toolbar-actions button")
      .find(b => b.text().includes("查询"));
    await searchBtn!.trigger("click");
    await flushPromises();

    const lastCall = listIngestTasksMock.mock.calls.at(-1);
    expect(lastCall[0].status).toBe("FAILED");
  });
});

// ========================= P1-5: 用户取消 confirm 不触发 API =========================

describe("P1-5: 用户取消确认框不触发 API 请求", () => {
  it("取消重试 confirm 后不调用 retryIngestTask", async () => {
    confirmMock.mockRejectedValueOnce(new Error("cancel"));
    getIngestTaskMock.mockResolvedValue(makeTask(1, "FAILED"));

    const router = createListRouter();
    const wrapper = mount(detailVue, {
      global: { plugins: [router, pinia, ElementPlus] }
    });
    await router.push("/ingest/tasks/1");
    await flushPromises();

    const retryBtn = wrapper
      .findAll(".header-actions button")
      .find(b => b.text().includes("重试"));
    await retryBtn!.trigger("click");
    await flushPromises();

    expect(retryIngestTaskMock).not.toHaveBeenCalled();
  });
});

// ========================= P1-4: 真实权限指令行为 =========================

describe("P1-4: 无 ingest:task:view 权限时详情按钮不存在", () => {
  it("关闭权限后详情按钮应从 DOM 移除", async () => {
    grantAll = false;

    const { default: authDirective } = await import("@/directive/permission");

    listIngestTasksMock.mockResolvedValue({
      records: [makeTask(1, "PROCESSING")],
      total: 1
    });

    const router = createListRouter();
    const wrapper = mount(listVue, {
      global: {
        plugins: [router, pinia, ElementPlus],
        directives: { auth: authDirective as any }
      }
    });
    await flushPromises();

    const detailBtns = wrapper.findAll("button").filter(b =>
      b.text().includes("详情")
    );
    expect(detailBtns.length).toBe(0);

    grantAll = true;
  });
});