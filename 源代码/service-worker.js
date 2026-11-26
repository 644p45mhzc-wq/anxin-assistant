const CACHE = 'elder-ai-site-v23';
const ASSETS = ['./', './index.html', './styles.css', './app.js', './manifest.webmanifest'];
const paths = new Set(ASSETS.map(path => new URL(path, self.registration.scope).pathname));
self.addEventListener('install', event => event.waitUntil((async () => {
  const cache = await caches.open(CACHE);
  await cache.addAll(ASSETS.map(path => new Request(path, {cache:'reload'})));
  await self.skipWaiting();
})()));
self.addEventListener('activate', event => event.waitUntil((async () => {
  for (const key of await caches.keys()) {
    if (key.startsWith('elder-ai-') && key !== CACHE) await caches.delete(key);
  }
  await self.clients.claim();
})()));
self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== self.location.origin || !paths.has(url.pathname)) return;
  event.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const response = await fetch(request, {cache:'no-cache'});
      if (response.ok) await cache.put(url.pathname, response.clone());
      return response;
    } catch (error) {
      const saved = await cache.match(url.pathname);
      if (saved) return saved;
      throw error;
    }
  })());
});
