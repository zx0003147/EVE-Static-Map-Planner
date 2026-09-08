# Web/PWA Production Deployment

## Deployment boundary

The Web client is a static HTTPS site. Shared Marker remains the separately deployed EVE Shared Map Server. A production deployment therefore has two origins:

```text
https://map.example.com       static Web/PWA client and /data Web Pack
https://markers.example.com   existing Shared Map Server REST API
```

Set the server's `SHARED_MAP_ALLOWED_ORIGINS` to the exact Web origin:

```text
SHARED_MAP_ALLOWED_ORIGINS=https://map.example.com
```

Do not use `*`. Both origins must use HTTPS; an HTTPS Web client cannot connect to a plain-HTTP Shared Marker server. They may be hosted on different machines. No Shared Map Server code or Protocol v1 change is required for PWA deployment.

## Build and published files

For a self-hosted release, build the versioned ZIP from a Desktop-exported Web Pack:

```powershell
.\gradlew.bat assembleSelfHostedWeb `
  "-PwebPackDir=C:\path\to\EVE-Web-Pack"
```

The stable release output is written under `build/distributions/`:

```text
eve-map-web-<version>.zip
eve-map-web-<version>.zip.sha256
eve-map-web-<version>.metadata.json
```

The task refuses a missing, incomplete, path-unsafe, size-mismatched, checksum-mismatched, or unsupported-schema Web
Pack. The ZIP contains the static site at its root plus `self-hosted-web.json`; the checksum and external metadata
remain beside the artifact for release automation and installer verification. The Shared Map Server host downloads
this artifact and never compiles Kotlin/JS.

For direct/manual static hosting, build and stage the same Desktop-exported Web Pack without creating the release
ZIP:

```powershell
.\gradlew.bat :web-client:webProduction `
  "-PwebPackDir=C:\path\to\EVE-Web-Pack"
```

Publish the complete contents of `web-client/build/dist/js/productionExecutable/`:

```text
/
|-- index.html
|-- web-client.js
|-- web-client.css
|-- web-pack-loader.mjs
|-- pwa-runtime.mjs
|-- manifest.webmanifest
|-- service-worker.js
|-- icons/
|   |-- app-icon-192.png
|   `-- app-icon-512.png
`-- data/
    |-- manifest.json
    `-- web-pack-<packVersion>.json.gz
```

The production `.js.map` may be retained for private diagnostics or omitted from the public upload. It is not required at runtime. Do not publish test sources or a Gradle build directory as a browsable directory listing.

## Required cache and content headers

| Resource | Recommended `Cache-Control` | Notes |
|---|---|---|
| `/`, `/index.html` | `no-cache, must-revalidate` | Lets users discover a new app shell. |
| `/service-worker.js` | `no-cache, must-revalidate` | Never immutable; browser update checks must reach the current worker. |
| `/manifest.webmanifest` | `public, max-age=3600, must-revalidate` | Ordinary short-lived asset caching. |
| JS, CSS, icons | `public, max-age=3600, must-revalidate` | Current filenames are stable, so revalidate rather than immutable caching. |
| `/data/manifest.json` | `no-cache, must-revalidate` | Stable discovery URL; always revalidate online. |
| `/data/web-pack-<packVersion>.json.gz` | `public, max-age=31536000, immutable` | Content/version-addressed Pack. |

Serve the Pack as `application/gzip` or `application/octet-stream`. Do **not** add `Content-Encoding: gzip` to the `.json.gz` Pack. The loader must receive the compressed bytes so it can verify `sizeBytes` and SHA-256 before using `DecompressionStream("gzip")`.

The service worker owns app-shell consistency once installed: controlled clients use one revisioned shell cache until the user accepts the waiting worker. For every app-shell release, change the `APP_CACHE` revision in `service-worker.js`; do not reuse an old shell cache name. HTTP revalidation headers are still required for the first load and service-worker update discovery.

Ordinary HTTP compression is appropriate for HTML, JS, CSS, JSON, and the Web App Manifest, but exclude the already-compressed Web Pack path.

## Caddy example

```caddyfile
map.example.com {
    root * /srv/eve-static-map

    @dataManifest path /data/manifest.json
    header @dataManifest Cache-Control "no-cache, must-revalidate"

    @versionedPack path_regexp versionedPack ^/data/web-pack-[A-Za-z0-9._-]+\.json\.gz$
    header @versionedPack Cache-Control "public, max-age=31536000, immutable"
    header @versionedPack -Content-Encoding

    @serviceWorker path /service-worker.js
    header @serviceWorker Cache-Control "no-cache, must-revalidate"

    @appShell path / /index.html
    header @appShell Cache-Control "no-cache, must-revalidate"

    @compressible not path /data/web-pack-*.json.gz
    encode @compressible zstd gzip
    file_server
}
```

The Shared Map Server may keep its existing, separate Caddy reverse-proxy site. Configure its TLS and the exact `SHARED_MAP_ALLOWED_ORIGINS` value independently.

## nginx example

```nginx
server {
    listen 443 ssl http2;
    server_name map.example.com;
    root /srv/eve-static-map;

    location = /data/manifest.json {
        add_header Cache-Control "no-cache, must-revalidate" always;
        try_files $uri =404;
    }

    location ~ ^/data/web-pack-[A-Za-z0-9._-]+\.json\.gz$ {
        default_type application/gzip;
        gzip off;
        add_header Cache-Control "public, max-age=31536000, immutable" always;
        try_files $uri =404;
    }

    location = /service-worker.js {
        add_header Cache-Control "no-cache, must-revalidate" always;
        try_files $uri =404;
    }

    location / {
        add_header Cache-Control "public, max-age=3600, must-revalidate" always;
        try_files $uri $uri/ /index.html;
    }
}
```

Add the site's existing certificate configuration. Ensure no global rule adds `Content-Encoding` to the Pack response.

## Safe Web Pack update

The browser has no Import, Apply, Update, or cache-clear action. Publish data in this order:

1. Export the new Pack from Desktop.
2. Upload `web-pack-<newPackVersion>.json.gz` under `/data/`.
3. Verify its HTTP status, byte length, SHA-256, content type, and lack of `Content-Encoding`.
4. Upload the new `/data/manifest.json` last.
5. Keep the previous versioned Pack available while old tabs may still reference it.
6. Open or reload the Web/PWA client; it revalidates the manifest and automatically requests the new filename.

To roll back data, republish the previous manifest while its referenced immutable Pack still exists. App-shell rollback requires restoring the previous static files and service worker; clients will receive the waiting update and choose Reload.

## PWA update and offline behavior

Chrome/Android can install the site after it is served over HTTPS with the manifest, 192/512 icons, and service worker intact. Standalone launch uses the same root URL and `/data` contract.

The service worker caches the app shell and the last successfully loaded, integrity-checked Web Pack. A new app shell waits and displays an explicit Reload notice. It never automatically reloads a marker editor. `/data/manifest.json` remains network-first, so app-shell caching cannot pin old universe data.

Offline reopen supports static map and local calculations only. Shared Marker shows Offline and disables writes; it does not queue create, edit, or delete operations. A first offline launch without a prior successful cache cannot construct the universe and shows Retry.

## Production verification

Before changing DNS or declaring rollout complete:

1. Confirm HTTPS and no mixed-content errors for both origins.
2. Inspect all cache/content headers above.
3. Load the current real Pack and verify its count/status line.
4. Install the PWA and launch it standalone.
5. Disable networking and reopen; verify static planning and explicit Shared Marker offline state.
6. Restore networking and verify Shared Marker can reconnect with a valid invite/session flow.
7. Perform a disposable Pack A to Pack B rollout in upload order and confirm automatic discovery.
8. Run Chrome and Edge desktop smoke plus Chromium tablet/touch emulation.

Local `qa/static-web-server.mjs` emits the production-intent cache headers and is suitable only for local acceptance, not Internet deployment.
