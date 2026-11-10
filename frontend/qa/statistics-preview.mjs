// TEST ONLY: start unmodified application components with synthetic API/Google fixtures.
// No backend, real account or Google Sheet is contacted. Not imported by production.
import { createServer } from "vite";
import react from "@vitejs/plugin-react";
import { build } from "esbuild";
import { readFile } from "node:fs/promises";
const root = new URL("../", import.meta.url).pathname;
const fixture = await build({
  entryPoints: [root + "src/test-fixtures.ts"],
  bundle: true,
  write: false,
  platform: "node",
  format: "esm",
});
const { playedRoom } = await import(
  `data:text/javascript;base64,${Buffer.from(fixture.outputFiles[0].text).toString("base64")}`
);
const controls = await readFile(
  new URL("./statistics-fixtures.js", import.meta.url),
  "utf8",
);
const server = await createServer({
  root,
  configFile: false,
  plugins: [
    react(),
    {
      name: "synthetic-statistics-fixtures",
      transformIndexHtml() {
        return [
          {
            tag: "script",
            children: `window.__QA_ROOM__=${JSON.stringify(playedRoom())};\n${controls}`,
            injectTo: "head-prepend",
          },
        ];
      },
    },
  ],
  server: { host: "127.0.0.1", port: Number(process.env.QA_PORT || 5190), strictPort: true },
});
await server.listen();
server.printUrls();
