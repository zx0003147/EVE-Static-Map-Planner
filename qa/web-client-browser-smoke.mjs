import assert from "node:assert/strict";
import { gunzipSync } from "node:zlib";

const debugPort = Number(process.argv[2] ?? 9223);
const expectedPageUrl = process.argv[3] ?? "http://127.0.0.1:8765/";
const expectedPackAnsiblex = Number(process.argv[4] ?? 103);
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
assert.match(ready.stats, new RegExp(`${expectedPackAnsiblex} Pack Ansiblex`));

const packManifest = await fetch(new URL("data/manifest.json", expectedPageUrl)).then((response) => response.json());
const packBytes = await fetch(new URL(`data/${packManifest.fileName}`, expectedPageUrl))
  .then((response) => response.arrayBuffer());
const publishedPack = JSON.parse(gunzipSync(Buffer.from(packBytes)).toString("utf8"));
const enabledPackAnsiblex = publishedPack.payload.ansiblexLinks.filter((link) => link.enabled);
assert.equal(enabledPackAnsiblex.length, expectedPackAnsiblex);
const systemNames = new Map(publishedPack.payload.systems.map((system) => [system.id, system.name]));
const productionAnsiblex = enabledPackAnsiblex[0];
assert.ok(productionAnsiblex, "production Web Pack must contain an enabled Ansiblex");
const ansiblexFrom = productionAnsiblex.direction === "SECOND_TO_FIRST"
  ? systemNames.get(productionAnsiblex.secondSystemId)
  : systemNames.get(productionAnsiblex.firstSystemId);
const ansiblexTo = productionAnsiblex.direction === "SECOND_TO_FIRST"
  ? systemNames.get(productionAnsiblex.firstSystemId)
  : systemNames.get(productionAnsiblex.secondSystemId);
assert.ok(ansiblexFrom && ansiblexTo, "production Ansiblex endpoints must resolve to systems");

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
await waitFor("document.querySelector('#user-message')?.textContent.startsWith('Route ready')", 2_000);
const normalElapsedMs = Date.now() - normalStartedAt;
const routeSummary = await evaluate("document.querySelector('#route-summary').textContent");
assert.match(routeSummary, /Jita → Perimeter/);
await waitFor("document.querySelector('#user-message').classList.contains('hidden')", 4_000);

await evaluate("document.querySelector('#clear-route').click()");
await waitFor("document.querySelector('#user-message')?.textContent === 'Normal route cleared.'", 2_000);
await waitFor("document.querySelector('#user-message').classList.contains('hidden')", 4_000);

await evaluate("document.querySelector('#clear-route').click()");
await delay(2_500);
await evaluate("document.querySelector('#clear-capital').click()");
await delay(700);
assert.equal(await evaluate("document.querySelector('#user-message').textContent"), "Capital route cleared.");
assert.equal(await evaluate("document.querySelector('#user-message').classList.contains('hidden')"), false);
await waitFor("document.querySelector('#user-message').classList.contains('hidden')", 3_000);

await chooseSystem("route-from", "route-from-results", "Jita");
await chooseSystem("route-to", "route-to-results", "Amarr");
await chooseSystem("route-waypoint", "route-waypoint-results", "Perimeter");
await evaluate("document.querySelector('#calculate-route').click()");
await waitFor("document.querySelector('#waypoint-list')?.textContent.includes('Perimeter')", 5_000);
await waitFor("document.querySelector('#route-summary')?.textContent.includes('Amarr')", 5_000);
const waypointSummary = await evaluate("document.querySelector('#route-summary').textContent");
await evaluate("document.querySelector('#waypoint-list .list-row .icon-button:last-child').click()");
await waitFor("document.querySelector('#waypoint-list')?.textContent.includes('No waypoints')", 5_000);

await chooseSystem("route-from", "route-from-results", "Jita");
await chooseSystem("route-to", "route-to-results", "1DQ1-A");
await evaluate("document.querySelector('#clear-route').click()");
await chooseSystem("route-from", "route-from-results", "Jita");
await chooseSystem("route-to", "route-to-results", "1DQ1-A");
const longRouteStartedAt = Date.now();
await evaluate("document.querySelector('#calculate-route').click()");
await waitFor("/^\\d+ jumps/.test(document.querySelector('#route-summary')?.textContent ?? '')", 5_000);
const longRouteElapsedMs = Date.now() - longRouteStartedAt;
const longRouteSummary = await evaluate("document.querySelector('#route-summary').textContent");
assert.match(longRouteSummary, /jumps/);

await chooseSystem("route-from", "route-from-results", ansiblexFrom);
await chooseSystem("route-to", "route-to-results", ansiblexTo);
await evaluate(`(() => {
  const toggle = document.querySelector('#use-ansiblex');
  toggle.checked = true;
  toggle.dispatchEvent(new Event('change', { bubbles: true }));
  document.querySelector('#calculate-route').click();
})()`);
await waitFor("document.querySelector('#route-summary')?.textContent.includes('1 Ansiblex')", 5_000);
const ansiblexSummary = await evaluate("document.querySelector('#route-summary').textContent");

await chooseSystem("capital-from", "capital-from-results", "1DQ1-A");
await chooseSystem("capital-to", "capital-to-results", "T5ZI-S");
const capitalStartedAt = Date.now();
await evaluate("document.querySelector('#calculate-capital').click()");
await waitFor("document.querySelector('#capital-summary')?.textContent.includes('1 jumps')", 5_000);
const capitalElapsedMs = Date.now() - capitalStartedAt;
const capitalSummary = await evaluate("document.querySelector('#capital-summary').textContent");
assert.match(capitalSummary, /1DQ1-A → T5ZI-S/);

await chooseSystem("jump-source", "jump-source-results", "Jita");
await evaluate(`(() => {
  const input = document.querySelector('#coverage-range');
  input.value = '4';
  input.dispatchEvent(new Event('change', { bubbles: true }));
  document.querySelector('#add-jump-range').click();
})()`);
await waitFor("!document.querySelector('#overlay-list').classList.contains('empty')", 5_000);
await chooseSystem("jump-source", "jump-source-results", "Perimeter");
await evaluate(`(() => {
  const input = document.querySelector('#coverage-range');
  input.value = '6';
  input.dispatchEvent(new Event('change', { bubbles: true }));
  document.querySelector('#add-jump-range').click();
})()`);
await waitFor("document.querySelectorAll('#overlay-list .overlay-row').length === 2", 5_000);
const overlaySummary = await evaluate("document.querySelector('#overlay-list').textContent");
assert.match(overlaySummary, /Jita.*4\.00 LY/);
assert.match(overlaySummary, /Perimeter.*6\.00 LY/);
await evaluate(`(() => {
  const input = document.querySelector('#coverage-range');
  input.value = '10';
  input.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
assert.match(await evaluate("document.querySelector('#overlay-list').textContent"), /Jita.*4\.00 LY/);
const coverageSummary = await evaluate("document.querySelector('#coverage-summary').textContent");
assert.match(coverageSummary, /overlapping/);

await assertInvalidCoverage("not-a-number", "Coverage range must be a number.");
await assertInvalidCoverage("0", "Coverage range must be between 0 and 50 LY.");
await assertInvalidCoverage("50.01", "Coverage range must be between 0 and 50 LY.");

await evaluate(`(() => {
  document.querySelector('#constellation-threshold').value = '3.5';
  document.querySelector('#system-threshold').value = '8';
  document.querySelector('#save-map-lod').click();
})()`);
assert.equal(await evaluate("localStorage.getItem('eve-static-map-planner.web-map-preferences.v1')"), "3.5|8");
await evaluate("document.querySelector('#reset-map-lod').click()");
assert.equal(await evaluate("localStorage.getItem('eve-static-map-planner.web-map-preferences.v1')"), null);

await evaluate(`(() => {
  const transfer = new DataTransfer();
  transfer.items.add(new File([
    ${JSON.stringify(`from,to,direction,enabled\n${ansiblexFrom},${ansiblexTo},${productionAnsiblex.direction},true`)}
  ], 'pack-duplicate.csv', { type: 'text/csv' }));
  const input = document.querySelector('#personal-ansiblex-file');
  input.files = transfer.files;
  input.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await waitFor("document.querySelector('#personal-ansiblex-preview')?.textContent.includes('1 duplicate')", 5_000);
assert.equal(await evaluate("document.querySelector('#apply-personal-ansiblex').disabled"), true);
await evaluate("document.querySelector('#cancel-personal-ansiblex').click()");

await evaluate(`(() => {
  const transfer = new DataTransfer();
  transfer.items.add(new File([
    'from,to,direction,enabled\\nJita,Perimeter,FORWARD,true'
  ], 'personal.csv', { type: 'text/csv' }));
  const input = document.querySelector('#personal-ansiblex-file');
  input.files = transfer.files;
  input.dispatchEvent(new Event('change', { bubbles: true }));
})()`);
await waitFor("document.querySelector('#personal-ansiblex-preview')?.textContent.includes('1 valid')", 5_000);
await evaluate("document.querySelector('#apply-personal-ansiblex').click()");
await waitFor("document.querySelector('#personal-ansiblex-list')?.textContent.includes('Jita')", 5_000);
const personalAnsiblexSummary = await evaluate("document.querySelector('#personal-ansiblex-list').textContent");
assert.match(personalAnsiblexSummary, /Jita.*Perimeter/);

await evaluate(`(() => {
  const input = document.querySelector('#global-search');
  input.value = 'Jita';
  input.dispatchEvent(new Event('input', { bubbles: true }));
  document.querySelector('#global-results .search-result')?.click();
})()`);
await waitFor("document.querySelector('#system-info h2')?.textContent === 'Jita'", 5_000);
await evaluate(`(() => {
  const canvas = document.querySelector('#map-canvas');
  const bounds = canvas.getBoundingClientRect();
  canvas.dispatchEvent(new MouseEvent('contextmenu', {
    clientX: bounds.left + bounds.width / 2,
    clientY: bounds.top + bounds.height / 2,
    bubbles: true,
    cancelable: true
  }));
})()`);
await waitFor("!document.querySelector('#system-actions').classList.contains('hidden')", 2_000);
await evaluate("document.querySelector('#action-keepstar-marker').click()");
await waitFor("document.querySelector('#system-info')?.textContent.includes('keepstar')", 2_000);

await evaluate(`(() => {
  document.querySelector('#fit-map').click();
  document.querySelector('#map-canvas').dispatchEvent(new WheelEvent('wheel', {
    deltaY: -120, clientX: 600, clientY: 400, bubbles: true, cancelable: true
  }));
})()`);
await new Promise((resolve) => setTimeout(resolve, 100));
const loadTimings = await evaluate("globalThis.eveWebPerformance");
const mapDiagnostics = await evaluate("globalThis.eveWebClientDiagnostics()");

console.log(JSON.stringify({
  status: ready.status,
  stats: ready.stats,
  sharedStatus: ready.sharedStatus,
  readyElapsedMs,
  normalElapsedMs,
  routeSummary,
  waypointSummary,
  longRouteElapsedMs,
  longRouteSummary,
  ansiblexSummary,
  productionAnsiblex: { from: ansiblexFrom, to: ansiblexTo, count: enabledPackAnsiblex.length },
  capitalElapsedMs,
  capitalSummary,
  overlaySummary,
  coverageSummary,
  personalAnsiblexSummary,
  loadTimings,
  mapDiagnostics
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

async function assertInvalidCoverage(inputValue, expectedError) {
  const before = await evaluate(`({
    rows: document.querySelectorAll('#overlay-list .overlay-row').length,
    overlays: document.querySelector('#overlay-list').textContent,
    summary: document.querySelector('#coverage-summary').textContent
  })`);
  await evaluate(`(() => {
    document.querySelector('#coverage-range').value = ${JSON.stringify(inputValue)};
    document.querySelector('#add-jump-range').click();
  })()`);
  await waitFor(`document.querySelector('#user-message')?.textContent === ${JSON.stringify(expectedError)}`, 2_000);
  await delay(100);
  const after = await evaluate(`({
    rows: document.querySelectorAll('#overlay-list .overlay-row').length,
    overlays: document.querySelector('#overlay-list').textContent,
    summary: document.querySelector('#coverage-summary').textContent,
    errorVisible: document.querySelector('#user-message').classList.contains('error') &&
      !document.querySelector('#user-message').classList.contains('hidden')
  })`);
  assert.deepEqual(after.rows, before.rows, `invalid Coverage ${inputValue} must not add an overlay`);
  assert.deepEqual(after.overlays, before.overlays, `invalid Coverage ${inputValue} must not change existing overlays`);
  assert.deepEqual(after.summary, before.summary, `invalid Coverage ${inputValue} must not change coverage summary`);
  assert.equal(after.errorVisible, true);
  await delay(3_200);
  assert.equal(await evaluate("document.querySelector('#user-message').textContent"), expectedError);
  assert.equal(await evaluate("document.querySelector('#user-message').classList.contains('hidden')"), false);
  await waitFor("document.querySelector('#user-message').classList.contains('hidden')", 3_300);
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
  const pageState = await evaluate(`({
    bootStatus: document.querySelector('#boot-status')?.textContent,
    fatalError: document.querySelector('#fatal-error')?.textContent,
    readyState: document.readyState
  })`);
  throw new Error(`Timed out waiting for: ${expression}; page state: ${JSON.stringify(pageState)}`);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
