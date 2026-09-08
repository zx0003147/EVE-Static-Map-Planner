import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { gzipSync, gunzipSync } from "node:zlib";

const debugPort = Number(process.argv[2] ?? 9231);
const expectedPageUrl = process.argv[3] ?? "http://127.0.0.1:8766/";
const dataDirectory = resolve(process.argv[4] ?? ".sde-work/phase4-update-site/data");
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
const manifestA = JSON.parse(await readFile(resolve(dataDirectory, "manifest.json"), "utf8"));
assert.match(await evaluate("document.querySelector('#pack-meta').textContent"), new RegExp(escapeRegex(manifestA.packVersion)));

const packedA = await readFile(resolve(dataDirectory, manifestA.fileName));
const documentB = JSON.parse(gunzipSync(packedA).toString("utf8"));
const packVersionB = `${manifestA.packVersion}-qa-b`;
const generatedAtB = new Date(Date.parse(manifestA.generatedAt) + 1000).toISOString();
documentB.packVersion = packVersionB;
documentB.generatedAt = generatedAtB;
const packedB = gzipSync(Buffer.from(JSON.stringify(documentB), "utf8"), { level: 9 });
const fileNameB = `web-pack-${packVersionB}.json.gz`;
const manifestB = {
  ...manifestA,
  packVersion: packVersionB,
  generatedAt: generatedAtB,
  fileName: fileNameB,
  sizeBytes: packedB.byteLength,
  sha256: createHash("sha256").update(packedB).digest("hex"),
};

// Deployment order is intentional: immutable versioned Pack first, stable manifest last.
await writeFile(resolve(dataDirectory, fileNameB), packedB);
await writeFile(resolve(dataDirectory, "manifest.json"), JSON.stringify(manifestB));

const previousTimeOrigin = await evaluate("performance.timeOrigin");
await send("Page.reload", { ignoreCache: false });
await waitFor(
  `performance.timeOrigin !== ${JSON.stringify(previousTimeOrigin)} && document.documentElement.classList.contains('app-ready')`,
  25_000,
);
const packMetaB = await evaluate("document.querySelector('#pack-meta').textContent");
assert.match(packMetaB, new RegExp(escapeRegex(packVersionB)));
assert.equal(await evaluate("document.querySelector('#fatal-error').textContent"), "");
assert.equal(await evaluate("document.querySelector('#map-stats').textContent.includes('8490 systems')"), true);

console.log(JSON.stringify({
  packA: manifestA.packVersion,
  packB: packVersionB,
  counts: manifestB.counts,
  automaticDiscovery: true,
  serviceWorkerControlled: await evaluate("navigator.serviceWorker.controller != null"),
}));
await send("Browser.close");
socket.close();

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

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function delay(milliseconds) {
  return new Promise((resolveDelay) => setTimeout(resolveDelay, milliseconds));
}
