// ============================================================
// sw-notifiche.js — il service worker del canale 'browser'
//
// ⚠️ Deroga dichiarata alla regola «un'app = un file HTML»: un service worker
// DEVE essere un file a sé, servito dalla radice perché il suo scope sia «/».
// Lo registra index.html (☰ → 💻 Browser) e lo sveglia il servizio push del
// browser quando send-notifications manda una riga 'browser'.
//
// Fa due cose e basta: mostra la notifica e, al clic, apre la pagina dell'app.
// Niente pulsanti (✅ Fatto / ⏸ Rinvia): servirebbero il login, che qui non c'è.
// Niente cache: il sito resta quello di sempre, sempre fresco.
// ============================================================

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', e => e.waitUntil(self.clients.claim()));

self.addEventListener('push', e => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (_) { d = { body: e.data && e.data.text() }; }
  e.waitUntil(self.registration.showNotification(d.title || '🔔 AppSphere', {
    body: d.body || '',
    icon: '/icons/icon-192.png',
    // Lo stesso promemoria ripetuto (anticipi diversi) prende il posto del precedente.
    tag: d.tag || undefined,
    renotify: !!d.tag,
    data: { url: d.url || '/' },
  }));
});

self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = new URL((e.notification.data && e.notification.data.url) || '/', self.location.origin).href;
  e.waitUntil((async () => {
    const aperte = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const gia = aperte.find(c => c.url.split('#')[0] === url);
    if (gia) return gia.focus();
    return self.clients.openWindow(url);
  })());
});
