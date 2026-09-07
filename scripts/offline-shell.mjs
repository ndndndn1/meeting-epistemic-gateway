import { readdir, writeFile } from "node:fs/promises";
const files = (await readdir("dist", { recursive: true })).filter(
  (f) => /\.(js|mjs|css|html|wasm|onnx|json)$/.test(f) && !f.includes("sw.js"),
);
const version = process.env.GITHUB_SHA ?? Date.now().toString();
await writeFile(
  "dist/sw.js",
  `const CACHE='meg-shell-${version}',BASE='/meeting-epistemic-gateway/';
self.addEventListener('install',e=>e.waitUntil(caches.open(CACHE).then(c=>c.addAll(${JSON.stringify(files)}.map(p=>BASE+p))).then(()=>self.skipWaiting())));
self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('meg-shell-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>clients.claim())));
self.addEventListener('fetch',e=>{const u=new URL(e.request.url);if(u.origin!==location.origin||!u.pathname.startsWith(BASE)||e.request.method!=='GET'||u.searchParams.has('code'))return;e.respondWith(fetch(e.request).then(r=>{if(r.ok&&u.search===''){const copy=r.clone();caches.open(CACHE).then(c=>c.put(e.request,copy));}return r;}).catch(()=>caches.match(e.request).then(r=>r||(e.request.mode==='navigate'?caches.match(BASE+'index.html'):Response.error()))));});`,
);
