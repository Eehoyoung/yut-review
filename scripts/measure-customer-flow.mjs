import { spawn } from "node:child_process";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

function args(argv) {
  const result = {};
  for (let i = 2; i < argv.length; i += 2) result[argv[i].replace(/^--/, "")] = argv[i + 1];
  return result;
}

const options = args(process.argv);
const required = ["base-url", "email", "password", "name", "phone"];
const missing = required.filter((key) => !options[key]);
if (missing.length) {
  console.error(JSON.stringify({
    status: "blocked",
    blocked_reason: `Missing arguments: ${missing.map((key) => `--${key}`).join(", ")}`,
    usage: "node scripts/measure-customer-flow.mjs --base-url http://127.0.0.1:18088 --email test@example.invalid --password <test-password> --name 측정고객 --phone 01000000001 [--chrome <path>] [--timeout-ms 90000]",
  }));
  process.exit(1);
}

const base = new URL(options["base-url"]);
if (!new Set(["localhost", "127.0.0.1", "[::1]"]).has(base.hostname)) {
  console.error(JSON.stringify({ status: "blocked", blocked_reason: "Safety guard: --base-url must be localhost/loopback and an isolated test stack." }));
  process.exit(1);
}
const timeoutMs = Number(options["timeout-ms"] ?? 90_000);
if (!Number.isFinite(timeoutMs) || timeoutMs < 1_000) {
  console.error(JSON.stringify({ status: "blocked", blocked_reason: "--timeout-ms must be a number of at least 1000." }));
  process.exit(1);
}
const chrome = options.chrome ?? [
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
].find(existsSync);
if (!chrome) {
  console.error(JSON.stringify({ status: "blocked", blocked_reason: "Chrome/Edge executable not found; pass --chrome <path>." }));
  process.exit(1);
}

async function api(path, init) {
  const response = await fetch(new URL(`/api${path}`, base), init);
  const body = await response.json().catch(() => null);
  if (!response.ok || !body?.success) throw new Error(`${path}: ${response.status} ${body?.error?.code ?? "invalid response"}`);
  return body.data;
}

const login = await api("/admin/auth/login", {
  method: "POST", headers: { "content-type": "application/json" },
  body: JSON.stringify({ email: options.email, password: options.password }),
});
const auth = { headers: { authorization: `Bearer ${login.accessToken}` } };
const stores = await api("/admin/stores", auth);
if (stores.length !== 1) throw new Error(`Expected exactly one isolated bootstrap store, found ${stores.length}.`);
const qrs = await api(`/admin/stores/${stores[0].id}/qr-codes`, auth);
const storeToken = qrs.find((qr) => qr.status === "ACTIVE")?.token;
if (!storeToken) throw new Error("No active QR token found in the isolated test store.");

const profile = mkdtempSync(join(tmpdir(), "yut-measure-"));
const child = spawn(chrome, [
  "--headless=new", "--no-first-run", "--no-default-browser-check", "--disable-background-networking",
  "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank",
], { stdio: "ignore" });

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function waitFor(fn, label) {
  const until = Date.now() + timeoutMs;
  while (Date.now() < until) {
    const value = await fn().catch(() => null);
    if (value) return value;
    await sleep(100);
  }
  throw new Error(`Timed out waiting for ${label}.`);
}

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.id = 0;
    this.pending = new Map();
    this.listeners = new Set();
  }
  async open() {
    await new Promise((resolve, reject) => {
      this.ws.addEventListener("open", resolve, { once: true });
      this.ws.addEventListener("error", reject, { once: true });
    });
    this.ws.addEventListener("message", ({ data }) => {
      const message = JSON.parse(data);
      if (message.id) {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id);
        message.error ? pending.reject(new Error(message.error.message)) : pending.resolve(message.result);
      } else this.listeners.forEach((listener) => listener(message));
    });
  }
  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((resolve, reject) => this.pending.set(id, { resolve, reject }));
  }
  on(listener) { this.listeners.add(listener); return () => this.listeners.delete(listener); }
  close() { this.ws.close(); }
}

function category(request) {
  const url = new URL(request.url);
  if (url.pathname.startsWith("/api/")) return "api_json";
  const mime = request.mimeType ?? "";
  if (/javascript/.test(mime) || url.pathname.endsWith(".js")) return "js";
  if (/css/.test(mime) || url.pathname.endsWith(".css")) return "css";
  if (/font|woff|ttf/.test(mime) || /\.(woff2?|ttf|otf)$/.test(url.pathname)) return "font";
  if (/wasm/.test(mime) || url.pathname.endsWith(".wasm")) return "wasm";
  if (/^image\//.test(mime)) return "image";
  if (/html/.test(mime) || request.type === "Document") return "page";
  if (url.pathname.startsWith("/_next/static/")) return "next_static_other";
  return "other";
}

function summarize(requests) {
  const rows = [...requests.values()].filter((request) => request.response);
  const categories = {};
  for (const request of rows) {
    const key = category(request);
    const item = categories[key] ??= { requests: 0, transferred_bytes: 0, decoded_body_bytes: 0, cache_hits: 0 };
    item.requests += 1;
    item.transferred_bytes += request.transferred ?? 0;
    item.decoded_body_bytes += request.decoded ?? 0;
    item.cache_hits += request.cached ? 1 : 0;
  }
  return {
    total_requests: rows.length,
    api_requests: rows.filter((request) => category(request) === "api_json").length,
    total_transferred_bytes: rows.reduce((sum, request) => sum + (request.transferred ?? 0), 0),
    total_decoded_body_bytes: rows.reduce((sum, request) => sum + (request.decoded ?? 0), 0),
    categories,
    api_flow: rows.filter((request) => category(request) === "api_json").map(({ method, url, status, transferred, decoded, cached }) => ({ method, path: new URL(url).pathname, status, transferred_bytes: transferred ?? 0, decoded_body_bytes: decoded ?? 0, cached })),
    failures: rows.filter(({ status }) => status >= 400).map(({ method, url, status }) => ({ method, path: new URL(url).pathname, status })),
  };
}

let cdp;
try {
  const portFile = join(profile, "DevToolsActivePort");
  const port = await waitFor(async () => existsSync(portFile) && readFileSync(portFile, "utf8").split(/\r?\n/)[0], "Chrome DevTools port");
  const target = await fetch(`http://127.0.0.1:${port}/json/new?${encodeURIComponent("about:blank")}`, { method: "PUT" }).then((response) => response.json());
  cdp = new Cdp(target.webSocketDebuggerUrl);
  await cdp.open();
  await Promise.all([cdp.send("Page.enable"), cdp.send("Network.enable"), cdp.send("Runtime.enable"), cdp.send("Network.setCacheDisabled", { cacheDisabled: false })]);

  let requests = new Map();
  cdp.on(({ method, params }) => {
    if (method === "Network.requestWillBeSent") requests.set(params.requestId, { url: params.request.url, method: params.request.method, type: params.type, decoded: 0 });
    if (method === "Network.responseReceived") {
      const request = requests.get(params.requestId);
      if (request) Object.assign(request, { status: params.response.status, mimeType: params.response.mimeType, response: true, cached: Boolean(params.response.fromDiskCache || params.response.fromPrefetchCache || params.response.fromServiceWorker) });
    }
    if (method === "Network.dataReceived") {
      const request = requests.get(params.requestId);
      if (request) request.decoded += params.dataLength;
    }
    if (method === "Network.loadingFinished") {
      const request = requests.get(params.requestId);
      if (request) request.transferred = params.encodedDataLength;
    }
  });

  const evaluate = async (expression) => {
    const response = await cdp.send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true });
    if (response.exceptionDetails) throw new Error(response.exceptionDetails.exception?.description ?? response.exceptionDetails.text);
    return response.result.value;
  };
  const waitExpression = (expression, label) => waitFor(() => evaluate(expression), label);
  const landing = new URL(`/s/${storeToken}`, base).href;
  await cdp.send("Page.navigate", { url: landing });
  await waitExpression("document.querySelector('a[href$=\"/identify\"]')?.click() || location.pathname.endsWith('/identify')", "identify link");
  await waitExpression("location.pathname.endsWith('/identify') && !!document.querySelector('#name')", "identify page");
  await evaluate(`(() => { const set=(selector,value)=>{const el=document.querySelector(selector);const setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;setter.call(el,value);el.dispatchEvent(new Event('input',{bubbles:true}));};set('#name',${JSON.stringify(options.name)});set('#phone',${JSON.stringify(options.phone)});document.querySelector('input[type=checkbox]').click();return true;})()`);
  await waitExpression("!!document.querySelector('form button:not(:disabled)')", "enabled identify submit");
  await evaluate("document.querySelector('form').requestSubmit()");
  await waitExpression("location.pathname.endsWith('/game') && [...document.querySelectorAll('button')].some(b=>b.textContent.includes('윷 던지기'))", "game page");
  await evaluate("[...document.querySelectorAll('button')].find(b=>b.textContent.includes('윷 던지기')).click()");
  await waitExpression("location.pathname.includes('/result/') && !!document.querySelector('a[href*=\"/coupon/\"]')", "result page");
  await evaluate("document.querySelector('a[href*=\"/coupon/\"]').click()");
  const couponUrl = await waitExpression("location.pathname.includes('/coupon/') && document.querySelector('.ticket') && location.href", "coupon page");
  await sleep(1_000);
  const cold = summarize(requests);

  requests = new Map();
  await cdp.send("Page.reload", { ignoreCache: false });
  await waitExpression("location.pathname.includes('/coupon/') && !!document.querySelector('.ticket')", "warm coupon reload");
  await sleep(1_000);
  const warm = summarize(requests);

  const revealCalls = cold.api_flow.filter(({ path }) => /\/public\/games\/[^/]+\/reveal$/.test(path));
  console.log(JSON.stringify({
    status: cold.failures.length || warm.failures.length ? "partial" : "completed",
    base_url: base.href,
    scenario: "cold full flow from QR landing through coupon, then warm coupon reconnect in the same browser profile",
    cold_cache: cold,
    warm_cache: warm,
    validation: {
      reveal_request_count: revealCalls.length,
      duplicate_reveal_observed: revealCalls.length > 1,
      expected_new_customer_api_requests: 6,
      final_coupon_url: couponUrl,
      transferred_definition: "CDP Network.loadingFinished.encodedDataLength (wire bytes including protocol overhead reported by Chromium)",
      decoded_definition: "Sum of CDP Network.dataReceived.dataLength",
    },
  }, null, 2));
} catch (error) {
  console.error(JSON.stringify({ status: "blocked", blocked_reason: error.message, base_url: base.href }));
  process.exitCode = 1;
} finally {
  cdp?.close();
  child.kill();
  await sleep(200);
  rmSync(profile, { recursive: true, force: true });
}
