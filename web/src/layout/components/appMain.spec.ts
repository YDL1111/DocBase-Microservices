import { nextTick } from "vue";
import { mount } from "@vue/test-utils";
import { createRouter, createWebHashHistory } from "vue-router";
import { describe, expect, it } from "vitest";
import AppMain from "./appMain.vue";

async function flushNavigation() {
  await nextTick();
  await Promise.resolve();
  await nextTick();
}

describe("AppMain route rendering", () => {
  it("rapid menu switches keep exactly one active page instance", async () => {
    const router = createRouter({
      history: createWebHashHistory(),
      routes: [
        {
          path: "/a",
          name: "PageA",
          component: {
            template: '<section class="route-page">Page A</section>'
          }
        },
        {
          path: "/b",
          name: "PageB",
          component: {
            template: '<section class="route-page">Page B</section>'
          }
        }
      ]
    });

    await router.push("/a");
    await router.isReady();
    const wrapper = mount(AppMain, { global: { plugins: [router] } });
    await flushNavigation();

    await router.push("/b");
    await router.push("/a");
    await flushNavigation();

    expect(wrapper.findAll(".route-page")).toHaveLength(1);
    expect(wrapper.text()).toBe("Page A");
    wrapper.unmount();
  });
});
