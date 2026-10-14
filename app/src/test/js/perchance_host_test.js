// Behaviour tests for app/src/main/assets/perchance_host.html (the hidden Perchance bridge page).
// Not part of the Gradle build; run with Node 18+:   node app/src/test/js/perchance_host_test.js
// The harness fakes the DOM (iframes, timers, message events) so the postMessage protocol, origin /
// request-id filtering, single reporting, timeout, cancel and warm-up logic can be checked without a device.
const fs = require("fs");
const assert = require("assert");
const html = fs.readFileSync(require("path").join(__dirname, "..", "..", "main", "assets", "perchance_host.html"), "utf8");
const script = html.split("<script>")[1].split("</script>")[0];

function makeEnv() {
  const timers = []; let now = 0; let nextId = 1;
  const holder = { children: [], appendChild(f) { f.parentNode = holder; holder.children.push(f); }, removeChild(f) { holder.children = holder.children.filter(x => x !== f); f.parentNode = null; } };
  const document = { getElementById: () => holder, createElement: () => ({ style: {}, setAttribute() {}, parentNode: null }) };
  const listeners = {};
  const calls = { events: [], results: [], errors: [], warm: [] };
  const window = {
    addEventListener(t, fn) { listeners[t] = fn; },
    AndroidBridge: {
      onEvent: (id, stage, detail) => calls.events.push([id, stage, detail]),
      onWarmUpDone: (ok, why, ms) => calls.warm.push([ok, why, ms]),
      onImageResult: (id, url, dataUrl, seed) => calls.results.push([id, url, dataUrl, seed]),
      onImageError: (id, code, msg) => calls.errors.push([id, code, msg]),
    },
  };
  const setTimeout_ = (fn, ms) => { const id = nextId++; timers.push({ id, at: now + ms, fn }); return id; };
  const clearTimeout_ = (id) => { const i = timers.findIndex(t => t.id === id); if (i >= 0) timers.splice(i, 1); };
  const DateStub = { now: () => now };
  new Function("window", "document", "setTimeout", "clearTimeout", "Date", "URLSearchParams", script)(window, document, setTimeout_, clearTimeout_, DateStub, URLSearchParams);
  return {
    window, holder, calls,
    advance(ms) { now += ms; for (;;) { const due = timers.filter(t => t.at <= now).sort((a, b) => a.at - b.at)[0]; if (!due) break; timers.splice(timers.indexOf(due), 1); due.fn(); } },
    post(data, origin) { listeners.message({ data, origin }); },
    pendingTimers: () => timers.length,
  };
}
const frameParams = (f) => new URL(f.src).searchParams;
let n = 0; const t = (name, fn) => { fn(); n++; console.log("ok -", name); };

t("generate creates one hidden iframe with documented query params", () => {
  const e = makeEnv();
  e.window.orkiGenerate("req1", "a cat & dog? बर'", "512x768", 7, 150000);
  assert.strictEqual(e.holder.children.length, 1);
  const f = e.holder.children[0]; const u = new URL(f.src);
  assert.strictEqual(u.origin + u.pathname, "https://perchance.org/perchance-ai-api");
  const p = u.searchParams;
  assert.strictEqual(p.get("prompt"), "a cat & dog? बर'"); assert.strictEqual(p.get("format"), "json");
  assert.strictEqual(p.get("id"), "req1"); assert.strictEqual(p.get("resolution"), "512x768"); assert.strictEqual(p.get("seed"), "7");
  assert.ok(e.calls.events.some(x => x[1] === "frame-requested"));
});

t("a valid message from perchance.org settles once, removes the frame, ignores duplicates", () => {
  const e = makeEnv(); e.window.orkiGenerate("req1", "p", "768x768", 0, 150000);
  assert.ok(!frameParams(e.holder.children[0]).has("seed"));
  const msg = { api: "perchance-image-api", result: { id: "req1", ok: true, url: "https://user.uploads.dev/file/a.jpeg", seed: 5 } };
  e.post(msg, "https://perchance.org");
  assert.deepStrictEqual(e.calls.results, [["req1", "https://user.uploads.dev/file/a.jpeg", "", "5"]]);
  assert.strictEqual(e.holder.children.length, 0); assert.strictEqual(e.pendingTimers(), 0);
  e.post(msg, "https://perchance.org"); assert.strictEqual(e.calls.results.length, 1);
});

t("messages from foreign origins, wrong api, unknown ids and junk are ignored", () => {
  const e = makeEnv(); e.window.orkiGenerate("req1", "p", "768x768", 0, 150000);
  const ok = { api: "perchance-image-api", result: { id: "req1", ok: true, url: "https://x/y" } };
  e.post(ok, "https://evil.com"); e.post(ok, "https://perchance.org.evil.com"); e.post(ok, "http://perchance.org"); e.post(ok, "https://evilperchance.org");
  e.post({ api: "other", result: { id: "req1", ok: true, url: "u" } }, "https://perchance.org");
  e.post({ api: "perchance-image-api", result: { id: "nope", ok: true, url: "u" } }, "https://perchance.org");
  e.post("not json", "https://perchance.org"); e.post(null, "https://perchance.org"); e.post(42, "https://perchance.org");
  assert.strictEqual(e.calls.results.length + e.calls.errors.length, 0);
  assert.ok(e.calls.events.some(x => x[1] === "message-rejected-origin"));
  e.post(JSON.stringify(ok), "https://null.perchance.org"); // string form + subdomain accepted
  assert.strictEqual(e.calls.results.length, 1);
});

t("generation failure reported by the page becomes a rejected error", () => {
  const e = makeEnv(); e.window.orkiGenerate("r", "p", "768x768", 0, 150000);
  e.post({ api: "perchance-image-api", result: { id: "r", ok: false, error: "blocked prompt" } }, "https://perchance.org");
  assert.deepStrictEqual(e.calls.errors, [["r", "rejected", "blocked prompt"]]);
  assert.strictEqual(e.holder.children.length, 0);
});

t("ok without url or dataUrl is a protocol error; dataUrl-only is forwarded", () => {
  const e = makeEnv(); e.window.orkiGenerate("a", "p", "768x768", 0, 1000); e.window.orkiGenerate("b", "p", "768x768", 0, 1000);
  e.post({ api: "perchance-image-api", result: { id: "a", ok: true } }, "https://perchance.org");
  e.post({ api: "perchance-image-api", result: { id: "b", ok: true, dataUrl: "data:image/png;base64,AAAA" } }, "https://perchance.org");
  assert.deepStrictEqual(e.calls.errors[0].slice(0, 2), ["a", "protocol"]);
  assert.deepStrictEqual(e.calls.results, [["b", "", "data:image/png;base64,AAAA", ""]]);
});

t("a hosted url suppresses the heavy dataUrl", () => {
  const e = makeEnv(); e.window.orkiGenerate("a", "p", "768x768", 0, 1000);
  e.post({ api: "perchance-image-api", result: { id: "a", ok: true, url: "https://user.uploads.dev/f.png", dataUrl: "data:image/png;base64," + "A".repeat(100) } }, "https://perchance.org");
  assert.strictEqual(e.calls.results[0][2], "");
});

t("timeout reports once and cleans up", () => {
  const e = makeEnv(); e.window.orkiGenerate("r", "p", "768x768", 0, 5000);
  e.advance(4999); assert.strictEqual(e.calls.errors.length, 0);
  e.advance(2); assert.strictEqual(e.calls.errors.length, 1);
  assert.strictEqual(e.calls.errors[0][1], "timeout"); assert.ok(/no answer after 5s/.test(e.calls.errors[0][2]));
  assert.strictEqual(e.holder.children.length, 0);
  e.post({ api: "perchance-image-api", result: { id: "r", ok: true, url: "u" } }, "https://perchance.org");
  assert.strictEqual(e.calls.results.length, 0);
});

t("cancel drops the frame and timer silently; late answers are ignored", () => {
  const e = makeEnv(); e.window.orkiGenerate("r", "p", "768x768", 0, 5000);
  e.window.orkiCancel("r");
  assert.strictEqual(e.holder.children.length, 0); assert.strictEqual(e.pendingTimers(), 0);
  e.post({ api: "perchance-image-api", result: { id: "r", ok: true, url: "u" } }, "https://perchance.org");
  e.advance(10000);
  assert.strictEqual(e.calls.results.length + e.calls.errors.length, 0);
  assert.ok(e.calls.events.some(x => x[1] === "cancelled"));
  e.window.orkiCancel("unknown"); // harmless
});

t("duplicate ids do not create a second frame", () => {
  const e = makeEnv(); e.window.orkiGenerate("r", "p", "768x768", 0, 5000); e.window.orkiGenerate("r", "q", "768x768", 0, 5000);
  assert.strictEqual(e.holder.children.length, 1);
});

t("concurrent requests are tracked independently", () => {
  const e = makeEnv(); e.window.orkiGenerate("a", "p", "768x768", 0, 5000); e.window.orkiGenerate("b", "p", "768x768", 0, 5000);
  e.post({ api: "perchance-image-api", result: { id: "b", ok: true, url: "https://user.uploads.dev/b.png" } }, "https://perchance.org");
  assert.strictEqual(e.calls.results.length, 1); assert.strictEqual(e.holder.children.length, 1);
  e.advance(6000); assert.deepStrictEqual(e.calls.errors.map(x => x[0]), ["a"]);
});

t("warm-up loads perchance.org, reports ok after load, and is cached", () => {
  const e = makeEnv(); e.window.orkiWarmUp(15000);
  const f = e.holder.children[0]; assert.strictEqual(f.src, "https://perchance.org");
  f.onload(); e.advance(500);
  assert.strictEqual(e.calls.warm.length, 1); assert.strictEqual(e.calls.warm[0][0], true); assert.strictEqual(e.calls.warm[0][1], "loaded");
  assert.strictEqual(e.holder.children.length, 0);
  e.window.orkiWarmUp(15000); assert.deepStrictEqual(e.calls.warm[1].slice(0, 2), [true, "cached"]); assert.strictEqual(e.holder.children.length, 0);
});

t("warm-up timeout reports failure and is retried next time", () => {
  const e = makeEnv(); e.window.orkiWarmUp(3000); e.advance(3001);
  assert.deepStrictEqual(e.calls.warm[0].slice(0, 2), [false, "timeout"]); assert.strictEqual(e.holder.children.length, 0);
  e.window.orkiWarmUp(3000); assert.strictEqual(e.holder.children.length, 1); // not marked warmed
  e.window.orkiWarmUp(3000); assert.strictEqual(e.holder.children.length, 1); // already running
});

console.log("\nall " + n + " JS host-page tests passed");
