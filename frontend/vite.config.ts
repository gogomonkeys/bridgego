import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { pwaBuild } from "./pwa-build";
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const backend = env.LOCAL_BACKEND_URL || "http://127.0.0.1:8080";
  const url = new URL(backend);
  if (
    url.protocol !== "http:" ||
    !["127.0.0.1", "localhost", "[::1]"].includes(url.hostname) ||
    url.username ||
    url.password
  )
    throw new Error("LOCAL_BACKEND_URL must be an HTTP loopback URL.");
  return {
    plugins: [react(), pwaBuild()],
    server: {
      host: "127.0.0.1",
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": { target: backend },
        "/health": { target: backend },
        "/ws": { target: backend, ws: true },
      },
    },
  };
});
