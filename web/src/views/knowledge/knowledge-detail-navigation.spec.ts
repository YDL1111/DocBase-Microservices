import { nextTick } from "vue";
import { mount } from "@vue/test-utils";
import { createRouter, createWebHashHistory } from "vue-router";
import { beforeEach, describe, expect, it, vi } from "vitest";

const getKnowledgeBaseMock = vi.fn();

vi.mock("@/api/knowledge", () => ({
  getKnowledgeBase: (...args: unknown[]) => getKnowledgeBaseMock(...args)
}));

vi.mock("@/store/modules/knowledge", () => ({
  useKnowledgeStoreHook: () => ({
    currentBase: null,
    getRequestSeq: () => 1,
    beginBaseContext: vi.fn(),
    setCurrentBase: vi.fn()
  })
}));

vi.mock("@/utils/message", () => ({
  message: { error: vi.fn() }
}));

import detailVue from "./detail.vue";

async function flushPromises() {
  for (let i = 0; i < 5; i += 1) {
    await nextTick();
    await Promise.resolve();
  }
}

describe("KnowledgeDetail navigation isolation", () => {
  beforeEach(() => {
    getKnowledgeBaseMock.mockReset().mockResolvedValue({
      id: 1,
      name: "Knowledge 1"
    });
  });

  it("leaving the detail route must not redirect the target menu to 404", async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: "/knowledge/:id",
          name: "KnowledgeDetail",
          component: detailVue
        },
        {
          path: "/home",
          name: "Home",
          component: { template: "<div>home</div>" }
        }
      ]
    });

    await router.push("/knowledge/1");
    await router.isReady();
    const wrapper = mount({ template: "<router-view />" }, {
      global: {
        plugins: [router],
        stubs: {
          ElResult: true,
          ElTabs: { template: "<div><slot /></div>" },
          ElTabPane: { template: "<div><slot /></div>" },
          DetailOverview: true,
          DetailMembers: true,
          DetailFolders: true,
          DetailDocuments: true
        }
      }
    });
    await flushPromises();

    await router.push("/home");
    await flushPromises();

    expect(router.currentRoute.value.name).toBe("Home");
    expect(router.currentRoute.value.path).toBe("/home");
    wrapper.unmount();
  });
});
