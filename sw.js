// AppSphere Service Worker — DISATTIVATO (v2.0.0)
// Non cacha più nulla. Su 'activate' svuota tutte le cache esistenti e prende
// il controllo dei client; senza handler 'fetch' il browser usa sempre la rete.
// Questo elimina definitivamente il problema delle versioni vecchie/rotte
// servite dalla cache.

self.addEventListener('install', function() {
  self.skipWaiting();
});

self.addEventListener('activate', function(event) {
  event.waitUntil(
    caches.keys()
      .then(function(keys) {
        return Promise.all(keys.map(function(k) { return caches.delete(k); }));
      })
      .then(function() { return self.clients.claim(); })
  );
});

// Nessun listener 'fetch': il Service Worker non intercetta più le richieste,
// quindi ogni pagina viene presa sempre dalla rete (versione fresca).
