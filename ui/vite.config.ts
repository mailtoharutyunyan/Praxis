import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Dev: `npm run dev` proxies the API to a locally running backend.
// Build: output lands in the Spring Boot app's static resources (see the `ui` Maven profile).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: process.env.UI_OUT_DIR ?? "dist",
    emptyOutDir: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: false },
      "/ui-config.json": { target: "http://localhost:8080" },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
  },
});
