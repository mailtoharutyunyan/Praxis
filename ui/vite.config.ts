import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";

// Dev: `npm run dev` proxies the API to a locally running backend.
// Build: output lands in the Spring Boot app's static resources (see the `ui` Maven profile).
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: process.env.UI_OUT_DIR ?? "dist",
    emptyOutDir: true,
    sourcemap: false,
    rolldownOptions: {
      // silent-renew.html is the OIDC silent-renew redirect target (see lib/auth.ts).
      input: {
        main: fileURLToPath(new URL("./index.html", import.meta.url)),
        silentRenew: fileURLToPath(new URL("./silent-renew.html", import.meta.url)),
      },
    },
  },
  server: {
    port: 5173,
    proxy: {
      // API_TARGET points the dev server at another backend, e.g. http://localhost:8082.
      "/api": { target: process.env.API_TARGET ?? "http://localhost:8080", changeOrigin: false },
      "/ui-config.json": { target: process.env.API_TARGET ?? "http://localhost:8080" },
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
  },
});
