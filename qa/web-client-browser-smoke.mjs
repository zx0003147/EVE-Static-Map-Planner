import assert from "node:assert/strict";

const debugPort = Number(process.argv[2] ?? 9223);
const expectedPageUrl = process.argv[3] ?? "http://127.0.0.1:8765/";
const endpoint = `http://127.0.0.1:${debugPort}`;

const page = await waitForPage();
const socket = new WebSocket(page.webSocketDebuggerUrl);
const pending = new Map();
let nextId = 1;

await new Promise((resolve, reject) => {
  socket.addEventListener("open", resolve, { once: true });
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
await waitFor("document.documentElement.classList.contains('app-ready')", 20_000);
const readyElapsedMs = await evaluate("Math.round(performance.now())");

const ready = await evaluate(`({
  status: document.querySelector('#boot-status')?.textContent,
  fatal: document.querySelector('#fatal-error')?.textContent,
  stats: document.querySelector('#map-stats')?.textContent,
  canvasWidth: document.querySelector('#map-canvas')?.width,
  canvasHeight: document.querySelector('#map-canvas')?.height,
  sharedStatus: document.querySelector('#shared-status')?.textContent,
  sharedInviteType: document.querySelector('#shared-invite-code')?.type,
  sharedList: document.querySelector('#shared-marker-list')?.textContent
})`);
assert.equal(ready.status, "Ready");
assert.equal(ready.fatal, "");
assert.match(ready.stats, /8490 systems/);
assert.ok(ready.canvasWidth > 0 && ready.canvasHeight > 0);
assert.equal(ready.sharedStatus, "Disconnected");
assert.equal(ready.sharedInviteType, "password");
assert.match(ready.sharedList, /Connect to load markers/);

await evaluate(`(() => {
  document.querySelector('#shared-server-url').value = 'https://marker.example.com/has-a-path';
  document.querySelector('#shared-invite-code').value = 'not-a-real-invite';
  document.querySelector('#shared-connect').click();
})()`);
await waitFor("document.querySelector('#shared-error')?.textContent.includes('origin')", 5_000);
assert.equal(await evaluate("document.querySelector('#shared-invite-code').value"), "");
await evaluate("document.querySelector('#shared-disconnect').click()");

await evaluate(`(() => {
  const input = document.querySelector('#global-search');
  input.value = 'Jita';
  input.dispatchEvent(new Event('input', { bubbles: true }));
  document.querySelector('#global-results .search-result')?.click();
})()`);
await waitFor("document.querySelector('#system-info h2')?.textContent === 'Jita'", 5_000);

await chooseSystem("route-from", "route-from-results", "Jita");
await chooseSystem("route-to", "route-to-results", "Perimeter");
const normalStartedAt = Date.now();
await evaluate("document.querySelector('#calculate-route').click()");
await waitFor("document.querySelector('#route-summary')?.textContent.includes('1 jumps')", 5_000);
const normalElapsedMs = Date.now() - normalStartedAt;
const routeSummary = await evaluate("document.querySelector('#route-summary').textContent");
assert.match(routeSummary, /Jita → Perimeter/);

await chooseSystem("capital-from", "capital-from-results", "1DQ1-A");
await chooseSystem("capital-to", "capital-to-results", "T5ZI-S");
const capitalStartedAt = Date.now();
await evaluate("document.querySelector('#calculate-capital').click()");
await waitFor("document.querySelector('#capital-summary')?.textContent.includes('1 jumps')", 5_000);
const capitalElapsedMs = Date.now() - capitalStartedAt;
const capitalSummary = await evaluate("document.querySelector('#capital-summary').textContent");
assert.match(capitalSummary, /1DQ1-A → T5ZI-S/);

await chooseSystem("jump-source", "jump-source-results", "Jita");
await evaluate("document.querySelector('#add-jump-range').click()");
await waitFor("!document.querySelector('#overlay-list').classList.contains('empty')", 5_000);
const coverageSummary = await evaluate("document.querySelector('#coverage-summary').textContent");
assert.match(coverageSummary, /Coverage:/);

await evaluate(`(() => {
  document.querySelector('#fit-map').click();
  document.querySelector('#map-canvas').dispatchEvent(new WheelEvent('wheel', {
    deltaY: -120, clientX: 600, clientY: 400, bubbles: true, cancelable: true
  }));
})()`);

console.log(JSON.stringify({
  status: ready.status,
  stats: ready.stats,
  sharedStatus: ready.sharedStatus,
  readyElapsedMs,
  normalElapsedMs,
  routeSummary,
  capitalElapsedMs,
  capitalSummary,
  coverageSummary
}));
await send("Browser.close");
socket.close();

async function chooseSystem(inputId, resultsId, name) {
  await evaluate(`(() => {
    const input = document.querySelector('#${inputId}');
    input.value = ${JSON.stringify(name)};
    input.dispatchEvent(new Event('input', { bubbles: true }));
    document.querySelector('#${resultsId} .search-result')?.click();
  })()`);
  await waitFor(`document.querySelector('#${inputId}').value === ${JSON.stringify(name)}`, 5_000);
}

async function waitForPage() {
  const deadline = Date.now() + 15_000;
  while (Date.now() < deadline) {
    try {
      const pages = await fetch(`${endpoint}/json/list`).then((response) => response.json());
      const match = pages.find((candidate) => candidate.type === "page" && candidate.url === expectedPageUrl);
      if (match) return match;
    } catch {
      // Chrome may still be starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Chrome page ${expectedPageUrl} was not available on ${endpoint}`);
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
  if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
  return result.result.value;
}

async function waitFor(expression, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await evaluate(`Boolean(${expression})`)) return;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Timed out waiting for: ${expression}`);
}
