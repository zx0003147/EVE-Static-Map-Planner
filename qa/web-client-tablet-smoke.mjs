import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const debugPort = Number(process.argv[2] ?? 9226);
const expectedPageUrl = process.argv[3] ?? "http://127.0.0.1:8765/";
const endpoint = `http://127.0.0.1:${debugPort}`;
const screenshotDirectory = process.argv[4] ? resolve(process.argv[4]) : null;
const page = await waitForPage();
const socket = new WebSocket(page.webSocketDebuggerUrl);
const pending = new Map();
const protocolEvents = [];
let nextId = 1;

await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
  socket.addEventListener("error", reject, { once: true });
});
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (!message.id) {
    if (message.method === "Runtime.exceptionThrown" || message.method === "Log.entryAdded") {
      protocolEvents.push(message);
    }
    return;
  }
  const handler = pending.get(message.id);
  if (!handler) return;
  pending.delete(message.id);
  if (message.error) handler.reject(new Error(message.error.message));
  else handler.resolve(message.result);
});

await send("Runtime.enable");
await send("Log.enable");
await send("Page.enable");
await send("Network.enable");
await send("Emulation.setTouchEmulationEnabled", { enabled: true, maxTouchPoints: 5 });
await setViewport(1280, 800, 2);
await reloadAndReady();
await waitFor("navigator.serviceWorker?.controller != null", 15_000);
await reloadAndReady();

const manifest = await send("Page.getAppManifest");
assert.equal(manifest.errors?.length ?? 0, 0, JSON.stringify(manifest.errors));
const manifestData = JSON.parse(manifest.data);
assert.equal(manifestData.display, "standalone");
assert.ok(manifestData.icons.some((icon) => icon.sizes === "192x192"));
assert.ok(manifestData.icons.some((icon) => icon.sizes === "512x512"));

const cacheHeaders = await evaluate(`(async () => {
  const manifestResponse = await fetch('./data/manifest.json', { cache: 'no-cache' });
  const current = await manifestResponse.clone().json();
  const packResponse = await fetch('./data/' + current.fileName, { cache: 'force-cache' });
  return {
    manifest: manifestResponse.headers.get('cache-control'),
    pack: packResponse.headers.get('cache-control')
  };
})()`);
assert.match(cacheHeaders.manifest, /no-cache/);
assert.match(cacheHeaders.pack, /immutable/);

const landscape = await evaluate(`({
  toolToggle: getComputedStyle(document.querySelector('#tools-toggle')).display,
  columns: getComputedStyle(document.querySelector('#app-shell')).gridTemplateColumns,
  canvas: document.querySelector('#map-canvas').getBoundingClientRect().toJSON(),
  controls: [...document.querySelectorAll('button, input, select')]
    .filter((element) => element.getBoundingClientRect().width > 0)
    .map((element) => element.getBoundingClientRect().height)
})`);
assert.notEqual(landscape.toolToggle, "none");
assert.ok(landscape.canvas.width > 1000, `landscape canvas width ${landscape.canvas.width}`);
assert.ok(Math.min(...landscape.controls) >= 36, "visible controls retain usable hit targets");
await capture("tablet-landscape-map.png");

await evaluate("document.querySelector('#tools-toggle').click()");
await waitFor("document.documentElement.classList.contains('tools-open')", 2_000);
await delay(250);
await evaluate("document.querySelector('[data-tool-tab=capital]').click()");
assert.equal(await evaluate("document.querySelector('[data-tool-panel=capital]').classList.contains('tool-section-active')"), true);
await capture("tablet-landscape-tools.png");
await evaluate("document.querySelector('#tools-close').click()");

await chooseGlobalSystem("Jita");
const canvas = await evaluate("document.querySelector('#map-canvas').getBoundingClientRect().toJSON()");
const center = { x: canvas.left + canvas.width / 2, y: canvas.top + canvas.height / 2 };

const beforePan = await diagnostics();
await touch("touchStart", [{ x: center.x, y: center.y }]);
await touch("touchMove", [{ x: center.x + 90, y: center.y + 35 }]);
await touch("touchEnd", []);
await delay(100);
const afterPan = await diagnostics();
assert.ok(Math.abs(afterPan.centerX - beforePan.centerX) + Math.abs(afterPan.centerY - beforePan.centerY) > 0.01);
assert.equal(afterPan.selectedSystemId, 30000142, "drag must not create a phantom selection");

await chooseGlobalSystem("Jita");
const beforePinch = await diagnostics();
await touch("touchStart", [
  { x: center.x - 65, y: center.y },
  { x: center.x + 65, y: center.y },
]);
await touch("touchMove", [
  { x: center.x - 125, y: center.y + 20 },
  { x: center.x + 125, y: center.y + 20 },
]);
await touch("touchEnd", []);
await delay(120);
const afterPinch = await diagnostics();
assert.ok(afterPinch.zoom > beforePinch.zoom * 1.5, `pinch zoom ${beforePinch.zoom} -> ${afterPinch.zoom}`);
assert.ok(afterPinch.maxPinchFocalDriftPx < 0.75, `pinch focal drift ${afterPinch.maxPinchFocalDriftPx}px`);
assert.equal(afterPinch.selectedSystemId, 30000142, "pinch must not create a phantom tap");

await chooseGlobalSystem("Jita");
await touch("touchStart", [{ x: center.x, y: center.y }]);
await delay(650);
await waitFor("!document.querySelector('#system-actions').classList.contains('hidden')", 2_000);
assert.equal(await evaluate("document.querySelector('#system-actions-name').textContent"), "Jita");
await touch("touchEnd", []);
await evaluate("document.querySelector('#action-route-from').click()");
assert.equal(await evaluate("document.querySelector('#route-from').value"), "Jita");
assert.equal(await evaluate("document.documentElement.classList.contains('tools-open')"), true);
await evaluate("document.querySelector('#tools-close').click()");

await setViewport(800, 1280, 2);
await reloadAndReady();
await evaluate("document.querySelector('#tools-toggle').click()");
await delay(250);
const portrait = await evaluate(`(() => {
  const panel = document.querySelector('#tools-panel').getBoundingClientRect();
  const canvas = document.querySelector('#map-canvas').getBoundingClientRect();
  return { panel: panel.toJSON(), canvas: canvas.toJSON(), dpr: globalThis.eveWebClientDiagnostics().effectiveDpr };
})()`);
assert.ok(portrait.panel.width >= 790, `portrait sheet width ${portrait.panel.width}`);
assert.ok(portrait.panel.top > 250, `portrait sheet top ${portrait.panel.top}`);
assert.ok(portrait.canvas.width >= 790, `portrait canvas width ${portrait.canvas.width}`);
assert.equal(portrait.dpr, 2);
await capture("tablet-portrait-tools.png");
await evaluate("document.querySelector('#tools-close').click()");

await send("Network.emulateNetworkConditions", {
  offline: true,
  latency: 0,
  downloadThroughput: 0,
  uploadThroughput: 0,
  connectionType: "none",
});
await send("Network.overrideNetworkState", {
  offline: true,
  latency: 0,
  downloadThroughput: 0,
  uploadThroughput: 0,
  connectionType: "none",
});
await reloadAndReady();
if (await evaluate("document.querySelector('#network-status').textContent !== 'Offline'")) {
  // CDP network emulation keeps navigator.onLine=true in some headless Chromium builds.
  // The reload above is the real offline-cache assertion; dispatch the browser lifecycle event separately for UI coverage.
  await evaluate("window.dispatchEvent(new Event('offline'))");
}
await waitFor("document.querySelector('#network-status').textContent === 'Offline'", 3_000);
assert.match(await evaluate("document.querySelector('#shared-status').textContent"), /Offline/);
assert.equal(await evaluate("document.querySelector('#map-stats').textContent.includes('8490 systems')"), true);

await send("Network.emulateNetworkConditions", {
  offline: false,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
  connectionType: "wifi",
});
await send("Network.overrideNetworkState", {
  offline: false,
  latency: 0,
  downloadThroughput: -1,
  uploadThroughput: -1,
  connectionType: "wifi",
});
await reloadAndReady();
await waitFor("document.querySelector('#network-status').textContent === 'Online'", 5_000);

const result = {
  pwa: { display: manifestData.display, icons: manifestData.icons.map((icon) => icon.sizes) },
  cacheHeaders,
  landscapeCanvas: `${Math.round(landscape.canvas.width)}x${Math.round(landscape.canvas.height)}`,
  portraitCanvas: `${Math.round(portrait.canvas.width)}x${Math.round(portrait.canvas.height)}`,
  effectiveDpr: portrait.dpr,
  pinchZoomFactor: Math.round((afterPinch.zoom / beforePinch.zoom) * 100) / 100,
  pinchFocalDriftPx: afterPinch.maxPinchFocalDriftPx,
  maxRenderMillis: afterPinch.maxRenderMillis,
  longRenderCount: afterPinch.longRenderCount,
  offlineReady: true,
};
console.log(JSON.stringify(result));
await send("Browser.close");
socket.close();

async function setViewport(width, height, deviceScaleFactor) {
  await send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor,
    mobile: true,
    screenWidth: width,
    screenHeight: height,
    screenOrientation: width > height
      ? { type: "landscapePrimary", angle: 90 }
      : { type: "portraitPrimary", angle: 0 },
  });
}

async function reloadAndReady() {
  const priorTimeOrigin = await evaluate("performance.timeOrigin");
  await send("Page.reload", { ignoreCache: false });
  await waitFor(`performance.timeOrigin !== ${JSON.stringify(priorTimeOrigin)} && document.documentElement.classList.contains('app-ready')`, 25_000);
}

async function chooseGlobalSystem(name) {
  await evaluate(`(() => {
    const input = document.querySelector('#global-search');
    input.value = ${JSON.stringify(name)};
    input.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector('#global-results .search-result')?.click();
  })()`);
  await waitFor(`document.querySelector('#system-info h2')?.textContent === ${JSON.stringify(name)}`, 5_000);
  await delay(80);
}

async function diagnostics() {
  return evaluate("globalThis.eveWebClientDiagnostics()");
}

async function touch(type, touchPoints) {
  await send("Input.dispatchTouchEvent", { type, touchPoints });
}

async function capture(fileName) {
  if (!screenshotDirectory) return;
  await mkdir(screenshotDirectory, { recursive: true });
  const screenshot = await send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
  await writeFile(resolve(screenshotDirectory, fileName), Buffer.from(screenshot.data, "base64"));
}

async function waitForPage() {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    try {
      const pages = await fetch(`${endpoint}/json/list`).then((response) => response.json());
      const match = pages.find((candidate) => candidate.type === "page" && candidate.url === expectedPageUrl);
      if (match) return match;
    } catch {
      // Chromium may still be starting.
    }
    await delay(100);
  }
  throw new Error(`Chromium page ${expectedPageUrl} was not available on ${endpoint}`);
}

function send(method, params = {}) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    socket.send(JSON.stringify({ id, method, params }));
  });
}

async function evaluate(expression) {
  const result = await send("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.exception?.description ?? result.exceptionDetails.text);
  return result.result.value;
}

async function waitFor(expression, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await evaluate(`Boolean(${expression})`)) return;
    await delay(100);
  }
  const state = await evaluate(`(async () => {
    const registration = await navigator.serviceWorker?.getRegistration();
    let manifestProbe;
    try {
      const response = await fetch('./data/manifest.json', {
        cache: 'no-cache',
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(2000)
      });
      const body = await response.text();
      manifestProbe = { ok: response.ok, status: response.status, bodyLength: body.length };
    } catch (error) {
      manifestProbe = { error: String(error) };
    }
    return {
      status: document.querySelector('#boot-status')?.textContent,
      fatal: document.querySelector('#fatal-error')?.textContent,
      online: navigator.onLine,
      readyState: document.readyState,
      runtimeReady: typeof globalThis.startEveWebClient,
      controller: navigator.serviceWorker?.controller?.state,
      activeWorker: registration?.active?.state,
      manifestProbe,
      cacheKeys: await caches.keys(),
      dataCacheRequests: await caches.open('eve-static-map-data-v1')
        .then((cache) => cache.keys())
        .then((requests) => requests.map((request) => request.url)),
      cachedPwaRuntime: await caches.open('eve-static-map-app-phase4-v1')
        .then((cache) => cache.match('./pwa-runtime.mjs'))
        .then(async (response) => {
          const body = await response?.text();
          return { length: body?.length, hasPackCacheExport: body?.includes('export async function cacheWebPackForOffline') };
        }),
      resources: performance.getEntriesByType('resource')
        .filter((entry) => entry.name.includes('/data/manifest.json') || entry.name.endsWith('/web-client.js'))
        .map((entry) => ({ name: entry.name, duration: entry.duration }))
    };
  })()`);
  throw new Error(`Timed out waiting for ${expression}; ${JSON.stringify({ state, protocolEvents })}`);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
