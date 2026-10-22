import { describe, it, expect } from 'vitest';
import { defineComponent, nextTick } from 'vue';
import { mount } from '@vue/test-utils';
import auth from './permission';
import { useUserStoreHook } from '@/store/modules/user';

describe('reactive permission directive', () => {
  it('restores an already-mounted control after permission refresh and hides it after revocation', async () => {
    const user = useUserStoreHook();
    user.admin = false;
    user.setPermissions([]);
    const view = mount(defineComponent({ template: `<button v-auth="'system:user:status'">启停</button>` }),
      { global: { directives: { auth } } });
    expect(view.get('button').attributes('aria-hidden')).toBe('true');
    user.setPermissions(['system:user:status']);
    await nextTick();
    expect(view.get('button').attributes('aria-hidden')).toBe('false');
    user.setPermissions([]);
    await nextTick();
    expect(view.get('button').attributes('aria-hidden')).toBe('true');
    view.unmount();
  });
});
