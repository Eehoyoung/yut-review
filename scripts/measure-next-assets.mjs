import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { basename, join, relative } from "node:path";
import { brotliCompressSync, gzipSync } from "node:zlib";

const buildDir = process.argv[2] ?? "frontend/.next";
const manifestPath = join(buildDir, "app-build-manifest.json");
if (!existsSync(manifestPath)) {
  console.error(JSON.stringify({ status: "blocked", blocked_reason: `Missing ${manifestPath}; run the production build first.` }));
  process.exit(1);
}

const routes = [
  "/s/[storeToken]/page",
  "/s/[storeToken]/identify/page",
  "/s/[storeToken]/game/page",
  "/s/[storeToken]/result/[playId]/page",
  "/s/[storeToken]/coupon/[couponToken]/page",
];
const manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
const routeFiles = new Map(routes.map((route) => [route, (manifest.pages?.[route] ?? []).filter((file) => existsSync(join(buildDir, file)))]));
const uses = new Map();
for (const files of routeFiles.values()) for (const file of new Set(files)) uses.set(file, (uses.get(file) ?? 0) + 1);

function sizes(files) {
  return [...new Set(files)].reduce((total, file) => {
    const bytes = readFileSync(join(buildDir, file));
    total.raw_bytes += bytes.length;
    total.gzip_bytes += gzipSync(bytes).length;
    total.brotli_bytes += brotliCompressSync(bytes).length;
    return total;
  }, { raw_bytes: 0, gzip_bytes: 0, brotli_bytes: 0 });
}

const route_table = routes.map((route) => {
  const files = routeFiles.get(route);
  const shared = files.filter((file) => uses.get(file) > 1);
  const only = files.filter((file) => uses.get(file) === 1);
  return { route, ...sizes(files), shared_bytes: sizes(shared).raw_bytes, route_only_bytes: sizes(only).raw_bytes, files };
});

function filesUnder(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    return entry.isDirectory() ? filesUnder(path) : [path];
  });
}

const markers = [
  ["Rapier/WebAssembly", /rapier|WebAssembly|dimforge/i],
  ["React Three Fiber", /react-three-fiber|@react-three\/fiber|createRoot.*Canvas/i],
  ["Drei", /@react-three\/drei|react-three-drei|useGLTF/i],
  ["Three.js", /REVISION.{0,20}174|WebGLRenderer|ExtrudeGeometry/i],
];
const referenced = new Set([...routeFiles.values()].flat());
const assets = filesUnder(join(buildDir, "static")).map((path) => {
  const bytes = readFileSync(path);
  const asset = relative(buildDir, path).replaceAll("\\", "/");
  const text = /\.(?:js|css)$/.test(path) ? bytes.toString("utf8") : "";
  return {
    asset,
    ...sizes([asset]),
    loaded_on_routes: routes.filter((route) => routeFiles.get(route).includes(asset)),
    identified_as: markers.filter(([, pattern]) => pattern.test(text)).map(([name]) => name),
  };
});

const fonts = assets.filter(({ asset }) => /\.(?:woff2?|ttf|otf)$/i.test(asset));
const css = assets.filter(({ asset }) => asset.endsWith(".css"));
const wasm = assets.filter(({ asset }) => asset.endsWith(".wasm"));
const rapierEmbedded = assets.filter(({ identified_as }) => identified_as.includes("Rapier/WebAssembly"));
const allRouteFiles = [...new Set([...routeFiles.values()].flat())];

console.log(JSON.stringify({
  status: routes.every((route) => routeFiles.get(route).length) ? "completed" : "partial",
  build_dir: buildDir,
  build_id: existsSync(join(buildDir, "BUILD_ID")) ? readFileSync(join(buildDir, "BUILD_ID"), "utf8").trim() : null,
  notes: [
    "Manifest sizes are compression estimates, not browser transferred bytes.",
    "shared_bytes means files used by at least two measured routes; whole_flow_unique counts each route file once.",
    wasm.length ? "Rapier may have a separate WASM response; verify with the browser measurement." : "No standalone WASM asset found; Rapier is embedded in JavaScript and transfers as JS.",
    "Dynamic-subset font directory totals are not per-visit traffic; unicode-range selects only rendered glyph subsets.",
  ],
  route_table,
  whole_flow_unique: sizes(allRouteFiles),
  heavy_assets: assets.filter((asset) => referenced.has(asset.asset) || asset.raw_bytes >= 100_000).sort((a, b) => b.raw_bytes - a.raw_bytes),
  categories: {
    css: { count: css.length, ...sizes(css.map(({ asset }) => asset)) },
    fonts: { count: fonts.length, ...sizes(fonts.map(({ asset }) => asset)) },
    wasm: { count: wasm.length, ...sizes(wasm.map(({ asset }) => asset)) },
    rapier_embedded_chunks: rapierEmbedded.map(({ asset, raw_bytes, gzip_bytes, brotli_bytes }) => ({ asset, raw_bytes, gzip_bytes, brotli_bytes })),
  },
}, null, 2));
