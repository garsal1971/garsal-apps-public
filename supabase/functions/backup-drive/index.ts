// Supabase Edge Function: l'unico ponte fra `relazione.html` e la cartella dei
// backup su Google Drive. Elenca le fotografie e ne restituisce una.
//
// ⚠️ PERCHÉ NON DAL BROWSER. La pagina non può parlare con Drive: servirebbe
// un token Google con lo scope `drive.readonly`, cioè il permesso di leggere
// **tutto** il Drive di Salvatore chiesto al login di ogni app, per arrivare a
// una cartella sola. Qui invece la credenziale è un refresh token che sta nei
// Secrets, la pagina presenta il suo JWT Supabase e non vede altro che quella
// cartella.
//
// ⚠️ CHI PUÒ CHIAMARLA. Il JWT si verifica **contro Supabase**, non si legge e
// basta: un JWT si scrive a mano in dieci secondi, e questa funzione apre il
// patrimonio di famiglia. Passa il solo `BACKUP_EMAIL` (di partenza
// garsal1971@gmail.com) — Teresa, Rosa e Ada hanno un login valido e qui non
// c'entrano niente.
//
// ⚠️ SI LEGGE E BASTA. Nessuna scrittura, nessuna cancellazione: la rotazione
// la fa il workflow, che è l'unico posto dove qualcosa si cancella. Una
// funzione raggiungibile dal browser che sa cancellare i backup è un backup
// che un giorno non c'è più.
//
// Secrets: GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, GDRIVE_REFRESH_TOKEN,
//          GDRIVE_FOLDER_ID, e BACKUP_EMAIL (facoltativo).
// v1 — 2026-09-04

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
const CARTELLA     = Deno.env.get('GDRIVE_FOLDER_ID') ?? '';
const EMAIL_OK     = (Deno.env.get('BACKUP_EMAIL') ?? 'garsal1971@gmail.com').toLowerCase();

const DRIVE = 'https://www.googleapis.com/drive/v3';

function risposta(corpo: unknown, stato = 200) {
  return new Response(JSON.stringify(corpo), {
    status: stato,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

// Il token di Drive dura un'ora e si tiene in memoria: rifarlo a ogni
// chiamata sarebbe un giro in più su oauth2.googleapis.com per ogni tocco
// nella pagina. È la stessa scelta di `send-notifications` con FCM.
let tokenDrive: { valore: string; scade: number } | null = null;

async function accessoDrive(): Promise<string> {
  if (tokenDrive && tokenDrive.scade > Date.now() + 60_000) return tokenDrive.valore;

  const mancanti = ['GDRIVE_CLIENT_ID', 'GDRIVE_CLIENT_SECRET', 'GDRIVE_REFRESH_TOKEN', 'GDRIVE_FOLDER_ID']
    .filter((k) => !Deno.env.get(k));
  if (mancanti.length) throw new Error(`mancano i secret: ${mancanti.join(', ')}`);

  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id:     Deno.env.get('GDRIVE_CLIENT_ID')!,
      client_secret: Deno.env.get('GDRIVE_CLIENT_SECRET')!,
      refresh_token: Deno.env.get('GDRIVE_REFRESH_TOKEN')!,
      grant_type:    'refresh_token',
    }),
  });
  const dati = await r.json();
  if (!r.ok || !dati.access_token) {
    throw new Error(`refresh token rifiutato: ${JSON.stringify(dati).slice(0, 200)}`);
  }
  tokenDrive = { valore: dati.access_token, scade: Date.now() + (dati.expires_in ?? 3600) * 1000 };
  return tokenDrive.valore;
}

// Chi sta chiamando. ⚠️ Si chiede a Supabase invece di decodificare il JWT:
// la firma la può verificare solo lui, e senza quel giro basterebbe un token
// scritto a mano.
async function chiChiama(req: Request): Promise<string | null> {
  const header = req.headers.get('Authorization') ?? '';
  if (!header.toLowerCase().startsWith('bearer ')) return null;
  const r = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { Authorization: header, apikey: Deno.env.get('SUPABASE_ANON_KEY') ?? '' },
  });
  if (!r.ok) return null;
  const u = await r.json();
  return (u?.email ?? '').toLowerCase() || null;
}

async function elenco(token: string) {
  const file: Array<Record<string, unknown>> = [];
  let pagina: string | undefined;
  do {
    const q = new URLSearchParams({
      q: `'${CARTELLA}' in parents and trashed = false`,
      fields: 'nextPageToken, files(id,name,size,modifiedTime,mimeType)',
      orderBy: 'name desc',
      pageSize: '200',
    });
    if (pagina) q.set('pageToken', pagina);
    const r = await fetch(`${DRIVE}/files?${q}`, { headers: { Authorization: `Bearer ${token}` } });
    if (!r.ok) throw new Error(`Drive list: HTTP ${r.status} — ${(await r.text()).slice(0, 200)}`);
    const dati = await r.json();
    file.push(...(dati.files ?? []));
    pagina = dati.nextPageToken;
  } while (pagina);

  // Una fotografia per data, coi suoi file dentro: è come la pagina la mostra,
  // e raggrupparla qui evita che quella regola viva in due posti.
  const perGiorno = new Map<string, Array<Record<string, unknown>>>();
  for (const f of file) {
    const nome = String(f.name ?? '');
    const giorno = /^\d{4}-\d{2}-\d{2}/.test(nome) ? nome.slice(0, 10) : 'senza data';
    if (!perGiorno.has(giorno)) perGiorno.set(giorno, []);
    perGiorno.get(giorno)!.push({
      id: f.id,
      nome: nome.slice(11) || nome,
      bytes: Number(f.size ?? 0),
      modificato: f.modifiedTime,
    });
  }
  return [...perGiorno.entries()]
    .sort((a, b) => b[0].localeCompare(a[0]))
    .map(([giorno, file]) => ({ giorno, file }));
}

// ⚠️ Il file si scarica solo se sta in QUELLA cartella: senza il controllo sul
// padre, un id qualsiasi aprirebbe qualunque file del Drive di Salvatore —
// cioè la funzione diventerebbe esattamente quello che si è evitato non
// chiedendo `drive.readonly` alla pagina.
async function prendi(token: string, id: string) {
  const meta = await fetch(`${DRIVE}/files/${encodeURIComponent(id)}?fields=id,name,parents,size,mimeType`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!meta.ok) throw new Error(`Drive get: HTTP ${meta.status}`);
  const m = await meta.json();
  if (!Array.isArray(m.parents) || !m.parents.includes(CARTELLA)) {
    throw new Error('quel file non sta nella cartella dei backup');
  }
  const bin = await fetch(`${DRIVE}/files/${encodeURIComponent(id)}?alt=media`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!bin.ok) throw new Error(`Drive download: HTTP ${bin.status}`);
  return { nome: String(m.name ?? ''), testo: await bin.text() };
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });

  try {
    const email = await chiChiama(req);
    if (!email) return risposta({ ok: false, error: 'serve un login valido' }, 401);
    if (email !== EMAIL_OK) return risposta({ ok: false, error: 'non autorizzato' }, 403);

    const { azione = 'list', id = '' } = await req.json().catch(() => ({}));
    const token = await accessoDrive();

    if (azione === 'list') {
      // L'id della cartella non è una credenziale: senza il login Google di
      // Salvatore non apre niente. Serve alla pagina per il collegamento
      // «scarica da Drive», che è l'unica strada per i dump gzippati.
      return risposta({ ok: true, cartella: CARTELLA, backup: await elenco(token) });
    }

    if (azione === 'get') {
      if (!id) return risposta({ ok: false, error: 'serve l\'id del file' }, 400);
      const f = await prendi(token, String(id));
      // ⚠️ Solo la relazione torna come testo. Un dump gzippato passato di qui
      // diventerebbe una stringa rotta e un megabyte di JSON per niente: quelli
      // si scaricano da Drive, che è dove sono.
      if (!/\.html?$/i.test(f.nome)) {
        return risposta({ ok: false, error: 'da qui si legge solo la relazione; i dump si scaricano da Drive' }, 400);
      }
      return risposta({ ok: true, nome: f.nome, html: f.testo });
    }

    return risposta({ ok: false, error: `azione sconosciuta: ${azione}` }, 400);
  } catch (e) {
    console.error('backup-drive:', e);
    return risposta({ ok: false, error: String((e as Error)?.message ?? e) }, 500);
  }
});
