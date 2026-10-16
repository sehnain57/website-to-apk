// WebToAPK public API (Cloudflare Worker). The GitHub token lives here as a secret, never in the browser.
// Variables: GH_TOKEN (secret), REPO, REF, ALLOWED_ORIGIN, optional BLOCKED_DOMAINS, MAX_PER_HOUR, KV binding RATE.
const AD = /^ca-app-pub-\d{16}\/\d{10}$/, APP = /^ca-app-pub-\d{16}~\d{10}$/, HEX = /^#[0-9a-fA-F]{6}$/;

export default {
  async fetch(req, env) {
    const origin = req.headers.get("Origin") || "";
    const h = { "Access-Control-Allow-Origin": env.ALLOWED_ORIGIN, "Access-Control-Allow-Methods": "GET,POST,OPTIONS", "Access-Control-Allow-Headers": "Content-Type", "Vary": "Origin" };
    const out = (o, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { ...h, "Content-Type": "application/json" } });
    if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: h });
    if (origin && origin !== env.ALLOWED_ORIGIN) return out({ error: "Origin not allowed" }, 403);
    const url = new URL(req.url);
    try {
      if (req.method === "POST" && url.pathname === "/build") return await build(req, env, out);
      if (req.method === "GET" && url.pathname.startsWith("/status/")) return await status(url.pathname.slice(8), env, out);
      return out({ ok: true, service: "webtoapk-api" });
    } catch (e) { return out({ error: "Server error" }, 500); }
  }
};

const gh = (env, path, init = {}) => fetch("https://api.github.com/repos/" + env.REPO + path, {
  ...init,
  headers: { Authorization: "Bearer " + env.GH_TOKEN, Accept: "application/vnd.github+json", "User-Agent": "webtoapk-worker", "X-GitHub-Api-Version": "2022-11-28", ...(init.headers || {}) }
});

async function build(req, env, out) {
  const ip = req.headers.get("CF-Connecting-IP") || "unknown";
  if (env.RATE) {                                  // per-IP hourly limit (needs a KV namespace bound as RATE)
    const k = "r:" + ip + ":" + Math.floor(Date.now() / 3600000);
    const n = +(await env.RATE.get(k) || 0);
    if (n >= (+env.MAX_PER_HOUR || 5)) return out({ error: "Too many builds from your network. Try again in an hour." }, 429);
    await env.RATE.put(k, String(n + 1), { expirationTtl: 4000 });
  }
  let body; try { body = await req.json(); } catch { return out({ error: "Invalid request" }, 400); }
  const cfg = clean(body.config, env), icon = body.icon_b64;
  if (typeof cfg === "string") return out({ error: cfg }, 400);
  if (typeof icon !== "string" || !/^[A-Za-z0-9+/=]+$/.test(icon) || icon.length < 100 || icon.length > 60000) return out({ error: "Invalid logo image" }, 400);
  const id = [...crypto.getRandomValues(new Uint8Array(10))].map(b => "abcdefghijklmnopqrstuvwxyz0123456789"[b % 36]).join("");
  const r = await gh(env, "/actions/workflows/build.yml/dispatches", {
    method: "POST",
    body: JSON.stringify({ ref: env.REF || "main", inputs: { build_id: id, config: JSON.stringify(cfg), icon_b64: icon } })
  });
  if (r.status !== 204) return out({ error: "Could not start the build. Please try again later." }, 502);
  return out({ id });
}

async function status(id, env, out) {
  if (!/^[a-z0-9]{8,20}$/.test(id)) return out({ error: "Bad id" }, 400);
  const ck = new Request("https://cache.local/runs/" + env.REPO);
  let d, c = await caches.default.match(ck);
  if (c) d = await c.json();
  else {
    const g = await gh(env, "/actions/runs?event=workflow_dispatch&per_page=30");
    if (!g.ok) return out({ error: "GitHub unavailable" }, 502);
    d = await g.json();
    await caches.default.put(ck, new Response(JSON.stringify(d), { headers: { "Cache-Control": "max-age=4" } }));
  }
  const run = (d.workflow_runs || []).find(w => w.display_title === "build " + id);
  if (!run) return out({ state: "queued" });
  if (run.status !== "completed") return out({ state: run.status === "in_progress" ? "running" : "queued" });
  if (run.conclusion !== "success") return out({ state: "failed" });
  const base = "https://github.com/" + env.REPO + "/releases/download/build-" + id;
  return out({ state: "success", apk: base + "/app.apk", source: base + "/source.zip" });
}

// Rebuild the config from whitelisted, validated fields only (nothing user-supplied is passed through raw).
function clean(c, env) {
  if (!c || typeof c !== "object") return "Missing configuration";
  let u; try { u = new URL(String(c.url)); } catch { return "Invalid website URL"; }
  if (!/^https?:$/.test(u.protocol) || String(c.url).length > 500) return "Invalid website URL";
  const host = u.hostname.toLowerCase();
  if (host === "localhost" || !host.includes(".") || /^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.|0\.)/.test(host) || /\.(local|internal)$/.test(host)) return "That address can't be used";
  const blocked = (env.BLOCKED_DOMAINS || "").split(",").map(s => s.trim().toLowerCase()).filter(Boolean);
  if (blocked.some(b => host === b || host.endsWith("." + b))) return "This website is not allowed";
  const name = String(c.name || "").trim();
  if (!/^[A-Za-z0-9 ._-]{1,30}$/.test(name)) return "App name: letters, numbers, space . _ - (max 30)";
  const pkg = String(c.package || "");
  if (pkg.length > 100 || !/^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$/.test(pkg)) return "Invalid package name";
  const vn = String(c.versionName || "1.0.0"), vc = Number(c.versionCode);
  if (!/^[A-Za-z0-9._-]{1,20}$/.test(vn) || !Number.isInteger(vc) || vc < 1 || vc > 2100000000) return "Invalid version";
  const s = c.splash || {};
  if (!HEX.test(s.background) || !HEX.test(s.textColor) || ![1500, 2500, 3500].includes(s.durationMs)) return "Invalid splash settings";
  let admob = null;
  if (c.admob) {
    const a = c.admob;
    if (!APP.test(String(a.appId))) return "Invalid AdMob App ID";
    admob = { appId: a.appId };
    for (const k of ["banner", "interstitial", "rewardedInterstitial", "rewarded", "native", "appOpen"]) {
      if (a[k] != null && a[k] !== "" && !AD.test(String(a[k]))) return "Invalid AdMob ad unit ID (" + k + ")";
      admob[k] = a[k] ? a[k] : null;
    }
  }
  return { url: u.href, name, package: pkg, versionName: vn, versionCode: vc, splash: { background: s.background, textColor: s.textColor, durationMs: s.durationMs, showName: s.showName !== false }, admob };
}
