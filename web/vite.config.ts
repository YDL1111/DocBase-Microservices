import { defineConfig, loadEnv } from "vite";
import vue from "@vitejs/plugin-vue";
import vueJsx from "@vitejs/plugin-vue-jsx";
import { fileURLToPath, URL } from "node:url";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const apiTarget = env.VITE_API_TARGET || "http://localhost:8080";

  return {
    plugins: [vue(), vueJsx()],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url))
      }
    },
    base: env.VITE_PUBLIC_PATH || "/",
    server: {
      host: "0.0.0.0",
      port: Number(env.VITE_PORT) || 5173,
      open: false,
      // 开发环境统一通过 Gateway 访问后端，不允许绕过 Gateway 直接访问内部服务
      proxy: {
        "/api": {
          target: apiTarget,
          changeOrigin: true
          // 注意：Gateway 不剥离 /api 前缀，后端 Controller 已带 /api 根路径，
          // 因此这里不做 rewrite，保留完整路径转发。
        }
      }
    },
    build: {
      outDir: "dist",
      sourcemap: false,
      chunkSizeWarningLimit: 1500,
      rollupOptions: {
        output: {
          chunkFileNames: "assets/js/[name]-[hash].js",
          entryFileNames: "assets/js/[name]-[hash].js",
          assetFileNames: "assets/[ext]/[name]-[hash].[ext]"
        }
      }
    },
    test: {
      environment: "happy-dom",
      globals: true,
      include: ["src/**/*.{test,spec}.{ts,tsx}"]
    }
  };
});
