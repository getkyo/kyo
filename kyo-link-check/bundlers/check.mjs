// Prototype: bundle a linked Scala.js ESModule program with vite, webpack and rollup, with and without
// `external: [/^node:/]`, then load each build in headless Chrome and record what the page requests.
//
// usage: node check.mjs <linked dir> <chrome executable> <expected console regex> <node marker>...
import { spawnSync, spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import http from "node:http";
import os from "node:os";

const [linked, chrome, expected, ...markers] = process.argv.slice(2);
const here = path.dirname(new URL(import.meta.url).pathname);
const bin = (name) => path.join(here, "node_modules", ".bin", name);

function project(name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `kyo-bundle-${name}-`));
  fs.cpSync(linked, path.join(dir, "linked"), { recursive: true, filter: (f) => !f.endsWith(".map") });
  fs.writeFileSync(path.join(dir, "entry.js"), 'import "./linked/main.mjs";\n');
  fs.writeFileSync(
    path.join(dir, "index.html"),
    '<!doctype html><html><body><script type="module" src="./entry.js"></script></body></html>\n'
  );
  return dir;
}

const builds = {
  vite(dir, external) {
    fs.writeFileSync(
      path.join(dir, "vite.config.mjs"),
      `export default { logLevel: "warn", build: { outDir: "dist", minify: true, rollupOptions: { ${external ? "external: [/^node:/]" : ""} } } };\n`
    );
    return run(bin("vite"), ["build"], dir);
  },
  webpack(dir, external) {
    fs.writeFileSync(
      path.join(dir, "webpack.config.cjs"),
      `module.exports = {
  mode: "production",
  entry: "./entry.js",
  experiments: { outputModule: true },
  output: { path: require("path").resolve(__dirname, "dist"), module: true, filename: "[name].js", chunkFilename: "[name].[contenthash].js" },
  ${external ? 'externalsType: "module", externals: [({ request }, cb) => (/^node:/.test(request) ? cb(null, "module " + request) : cb())],' : ""}
};\n`
    );
    const r = run(bin("webpack"), ["--config", "webpack.config.cjs"], dir);
    if (r.ok) fs.writeFileSync(path.join(dir, "dist", "index.html"), '<!doctype html><html><body><script type="module" src="./main.js"></script></body></html>\n');
    return r;
  },
  rollup(dir, external) {
    fs.writeFileSync(
      path.join(dir, "rollup.config.mjs"),
      `import resolve from "${path.join(here, "node_modules", "@rollup", "plugin-node-resolve", "dist", "es", "index.js")}";
export default { input: "entry.js", output: { dir: "dist", format: "es" }, plugins: [resolve()], ${external ? "external: [/^node:/]," : ""} };\n`
    );
    const r = run(bin("rollup"), ["-c", "rollup.config.mjs"], dir);
    if (r.ok) fs.writeFileSync(path.join(dir, "dist", "index.html"), '<!doctype html><html><body><script type="module" src="./entry.js"></script></body></html>\n');
    return r;
  },
};

function run(cmd, args, cwd) {
  const r = spawnSync(cmd, args, { cwd, encoding: "utf8" });
  return { ok: r.status === 0, out: (r.stdout || "") + (r.stderr || "") };
}

function scripts(dist) {
  const out = [];
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walk(p);
      else if (/\.(m?js)$/.test(e.name)) out.push(p);
    }
  };
  walk(dist);
  return out;
}

function carries(file) {
  const text = fs.readFileSync(file, "utf8");
  const quoted = (m) => new RegExp("[\"'`]" + m.replace(/[.$]/g, (c) => "\\" + c) + "\\$?[\"'`]");
  return markers.filter((m) => quoted(m).test(text));
}

async function page(dist) {
  const server = http.createServer((req, res) => {
    const file = path.join(dist, decodeURIComponent(new URL(req.url, "http://x").pathname));
    const target = fs.existsSync(file) && fs.statSync(file).isDirectory() ? path.join(file, "index.html") : file;
    if (!fs.existsSync(target)) { res.writeHead(404); res.end(); return; }
    const type = target.endsWith(".html") ? "text/html" : "text/javascript";
    res.writeHead(200, { "content-type": type });
    res.end(fs.readFileSync(target));
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  const port = server.address().port;
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), "kyo-bundle-chrome-"));
  const proc = spawn(chrome, ["--headless", "--remote-debugging-port=0", `--user-data-dir=${profile}`, "about:blank"], { stdio: ["ignore", "ignore", "pipe"] });
  const wsUrl = await new Promise((resolve, reject) => {
    let buf = "";
    proc.stderr.on("data", (d) => {
      buf += d;
      const m = buf.match(/DevTools listening on (ws:\/\/\S+)/);
      if (m) resolve(m[1]);
    });
    proc.on("exit", () => reject(new Error("chrome exited: " + buf)));
  });
  const browser = new WebSocket(wsUrl);
  await new Promise((r) => (browser.onopen = r));
  let id = 0;
  const pending = new Map();
  const events = [];
  browser.onmessage = (m) => {
    const msg = JSON.parse(m.data);
    if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
    else events.push(msg);
  };
  const send = (method, params = {}, sessionId) =>
    new Promise((r) => { const i = ++id; pending.set(i, r); browser.send(JSON.stringify({ id: i, method, params, sessionId })); });
  const target = await send("Target.createTarget", { url: "about:blank" });
  const attached = await send("Target.attachToTarget", { targetId: target.result.targetId, flatten: true });
  const session = attached.result.sessionId;
  await send("Network.enable", {}, session);
  await send("Runtime.enable", {}, session);
  await send("Page.navigate", { url: `http://127.0.0.1:${port}/index.html` }, session);
  const deadline = Date.now() + 20000;
  const logged = () => events.filter((e) => e.method === "Runtime.consoleAPICalled").map((e) => e.params.args.map((a) => a.value).join(" "));
  const errors = () => events.filter((e) => e.method === "Runtime.exceptionThrown").map((e) => e.params.exceptionDetails.exception?.description || e.params.exceptionDetails.text);
  while (Date.now() < deadline && !logged().some((l) => new RegExp(expected).test(l)) && errors().length === 0) {
    await new Promise((r) => setTimeout(r, 100));
  }
  const requested = events
    .filter((e) => e.method === "Network.requestWillBeSent")
    .map((e) => new URL(e.params.request.url).pathname);
  browser.close();
  proc.kill();
  server.close();
  return { logged: logged(), errors: errors(), requested };
}

const rows = [];
for (const name of Object.keys(builds)) {
  for (const external of [false, true]) {
    const dir = project(name);
    const built = builds[name](dir, external);
    const row = { bundler: name, external, built: built.ok };
    if (!built.ok) {
      row.error = built.out.split("\n").filter((l) => /error|Error/.test(l)).slice(0, 3).join(" | ");
      rows.push(row);
      continue;
    }
    const dist = path.join(dir, "dist");
    const files = scripts(dist);
    row.files = files.length;
    row.nodeFiles = files.filter((f) => carries(f).length > 0).map((f) => path.relative(dist, f));
    row.totalBytes = files.reduce((s, f) => s + fs.statSync(f).size, 0);
    const result = await page(dist);
    row.answered = result.logged.find((l) => new RegExp(expected).test(l)) || null;
    row.pageErrors = result.errors;
    row.requested = result.requested.filter((p) => /\.m?js$/.test(p));
    row.requestedNode = row.requested.filter((p) => row.nodeFiles.includes(p.replace(/^\//, "")));
    row.initialBytes = row.requested.reduce((s, p) => s + fs.statSync(path.join(dist, p)).size, 0);
    rows.push(row);
  }
}
console.log(JSON.stringify(rows, null, 2));
