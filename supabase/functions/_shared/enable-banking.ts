// Codice comune delle Edge Functions che parlano con Enable Banking.
//
// Fino al 21/11/2026 queste funzioni stavano copiate a mano in otto file
// (enable-banking-* e revolut-auto-categorize). Cambiandone una qui, vanno
// ridistribuite TUTTE le funzioni che la importano: deploy.yml lo fa da sé
// quando il commit tocca supabase/functions/_shared/.

export const ENABLE_BANKING_API_BASE = 'https://api.enablebanking.com';

export function base64url(input: ArrayBuffer | string): string {
  const bytes = typeof input === 'string' ? new TextEncoder().encode(input) : new Uint8Array(input);
  let str = '';
  for (const b of bytes) str += String.fromCharCode(b);
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Web Crypto accetta solo PKCS8 ("PRIVATE KEY"). Se la chiave scaricata da Enable Banking è
// in formato PKCS1 ("RSA PRIVATE KEY"), l'import fallisce qui — va convertita con:
// openssl pkcs8 -topk8 -inform PEM -outform PEM -in key.pem -out key_pkcs8.pem -nocrypt
async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const pemBody = pem
    .replace(/-----BEGIN (RSA )?PRIVATE KEY-----/, '')
    .replace(/-----END (RSA )?PRIVATE KEY-----/, '')
    .replace(/\s+/g, '');
  const binaryDer = Uint8Array.from(atob(pemBody), (c) => c.charCodeAt(0));
  return crypto.subtle.importKey(
    'pkcs8',
    binaryDer.buffer,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
}

// Il JWT con cui l'applicazione si presenta a Enable Banking (RS256, kid = app id, 1 ora).
export async function createEnableBankingJWT(appId: string, privateKeyPem: string): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const header = { typ: 'JWT', alg: 'RS256', kid: appId };
  const body = { iss: 'enablebanking.com', aud: 'api.enablebanking.com', iat: now, exp: now + 3600 };
  const signingInput = `${base64url(JSON.stringify(header))}.${base64url(JSON.stringify(body))}`;
  const key = await importPrivateKey(privateKeyPem);
  const signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(signingInput));
  return `${signingInput}.${base64url(signature)}`;
}

// Il sub del JWT Supabase, letto senza verificarne la firma: la verifica la fa la
// piattaforma (verify_jwt) o la query successiva sotto RLS.
export function decodeSupabaseJwtSub(token: string): string | null {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return payload.sub || null;
  } catch {
    return null;
  }
}

// Alcune banche pretendono di sapere da quale IP arriva l'utente che sta autorizzando: lo
// dichiarano in required_psu_headers nel catalogo /aspsps (UniCredit IT chiede
// "psu-ip-address"). Senza quell'header il consenso viene comunque autorizzato, ma la banca
// non espone nessun conto — e non arriva nessun errore che lo spieghi.
// L'IP è quello del browser dell'utente, che raggiunge la Edge Function in x-forwarded-for:
// se manca (chiamata da un job, senza utente davanti) l'header non si inventa.
export function psuHeaders(req: Request): Record<string, string> {
  const fwd = req.headers.get('x-forwarded-for') || '';
  const ip = fwd.split(',')[0].trim();
  if (!ip) return {};
  const headers: Record<string, string> = { 'psu-ip-address': ip };
  const ua = req.headers.get('user-agent');
  if (ua) headers['psu-user-agent'] = ua;
  return headers;
}

// Il primo dei campi indicati che c'è davvero: i nomi nella risposta di Enable Banking
// variano da banca a banca.
export function pick(obj: any, ...keys: string[]): unknown {
  for (const k of keys) {
    if (obj && obj[k] !== undefined && obj[k] !== null) return obj[k];
  }
  return null;
}
