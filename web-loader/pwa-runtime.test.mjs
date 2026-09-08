import assert from "node:assert/strict";
import test from "node:test";
import { cacheWebPackForOffline, registerPwa } from "../web-client/src/jsMain/resources/pwa-runtime.mjs";

test("reports unsupported service workers without throwing", async () => {
  installGlobal("navigator", {});
  let reported = null;

  const result = await registerPwa({ onError: (error) => { reported = error; } });

  assert.equal(result, null);
  assert.match(reported?.message ?? "", /unavailable/);
});

test("offers a waiting update and reloads only after user applies it", async () => {
  const messages = [];
  let reloads = 0;
  const waiting = new EventTarget();
  waiting.postMessage = (message) => messages.push(message);
  const registration = new EventTarget();
  registration.waiting = waiting;
  registration.installing = null;
  const serviceWorker = new EventTarget();
  serviceWorker.controller = {};
  serviceWorker.register = async () => registration;
  installGlobal("navigator", { serviceWorker });
  installGlobal("location", { reload: () => { reloads += 1; } });
  let offered = null;

  await registerPwa({ onUpdateReady: (update) => { offered = update; } });

  assert.ok(offered);
  assert.equal(reloads, 0);
  offered.applyUpdate();
  assert.deepEqual(messages, [{ type: "SKIP_WAITING" }]);
  assert.equal(reloads, 0);
  serviceWorker.dispatchEvent(new Event("controllerchange"));
  assert.equal(reloads, 1);
});

test("asks the active service worker to persist the verified published pack", async () => {
  const messages = [];
  const active = { postMessage: (message) => messages.push(message) };
  installGlobal("navigator", { serviceWorker: { ready: Promise.resolve({ active }) } });
  installGlobal("location", { href: "https://map.example.test/planner/" });
  const manifest = { fileName: "web-pack-fixture-1.json.gz", packVersion: "fixture-1" };

  assert.equal(await cacheWebPackForOffline(manifest), true);
  assert.deepEqual(messages, [{
    type: "CACHE_WEB_PACK",
    manifest,
    manifestUrl: "https://map.example.test/planner/data/manifest.json",
    packUrl: "https://map.example.test/planner/data/web-pack-fixture-1.json.gz",
  }]);
});

function installGlobal(name, value) {
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}
