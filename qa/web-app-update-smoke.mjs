import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";

const debugPort = Number(process.argv[2] ?? 9232);
const expectedPageUrl = process.argv[3] ?? "http://127.0.0.1:8767/";
const siteDirectory = resolve(process.argv[4] ?? ".sde-work/phase4-app-update-site");
const endpoint = `http://127.0.0.1:${debugPort}`;
const page = await waitForPage();
const socket = new WebSocket(page.webSocketDebuggerUrl);
const pending = new Map();
let nextId = 1;

await new Promise((resolveOpen, reject) => {
  socket.addEventListener("open", resolveOpen, { once: true });
  socket.addEventListener("error", reject, { once: true });
});
socket.addEventListener("message", (event) => {
  const message = JSON.parse(event.data);
  if (!message.id) return;
  const handler = pending.get(message.id);
  if (!handler) return;
  pending.delete(message.id);
  if (message.error) handler.reject(new Error(message.error.message));
  else handler.resolve(message.result);
});

await send("Runtime.enable");
await send("Page.enable");
await waitFor("document.documentElement.classList.contains('app-ready')", 25_000);
await waitFor("navigator.serviceWorker?.controller != null", 15_000);
await reloadAndReady();
assert.equal(await evaluate("globalThis.__qaShellRevision"), undefined);

const workerPath = resolve(siteDirectory, "service-worker.js");
const runtimePath = resolve(siteDirectory, "pwa-runtime.mjs");
const workerV2 = await readFile(workerPath, "utf8");
const runtimeV2 = await readFile(runtimePath, "utf8");
const cacheMatch = workerV2.match(/const APP_CACHE = "([^"]+)";/);
assert.ok(cacheMatch, "service-worker.js must declare APP_CACHE");
const cacheV2 = cacheMatch[1];
const cacheV3 = `${cacheV2}-qa-next`;
await writeFile(workerPath, workerV2.replace(cacheMatch[0], `const APP_CACHE = "${cacheV3}";`));
await writeFile(runtimePath, `${runtimeV2}\nglobalThis.__qaShellRevision = "qa-next";\n`);

await evaluate("navigator.serviceWorker.getRegistration().then((registration) => registration.update())");
await waitFor("!document.querySelector('#app-update').classList.contains('hidden')", 15_000);
assert.equal(await evaluate("globalThis.__qaShellRevision"), undefined, "waiting update must not mutate the active shell");

// An ordinary reload must remain on the internally consistent old shell until the user accepts the update.
await reloadAndReady();
assert.equal(await evaluate("globalThis.__qaShellRevision"), undefined);
await waitFor("!document.querySelector('#app-update').classList.contains('hidden')", 5_000);

const previousTimeOrigin = await evaluate("performance.timeOrigin");
await evaluate("document.querySelector('#app-update-reload').click()");
await waitFor(
  `performance.timeOrigin !== ${JSON.stringify(previousTimeOrigin)} && document.documentElement.classList.contains('app-ready')`,
  25_000,
);
assert.equal(await evaluate("globalThis.__qaShellRevision"), "qa-next");
const cacheKeys = await evaluate("caches.keys()");
assert.ok(cacheKeys.includes(cacheV3));
assert.ok(!cacheKeys.includes(cacheV2));

console.log(JSON.stringify({
  oldShellCache: cacheV2,
  newShellCache: cacheV3,
  waitingUpdatePrompted: true,
  ordinaryReloadStayedConsistent: true,
  userApprovedReload: true,
}));
await send("Browser.close");
socket.close();

async function reloadAndReady() {
  const previousTimeOrigin = await evaluate("performance.timeOrigin");
  await send("Page.reload", { ignoreCache: false });
  await waitFor(
    `performance.timeOrigin !== ${JSON.stringify(previousTimeOrigin)} && document.documentElement.classList.contains('app-ready')`,
    25_000,
  );
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
  return new Promise((resolveResult, reject) => {
    pending.set(id, { resolve: resolveResult, reject });
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
  throw new Error(`Timed out waiting for ${expression}`);
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}
