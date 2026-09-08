import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import test from "node:test";
import { gzipSync } from "node:zlib";
import { loadWebPack } from "./web-pack-loader.mjs";

if (!globalThis.crypto) Object.defineProperty(globalThis, "crypto", { value: webcrypto });

test("loads manifest and compressed pack without an Apply step", async () => {
  const counts = { systems: 2, stargateLinks: 1, regions: 1, constellations: 1, ansiblexLinks: 1 };
  const document = {
    schemaVersion: 1,
    packVersion: "fixture-1",
    generatedAt: "2026-09-08T01:02:03Z",
    desktopAppVersion: "1.7.0",
    sdeBuild: 3466501,
    counts,
    payload: {
      systems: [{ id: 1 }, { id: 2 }],
      stargateLinks: [{ firstSystemId: 1, secondSystemId: 2 }],
      regions: [{ id: 100 }],
      constellations: [{ id: 10, regionId: 100 }],
      ansiblexLinks: [{ id: "a", firstSystemId: 1, secondSystemId: 2, enabled: true }],
    },
  };
  const compressed = gzipSync(Buffer.from(JSON.stringify(document)));
  const sha256 = await digest(compressed);
  const manifest = {
    schemaVersion: 1,
    packVersion: document.packVersion,
    generatedAt: document.generatedAt,
    desktopAppVersion: document.desktopAppVersion,
    sdeBuild: document.sdeBuild,
    fileName: `web-pack-${document.packVersion}.json.gz`,
    sizeBytes: compressed.byteLength,
    sha256,
    counts,
  };
  const requests = [];
  const fetchImpl = async (url, options) => {
    requests.push({ url, options });
    if (url.endsWith("manifest.json")) return Response.json(manifest);
    if (url.endsWith(manifest.fileName)) return new Response(compressed);
    return new Response(null, { status: 404 });
  };

  const result = await loadWebPack("/data/", fetchImpl);

  assert.deepEqual(result.stats, {
    systems: 2,
    stargateLinks: 1,
    ansiblexLinks: 1,
    sdeBuild: 3466501,
    packVersion: "fixture-1",
  });
  assert.equal(requests[0].url, "/data/manifest.json");
  assert.equal(requests[0].options.cache, "no-cache");
  assert.equal(requests[1].url, `/data/${manifest.fileName}`);
  assert.equal(requests[1].options.cache, "force-cache");
});

test("rejects an unsupported manifest before requesting a pack", async () => {
  let requests = 0;
  const fetchImpl = async () => {
    requests += 1;
    return Response.json({ schemaVersion: 999 });
  };

  await assert.rejects(loadWebPack("/data", fetchImpl), /unsupported/);
  assert.equal(requests, 1);
});

test("rejects a pack whose checksum differs from the manifest", async () => {
  const counts = { systems: 1, stargateLinks: 1, regions: 1, constellations: 1, ansiblexLinks: 0 };
  const compressed = gzipSync(Buffer.from("{}"));
  const manifest = {
    schemaVersion: 1,
    packVersion: "fixture-bad",
    generatedAt: "2026-09-08T01:02:03Z",
    desktopAppVersion: "1.7.0",
    sdeBuild: 3466501,
    fileName: "web-pack-fixture-bad.json.gz",
    sizeBytes: compressed.byteLength,
    sha256: "0".repeat(64),
    counts,
  };
  const fetchImpl = async (url) => url.endsWith("manifest.json")
    ? Response.json(manifest)
    : new Response(compressed);

  await assert.rejects(loadWebPack("/data", fetchImpl), /checksum mismatch/);
});

async function digest(bytes) {
  const value = await webcrypto.subtle.digest("SHA-256", bytes);
  return Buffer.from(value).toString("hex");
}
