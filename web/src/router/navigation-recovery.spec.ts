import { describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import {
  freshRouteLocation,
  isDynamicImportError,
  singleFlight
} from "./navigation-recovery";

describe("dynamic route navigation recovery", () => {
  it("registers a dynamic route and resolves it by path instead of stale NotFound name", async () => {
    const testRouter = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: "/",
          name: "RootLayout",
          component: { template: "<router-view />" },
          children: [
            {
              path: "/:pathMatch(.*)*",
              name: "NotFound",
              component: { template: "404" }
            }
          ]
        }
      ]
    });

    const stale = testRouter.resolve("/ai/chat");
    expect(stale.name).toBe("NotFound");

    testRouter.addRoute("RootLayout", {
      path: "/ai/chat",
      name: "AiChat",
      component: { template: "chat" }
    });

    const target = freshRouteLocation(stale);
    expect(target).not.toHaveProperty("name");
    expect(target).not.toHaveProperty("matched");
    expect(testRouter.resolve(target).name).toBe("AiChat");
  });

  it("merges concurrent route bootstraps and permits a later retry", async () => {
    let resolveFirst!: (value: boolean) => void;
    const task = vi
      .fn(async () => true)
      .mockImplementationOnce(
        () => new Promise(resolve => (resolveFirst = resolve))
      )
      .mockResolvedValueOnce(true);
    const run = singleFlight(task);

    const first = run();
    const concurrent = run();
    expect(task).toHaveBeenCalledTimes(1);
    expect(concurrent).toBe(first);

    resolveFirst(false);
    await expect(first).resolves.toBe(false);
    await Promise.resolve();

    await expect(run()).resolves.toBe(true);
    expect(task).toHaveBeenCalledTimes(2);
  });

  it("only classifies dynamic module loading failures as reloadable", () => {
    expect(
      isDynamicImportError(
        new TypeError("Failed to fetch dynamically imported module")
      )
    ).toBe(true);
    expect(
      isDynamicImportError(new Error("ChunkLoadError: Loading chunk 8 failed"))
    ).toBe(true);
    expect(
      isDynamicImportError(new Error("Request failed with status code 500"))
    ).toBe(false);
  });
});
