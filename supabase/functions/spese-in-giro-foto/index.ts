// Supabase Edge Function: gli scontrini di «Spese in giro» — le spese di un viaggio in bici.
//
// L'APK non fa il login: si accoppia col codice del viaggio e da lì in poi si presenta col
// token del suo telefono. Un token non è un JWT, quindi da PostgREST non apre lo Storage —
// e un bucket pubblico o scrivibile da chiunque abbia la anon key sarebbe un album di
// scontrini aperto a Internet.
//
// Qui in mezzo il giro si chiude: la funzione chiede al database di chi è quel token
// (`vg_viaggio_del_token`, eseguibile dal solo service role) e poi scrive o legge col service
// role, dentro la cartella di QUEL viaggio e in nessun'altra.
//
// ⚠️ La cartella è `<viaggio_id>/…` e il controllo sul prefisso è la sola cosa che impedisce
// a un token di aprire lo scontrino di un altro viaggio. Il percorso non arriva mai dal
// client: per leggere si passa l'id della voce, e il percorso lo dice il database
// (`vg_scontrino_del_token`), che verifica che quella voce sia del viaggio giusto.
//
// Tre azioni, tutte POST con JSON:
//   { azione: 'carica',  token, dati (base64), tipo? }        → { ok, path }
//   { azione: 'leggi',   token, voce_id }                      → { ok, url } (firmato, 1 h)
//   { azione: 'cancella',token, path }                         → { ok }
//
// v1 — 2026-09-08

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
const SERVICE_KEY  = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
const BUCKET       = 'vg-scontrini';
// 8 MB: una foto di scontrino ridotta dal telefono ne pesa qualche centinaio di KB, e questo
// è lo stesso tetto dichiarato sul bucket. Oltre non è una foto, è un errore.
const MAX_BYTES    = 8 * 1024 * 1024;
const TIPI_OK      = ['image/jpeg', 'image/png', 'image/webp'];

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status, headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });

const ko = (error: string, status = 400) => json({ ok: false, error }, status);

/** Una RPC col service role. Le due che servono qui hanno l'EXECUTE revocato ad anon. */
async function rpc(nome: string, params: Record<string, unknown>) {
  const res = await fetch(`${SUPABASE_URL}/rest/v1/rpc/${nome}`, {
    method: 'POST',
    headers: {
      apikey: SERVICE_KEY,
      Authorization: `Bearer ${SERVICE_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(params),
  });
  if (!res.ok) return { ok: false, error: `RPC ${nome}: HTTP ${res.status}` };
  return await res.json();
}

/**
 * Il bucket esiste? Se no lo si crea, privato.
 *
 * ⚠️ Non è una ridondanza inutile rispetto alla migration: quell'INSERT in storage.buckets
 * può non passare (il ruolo della migration non sempre ci scrive) e lì è volutamente non
 * fatale — un deploy fermo su una riga di storage si porterebbe dietro tutto il resto. Qui
 * il primo caricamento rimedia, invece di fallire con un «bucket not found» che nessuno sa
 * come si aggiusta dal telefono.
 */
async function assicuraBucket() {
  const testa = await fetch(`${SUPABASE_URL}/storage/v1/bucket/${BUCKET}`, {
    headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
  });
  if (testa.ok) return;
  await fetch(`${SUPABASE_URL}/storage/v1/bucket`, {
    method: 'POST',
    headers: {
      apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      id: BUCKET, name: BUCKET, public: false,
      file_size_limit: MAX_BYTES, allowed_mime_types: TIPI_OK,
    }),
  });
}

/** base64 → bytes, senza passare da una stringa intermedia più grande del file. */
function daBase64(b64: string): Uint8Array {
  const pulito = b64.includes(',') ? b64.slice(b64.indexOf(',') + 1) : b64;
  const bin = atob(pulito);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });
  if (req.method !== 'POST')    return ko('Solo POST', 405);

  let body: Record<string, unknown>;
  try {
    body = await req.json();
  } catch {
    return ko('JSON non valido');
  }

  const azione = String(body.azione ?? '');
  const token  = String(body.token ?? '').trim();
  if (!token) return ko('Token mancante', 401);

  const chi = await rpc('vg_viaggio_del_token', { p_token: token });
  if (!chi?.ok) return ko(chi?.error ?? 'Codice non valido', 401);
  const viaggioId = String(chi.viaggio_id);

  // ── carica ────────────────────────────────────────────────────────────────
  if (azione === 'carica') {
    const tipo = String(body.tipo ?? 'image/jpeg');
    if (!TIPI_OK.includes(tipo)) return ko('Tipo di file non ammesso');

    let bytes: Uint8Array;
    try {
      bytes = daBase64(String(body.dati ?? ''));
    } catch {
      return ko('Immagine illeggibile');
    }
    if (bytes.length === 0)         return ko('Immagine vuota');
    if (bytes.length > MAX_BYTES)   return ko('Immagine troppo grande');

    await assicuraBucket();

    const est  = tipo === 'image/png' ? 'png' : tipo === 'image/webp' ? 'webp' : 'jpg';
    // Il nome è un uuid: gli scontrini di un viaggio stanno tutti nella sua cartella, e due
    // foto scattate nello stesso secondo dai due telefoni non devono sovrascriversi.
    const path = `${viaggioId}/${crypto.randomUUID()}.${est}`;

    const up = await fetch(`${SUPABASE_URL}/storage/v1/object/${BUCKET}/${path}`, {
      method: 'POST',
      headers: {
        apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}`,
        'Content-Type': tipo, 'x-upsert': 'false',
      },
      body: bytes,
    });
    if (!up.ok) return ko(`Caricamento fallito: HTTP ${up.status} ${await up.text()}`, 502);

    return json({ ok: true, path });
  }

  // ── leggi ─────────────────────────────────────────────────────────────────
  // Si passa l'id della VOCE e non il percorso: è il database a dire dove sta quella foto, e
  // a rifiutare una voce che non è del viaggio di questo token.
  if (azione === 'leggi') {
    const voceId = String(body.voce_id ?? '');
    if (!voceId) return ko('Voce mancante');

    const sc = await rpc('vg_scontrino_del_token', { p_token: token, p_voce_id: voceId });
    if (!sc?.ok) return ko(sc?.error ?? 'Nessuno scontrino', 404);

    const path = String(sc.path);
    if (!path.startsWith(`${viaggioId}/`)) return ko('Scontrino di un altro viaggio', 403);

    const firma = await fetch(`${SUPABASE_URL}/storage/v1/object/sign/${BUCKET}/${path}`, {
      method: 'POST',
      headers: {
        apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ expiresIn: 3600 }),
    });
    if (!firma.ok) return ko(`Firma fallita: HTTP ${firma.status}`, 502);
    const { signedURL } = await firma.json();
    return json({ ok: true, url: `${SUPABASE_URL}/storage/v1${signedURL}` });
  }

  // ── cancella ──────────────────────────────────────────────────────────────
  // Serve a una cosa sola: la foto caricata e poi rifatta prima di salvare la voce, che
  // altrimenti resterebbe nel bucket senza nessuna riga che dica cos'è.
  if (azione === 'cancella') {
    const path = String(body.path ?? '');
    if (!path.startsWith(`${viaggioId}/`)) return ko('Percorso non di questo viaggio', 403);

    const del = await fetch(`${SUPABASE_URL}/storage/v1/object/${BUCKET}/${path}`, {
      method: 'DELETE',
      headers: { apikey: SERVICE_KEY, Authorization: `Bearer ${SERVICE_KEY}` },
    });
    if (!del.ok) return ko(`Cancellazione fallita: HTTP ${del.status}`, 502);
    return json({ ok: true });
  }

  return ko('Azione sconosciuta');
});
