import { describe, expect, it, vi } from "vitest";

const { app, elementPlusPlugin, router, setupStore, setupPermissionDirective } =
  vi.hoisted(() => ({
    app: {
      use: vi.fn(),
      mount: vi.fn()
    },
    elementPlusPlugin: { install: vi.fn() },
    router: { install: vi.fn() },
    setupStore: vi.fn(),
    setupPermissionDirective: vi.fn()
  }));

vi.mock("vue", async importOriginal => {
  const actual = await importOriginal<typeof import("vue")>();
  return { ...actual, createApp: () => app };
});
vi.mock("element-plus", () => ({
  default: elementPlusPlugin,
  ElConfigProvider: {
    name: "ElConfigProvider",
    template: "<div><slot /></div>"
  }
}));
vi.mock("./router", () => ({ default: router }));
vi.mock("./store", () => ({ setupStore }));
vi.mock("./directive/permission", () => ({ setupPermissionDirective }));

describe("生产前端入口", () => {
  it("注册 Element Plus 后再挂载应用，避免真实页面组件丢失", async () => {
    await import("./main");

    expect(setupStore).toHaveBeenCalledWith(app);
    expect(setupPermissionDirective).toHaveBeenCalledWith(app);
    expect(app.use).toHaveBeenNthCalledWith(1, elementPlusPlugin);
    expect(app.use).toHaveBeenNthCalledWith(2, router);
    expect(app.mount).toHaveBeenCalledWith("#app");
  });
});
