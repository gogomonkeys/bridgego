import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import type { Plugin } from "vite";

export const publicPwaAssets = [
  "manifest.webmanifest",
  "icons/icon-192.png",
  "icons/icon-512.png",
  "icons/maskable-512.png",
  "icons/apple-touch-icon.png",
  ...[
    "backdrop",
    "animal-bear",
    "animal-panda",
    "animal-rabbit",
    "animal-fox",
  ].map((name) => `assets/forest/${name}.webp`),
];

/** This worker has no API, game-state, token or mutation storage. */
export function serviceWorkerSource(version: string, paths: string[]) {
  return `/* BridgeGo static shell only. No private data or background commands. */
const CACHE = ${JSON.stringify(`bridgego-static-${version}`)};
const PATHS = ${JSON.stringify(paths)};
const ALLOWED = new Set(PATHS);
function valid(response, path) {
  if (!response.ok || response.redirected || response.type === 'opaque') return false;
  const mime = (response.headers.get('content-type') || '').toLowerCase();
  if (path.endsWith('.html')) return mime.includes('text/html');
  if (path.endsWith('.js')) return /(?:java|ecma)script/.test(mime);
  if (path.endsWith('.css')) return mime.includes('text/css');
  if (path.endsWith('.webmanifest')) return mime.includes('json');
  return mime.startsWith('image/');
}
self.addEventListener('install', (event) => {
  event.waitUntil((async () => {
    const cache = await caches.open(CACHE);
    try {
      await Promise.all(PATHS.map(async (path) => {
        const response = await fetch(path, {cache: 'reload', credentials: 'omit'});
        if (!valid(response, path)) throw new Error('Static shell unavailable');
        await cache.put(path, response);
      }));
    } catch (error) {
      await caches.delete(CACHE);
      throw error;
    }
  })());
  // Never skipWaiting: another tab may still be in a game.
});
self.addEventListener('activate', (event) => {
  event.waitUntil((async () => {
    for (const key of await caches.keys()) {
      if (key.startsWith('bridgego-static-') && key !== CACHE) await caches.delete(key);
    }
  })());
  // Never claim or reload existing windows. New navigations use this version.
});
self.addEventListener('fetch', (event) => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin) return;
  if (url.pathname === '/api' || url.pathname.startsWith('/api/') ||
      url.pathname === '/health' || url.pathname.startsWith('/health/') ||
      url.pathname === '/ws' || url.pathname.startsWith('/ws/')) {
    event.respondWith(fetch(request, {cache: 'no-store'}));
    return;
  }
  if (url.search) return;
  const path = (url.pathname === '/' && request.mode === 'navigate') ? '/index.html' : url.pathname;
  if (!ALLOWED.has(path)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    const cached = await cache.match(path);
    if (cached) return cached;
    // A failed or unexpected response is never saved as the application shell.
    const response = await fetch(request);
    if (valid(response, path)) await cache.put(path, response.clone());
    return response;
  })());
});
`;
}

export function pwaBuild(): Plugin {
  return {
    name: "bridgego-static-pwa",
    apply: "build",
    enforce: "post",
    generateBundle(_options, bundle) {
      const hash = createHash("sha256");
      const generated = Object.keys(bundle)
        .filter(
          (name) =>
            name === "index.html" || /^assets\/[^/]+\.(js|css)$/.test(name),
        )
        .sort();
      if (!generated.includes("index.html"))
        throw new Error("PWA shell missing");
      for (const name of generated) {
        const item = bundle[name];
        hash
          .update(name)
          .update(item.type === "chunk" ? item.code : item.source);
      }
      for (const name of publicPwaAssets)
        hash.update(name).update(readFileSync(`public/${name}`));
      hash.update(serviceWorkerSource("", []));
      const source = serviceWorkerSource(
        hash.digest("hex").slice(0, 20),
        [...generated, ...publicPwaAssets].map((path) => `/${path}`),
      );
      this.emitFile({ type: "asset", fileName: "sw.js", source });
    },
  };
}
