// ============================================================
// Web Push — le notifiche del canale 'browser'
//
// Due RFC e nessuna libreria: RFC 8292 (VAPID, chi manda) e RFC 8291
// (aes128gcm, cosa si manda). Tutto con Web Crypto, che Deno ha già: una
// dipendenza npm che si porta dietro il `crypto` di Node è un deploy che un
// giorno smette di funzionare per una ragione che non ha a che fare con noi.
//
// ⚠️ La chiave pubblica VAPID è scritta ANCHE in index.html (VAPID_PUBLIC):
// il browser si abbona con quella, e una firma fatta con un'altra coppia la
// rifiuta il servizio push (403). Cambiando le chiavi, si cambiano tutt'e due —
// e i browser già abbonati vanno riabbonati.
// ============================================================

export interface Abbonamento {
  endpoint: string
  keys: { p256dh: string; auth: string }
}

const enc = new TextEncoder()

function daB64url(s: string): Uint8Array {
  const b = atob(s.replace(/-/g, '+').replace(/_/g, '/') + '==='.slice((s.length + 3) % 4))
  return Uint8Array.from(b, c => c.charCodeAt(0))
}
function aB64url(b: Uint8Array): string {
  let s = ''
  for (const x of b) s += String.fromCharCode(x)
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}
function unisci(...parti: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parti.reduce((n, p) => n + p.length, 0))
  let i = 0
  for (const p of parti) { out.set(p, i); i += p.length }
  return out
}

async function hkdf(salt: Uint8Array, ikm: Uint8Array, info: Uint8Array, lunghezza: number): Promise<Uint8Array> {
  const k = await crypto.subtle.importKey('raw', ikm, 'HKDF', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt, info }, k, lunghezza * 8)
  return new Uint8Array(bits)
}

// ── VAPID (RFC 8292) ─────────────────────────────────────────────────────
let chiaveFirma: CryptoKey | null = null

async function firmaVapid(pubB64: string, privB64: string): Promise<CryptoKey> {
  if (chiaveFirma) return chiaveFirma
  const pub = daB64url(pubB64)
  chiaveFirma = await crypto.subtle.importKey('jwk', {
    kty: 'EC', crv: 'P-256', ext: true,
    x: aB64url(pub.slice(1, 33)), y: aB64url(pub.slice(33, 65)), d: privB64,
  }, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
  return chiaveFirma
}

async function jwtVapid(endpoint: string, pub: string, priv: string, soggetto: string): Promise<string> {
  const aud = new URL(endpoint).origin
  const testa = aB64url(enc.encode(JSON.stringify({ typ: 'JWT', alg: 'ES256' })))
  const corpo = aB64url(enc.encode(JSON.stringify({
    aud, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: soggetto,
  })))
  const dati = `${testa}.${corpo}`
  // Web Crypto restituisce già r‖s (64 byte), che è la forma che vuole il JWT.
  const firma = await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' },
    await firmaVapid(pub, priv), enc.encode(dati))
  return `${dati}.${aB64url(new Uint8Array(firma))}`
}

// ── Cifratura (RFC 8291, aes128gcm) ───────────────────────────────────────
export async function cifra(ab: Abbonamento, testo: string): Promise<Uint8Array> {
  const uaPub = daB64url(ab.keys.p256dh)
  const auth  = daB64url(ab.keys.auth)

  const loro = await crypto.subtle.importKey('raw', uaPub, { name: 'ECDH', namedCurve: 'P-256' }, false, [])
  const mie  = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']) as CryptoKeyPair
  const asPub = new Uint8Array(await crypto.subtle.exportKey('raw', mie.publicKey))
  const segreto = new Uint8Array(await crypto.subtle.deriveBits({ name: 'ECDH', public: loro }, mie.privateKey, 256))

  const ikm   = await hkdf(auth, segreto, unisci(enc.encode('WebPush: info\0'), uaPub, asPub), 32)
  const salt  = crypto.getRandomValues(new Uint8Array(16))
  const cek   = await hkdf(salt, ikm, enc.encode('Content-Encoding: aes128gcm\0'), 16)
  const nonce = await hkdf(salt, ikm, enc.encode('Content-Encoding: nonce\0'), 12)

  // Un record solo: il testo più il delimitatore 0x02 di «ultimo record».
  const chiaro = unisci(enc.encode(testo), new Uint8Array([2]))
  const k = await crypto.subtle.importKey('raw', cek, 'AES-GCM', false, ['encrypt'])
  const cifrato = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, k, chiaro))

  const rs = new Uint8Array([0, 0, 0x10, 0])   // 4096, uint32 big-endian
  return unisci(salt, rs, new Uint8Array([asPub.length]), asPub, cifrato)
}

/**
 * Manda una push a un browser abbonato.
 * `morto` = il servizio dice che quell'abbonamento non esiste più (404/410):
 * il browser l'ha revocato, o i dati del sito sono stati cancellati.
 */
export async function inviaWebPush(
  ab: Abbonamento, dati: Record<string, unknown>,
  pub: string, priv: string, soggetto: string,
): Promise<{ ok: boolean; risposta: string; morto: boolean }> {
  const corpo = await cifra(ab, JSON.stringify(dati))
  const res = await fetch(ab.endpoint, {
    method: 'POST',
    headers: {
      'Authorization':    `vapid t=${await jwtVapid(ab.endpoint, pub, priv, soggetto)}, k=${pub}`,
      'Content-Encoding': 'aes128gcm',
      'Content-Type':     'application/octet-stream',
      'TTL':              '3600',
      'Urgency':          'high',
    },
    body: corpo,
  })
  const risposta = await res.text()
  return { ok: res.ok, risposta, morto: res.status === 404 || res.status === 410 }
}
