export const SUPPORTED_WEB_PACK_SCHEMA_VERSION = 1;

export async function loadWebPack(dataBaseUrl = "./data", fetchImpl = globalThis.fetch, onStatus = () => {}) {
  if (typeof fetchImpl !== "function") throw new Error("Fetch API is unavailable");
  if (typeof onStatus !== "function") throw new Error("Web Pack status listener must be a function");

  const startedAt = now();
  const timings = {};
  onStatus("Loading manifest", timings);
  const manifestUrl = joinUrl(dataBaseUrl, "manifest.json");
  let stageStartedAt = now();
  const manifestResponse = await fetchImpl(manifestUrl, {
    cache: "no-cache",
    headers: { Accept: "application/json" },
  });
  timings.manifestFetchMs = elapsed(stageStartedAt);
  requireOk(manifestResponse, "manifest", manifestUrl);
  timings.offlineFallback = manifestResponse.headers?.get?.("X-EVE-Offline-Fallback") === "true";
  stageStartedAt = now();
  const manifest = await manifestResponse.json();
  validateManifest(manifest);
  timings.manifestParseValidateMs = elapsed(stageStartedAt);

  onStatus("Loading Web Pack", timings);
  const packUrl = joinUrl(dataBaseUrl, manifest.fileName);
  stageStartedAt = now();
  const packResponse = await fetchImpl(packUrl, {
    cache: "force-cache",
    headers: { Accept: "application/gzip, application/octet-stream" },
  });
  requireOk(packResponse, "Web Pack", packUrl);
  const compressed = new Uint8Array(await packResponse.arrayBuffer());
  timings.packFetchMs = elapsed(stageStartedAt);
  if (compressed.byteLength !== manifest.sizeBytes) {
    throw new Error(`Web Pack size mismatch: expected ${manifest.sizeBytes}, received ${compressed.byteLength}`);
  }
  onStatus("Checking integrity", timings);
  stageStartedAt = now();
  const checksum = await sha256(compressed);
  timings.checksumMs = elapsed(stageStartedAt);
  if (checksum !== manifest.sha256) {
    throw new Error(`Web Pack checksum mismatch: expected ${manifest.sha256}, received ${checksum}`);
  }

  onStatus("Decompressing", timings);
  stageStartedAt = now();
  const json = await decompressGzip(compressed);
  timings.gzipDecodeMs = elapsed(stageStartedAt);
  onStatus("Parsing data", timings);
  stageStartedAt = now();
  const document = JSON.parse(json);
  timings.jsonParseMs = elapsed(stageStartedAt);
  onStatus("Validating", timings);
  stageStartedAt = now();
  validateDocument(document, manifest);
  timings.validationMs = elapsed(stageStartedAt);
  timings.loaderTotalMs = elapsed(startedAt);
  return {
    manifest,
    document,
    timings,
    stats: {
      systems: document.counts.systems,
      stargateLinks: document.counts.stargateLinks,
      ansiblexLinks: document.counts.ansiblexLinks,
      sdeBuild: document.sdeBuild,
      packVersion: document.packVersion,
    },
  };
}

function now() {
  return globalThis.performance?.now?.() ?? Date.now();
}

function elapsed(startedAt) {
  return Math.round((now() - startedAt) * 100) / 100;
}

export function validateManifest(manifest) {
  requireObject(manifest, "Manifest");
  requireSupportedSchema(manifest.schemaVersion, "Manifest");
  requireSafeVersion(manifest.packVersion);
  if (manifest.fileName !== `web-pack-${manifest.packVersion}.json.gz`) {
    throw new Error("Manifest filename does not match packVersion");
  }
  if (!Number.isSafeInteger(manifest.sdeBuild) || manifest.sdeBuild <= 0) {
    throw new Error("Manifest sdeBuild must be a positive integer");
  }
  if (typeof manifest.generatedAt !== "string" || Number.isNaN(Date.parse(manifest.generatedAt))) {
    throw new Error("Manifest generatedAt must be an ISO-8601 timestamp");
  }
  if (typeof manifest.desktopAppVersion !== "string" || manifest.desktopAppVersion.trim() === "") {
    throw new Error("Manifest desktopAppVersion must not be blank");
  }
  if (!Number.isSafeInteger(manifest.sizeBytes) || manifest.sizeBytes <= 0) {
    throw new Error("Manifest sizeBytes must be a positive integer");
  }
  if (typeof manifest.sha256 !== "string" || !/^[0-9a-f]{64}$/.test(manifest.sha256)) {
    throw new Error("Manifest sha256 is invalid");
  }
  validateCounts(manifest.counts, "Manifest");
}

export function validateDocument(document, manifest) {
  requireObject(document, "Web Pack");
  requireSupportedSchema(document.schemaVersion, "Web Pack");
  requireSafeVersion(document.packVersion);
  if (document.packVersion !== manifest.packVersion) throw new Error("Manifest and Web Pack versions differ");
  if (document.sdeBuild !== manifest.sdeBuild) throw new Error("Manifest and Web Pack SDE builds differ");
  if (document.generatedAt !== manifest.generatedAt) throw new Error("Manifest and Web Pack timestamps differ");
  if (document.desktopAppVersion !== manifest.desktopAppVersion) {
    throw new Error("Manifest and Web Pack Desktop versions differ");
  }
  validateCounts(document.counts, "Web Pack");
  requireObject(document.payload, "Web Pack payload");

  const collections = ["systems", "stargateLinks", "regions", "constellations", "ansiblexLinks"];
  for (const name of collections) {
    if (!Array.isArray(document.payload[name])) throw new Error(`Web Pack payload.${name} must be an array`);
  }
  const actualCounts = {
    systems: document.payload.systems.length,
    stargateLinks: document.payload.stargateLinks.length,
    regions: document.payload.regions.length,
    constellations: document.payload.constellations.length,
    ansiblexLinks: document.payload.ansiblexLinks.length,
  };
  if (!countsEqual(actualCounts, document.counts)) {
    throw new Error("Web Pack counts do not match its payload");
  }
  if (!countsEqual(document.counts, manifest.counts)) {
    throw new Error("Manifest counts do not match the Web Pack");
  }
}

function countsEqual(first, second) {
  return ["systems", "stargateLinks", "regions", "constellations", "ansiblexLinks"]
    .every((name) => first[name] === second[name]);
}

function validateCounts(counts, subject) {
  requireObject(counts, `${subject} counts`);
  for (const name of ["systems", "stargateLinks", "regions", "constellations"]) {
    if (!Number.isSafeInteger(counts[name]) || counts[name] <= 0) {
      throw new Error(`${subject} counts.${name} must be a positive integer`);
    }
  }
  if (!Number.isSafeInteger(counts.ansiblexLinks) || counts.ansiblexLinks < 0) {
    throw new Error(`${subject} counts.ansiblexLinks must be a non-negative integer`);
  }
}

function requireSupportedSchema(schemaVersion, subject) {
  if (schemaVersion !== SUPPORTED_WEB_PACK_SCHEMA_VERSION) {
    throw new Error(
      `${subject} schemaVersion ${schemaVersion} is unsupported; expected ${SUPPORTED_WEB_PACK_SCHEMA_VERSION}`,
    );
  }
}

function requireSafeVersion(packVersion) {
  if (typeof packVersion !== "string" || !/^[A-Za-z0-9._-]+$/.test(packVersion)) {
    throw new Error("packVersion is blank or unsafe");
  }
}

function requireObject(value, subject) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${subject} must be an object`);
  }
}

function requireOk(response, subject, url) {
  if (!response || !response.ok) {
    throw new Error(`Unable to load ${subject} from ${url}: HTTP ${response?.status ?? "unknown"}`);
  }
}

function joinUrl(base, fileName) {
  return `${String(base).replace(/\/+$/, "")}/${fileName}`;
}

async function sha256(bytes) {
  if (!globalThis.crypto?.subtle) throw new Error("Web Crypto API is unavailable; serve the loader over HTTPS or localhost");
  const digest = await globalThis.crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

async function decompressGzip(bytes) {
  if (typeof globalThis.DecompressionStream !== "function") {
    throw new Error("This browser does not support DecompressionStream('gzip')");
  }
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip"));
  return new Response(stream).text();
}
