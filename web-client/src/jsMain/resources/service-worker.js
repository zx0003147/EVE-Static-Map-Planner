const APP_CACHE = "eve-static-map-app-1.12.0";
const DATA_CACHE = "eve-static-map-data-v1";
const APP_SHELL = [
  "./",
  "./index.html",
  "./web-client.css",
  "./web-client.js",
  "./web-pack-loader.mjs",
  "./pwa-runtime.mjs",
  "./manifest.webmanifest",
  "./icons/app-icon-192.png",
  "./icons/app-icon-512.png",
];
const APP_SHELL_URLS = new Set(APP_SHELL.map((path) => new URL(path, self.registration.scope).href));

self.addEventListener("install", (event) => {
  const currentShell = APP_SHELL.map((path) => new Request(
    new URL(path, self.registration.scope),
    { cache: "reload" },
  ));
  event.waitUntil(caches.open(APP_CACHE).then((cache) => cache.addAll(currentShell)));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys
        .filter((key) => key.startsWith("eve-static-map-app-") && key !== APP_CACHE)
        .map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") self.skipWaiting();
  if (event.data?.type === "CACHE_WEB_PACK") event.waitUntil(cachePublishedWebPack(event.data));
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  if (url.pathname.endsWith("/data/manifest.json")) {
    event.respondWith(networkFirst(request, DATA_CACHE));
    return;
  }
  if (/\/data\/web-pack-[A-Za-z0-9._-]+\.json\.gz$/.test(url.pathname)) {
    event.respondWith(cacheFirst(request, DATA_CACHE));
    return;
  }
  if (request.mode === "navigate") {
    event.respondWith(appShellCacheFirst(request));
    return;
  }
  if (APP_SHELL_URLS.has(url.href)) event.respondWith(cacheFirst(request, APP_CACHE));
});

async function networkFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  try {
    const response = await fetch(request);
    if (response.ok) await cache.put(request, response.clone());
    return response;
  } catch (error) {
    const cached = await cache.match(request, { ignoreSearch: true });
    if (cached) return markOfflineFallback(cached);
    throw error;
  }
}

function markOfflineFallback(response) {
  const headers = new Headers(response.headers);
  headers.set("X-EVE-Offline-Fallback", "true");
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request, { ignoreSearch: true });
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok) await cache.put(request, response.clone());
  return response;
}

async function appShellCacheFirst(request) {
  const cache = await caches.open(APP_CACHE);
  const cached = await cache.match(request, { ignoreSearch: true })
    ?? await cache.match(new URL("./index.html", self.registration.scope), { ignoreSearch: true });
  if (cached) return cached;
  return fetch(request);
}

async function cachePublishedWebPack(message) {
  const manifestUrl = new URL(message.manifestUrl);
  const packUrl = new URL(message.packUrl);
  if (manifestUrl.origin !== self.location.origin || packUrl.origin !== self.location.origin) return;
  if (!manifestUrl.pathname.endsWith("/data/manifest.json")) return;
  if (!/\/data\/web-pack-[A-Za-z0-9._-]+\.json\.gz$/.test(packUrl.pathname)) return;
  if (message.manifest?.fileName !== packUrl.pathname.split("/").pop()) return;

  const cache = await caches.open(DATA_CACHE);
  const packResponse = await fetch(new Request(packUrl.href, { cache: "force-cache" }));
  if (!packResponse.ok) return;
  await cache.put(packUrl.href, packResponse.clone());
  await cache.put(manifestUrl.href, new Response(JSON.stringify(message.manifest), {
    headers: {
      "Cache-Control": "no-cache, must-revalidate",
      "Content-Type": "application/json; charset=utf-8",
    },
  }));
}
