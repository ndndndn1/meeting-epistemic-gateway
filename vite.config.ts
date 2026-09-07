import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  root: "web",
  base: "/meeting-epistemic-gateway/",
  plugins: [react()],
  build: { outDir: "../dist", emptyOutDir: true },
  test: { include: ["src/**/*.test.ts"] },
});
