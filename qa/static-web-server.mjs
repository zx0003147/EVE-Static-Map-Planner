import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, resolve, sep } from "node:path";

const root = resolve(process.argv[2] ?? "web-client/build/dist/js/productionExecutable");
const port = Number(process.argv[3] ?? 8765);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("Port must be from 1 through 65535");

const contentTypes = new Map([
  [".css", "text/css; charset=utf-8"],
  [".gz", "application/gzip"],
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".mjs", "text/javascript; charset=utf-8"],
  [".png", "image/png"],
  [".webmanifest", "application/manifest+json; charset=utf-8"],
]);

createServer(async (request, response) => {
  try {
    const pathname = decodeURIComponent(new URL(request.url, "http://localhost").pathname);
    const relative = pathname === "/" ? "index.html" : pathname.replace(/^\/+/, "");
    const target = resolve(root, relative);
    if (target !== root && !target.startsWith(`${root}${sep}`)) {
      response.writeHead(403).end("Forbidden");
      return;
    }
    const info = await stat(target);
    if (!info.isFile()) throw new Error("Not a file");
    response.writeHead(200, {
      "Cache-Control": cacheControl(pathname),
      "Content-Length": info.size,
      "Content-Type": contentTypes.get(extname(target)) ?? "application/octet-stream",
    });
    response.end(await readFile(target));
  } catch {
    response.writeHead(404).end("Not Found");
  }
}).listen(port, "127.0.0.1", () => {
  process.stdout.write(`Serving ${root} at http://127.0.0.1:${port}/\n`);
});

function cacheControl(pathname) {
  if (pathname.endsWith("/data/manifest.json")) return "no-cache, must-revalidate";
  if (/\/data\/web-pack-[A-Za-z0-9._-]+\.json\.gz$/.test(pathname)) {
    return "public, max-age=31536000, immutable";
  }
  if (pathname.endsWith("/service-worker.js") || pathname === "/" || pathname.endsWith("/index.html")) {
    return "no-cache, must-revalidate";
  }
  return "public, max-age=3600, must-revalidate";
}
