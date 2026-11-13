import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The dev server proxies the API and the WebSocket to the Spring Boot app on :8080,
// so the browser talks to one origin and no CORS setup is needed.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
  test: {
    environment: "node",
  },
});
