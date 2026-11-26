// Supabase Edge Function: riceve dall'APK nativa (sezione 📨 Inoltro) gli SMS e i
// messaggi WhatsApp già filtrati sul telefono e li scrive in `msg_messaggi`.
//
// ⚠️ CHI PUÒ CHIAMARLA: il JWT dell'utente, verificato CONTRO SUPABASE
// (/auth/v1/user) e non solo decodificato, e passa il solo `INOLTRO_EMAIL`
// (di partenza garsal1971@gmail.com). Nessun token segreto nell'app.
// ⚠️ Scrive col service role: la tabella non ha policy di INSERT.
// ⚠️ Doppioni: `impronta` è unica, quindi un rinvio della coda è innocuo
// (upsert con ignoreDuplicates = ON CONFLICT DO NOTHING).
//
// Corpo: { messaggi: [{ fonte, mittente, chat, testo, ricevuto_il, impronta }] }
//        (accetta anche un messaggio solo, senza l'array)
// Risposta: { ok, ricevuti, scritti }
// v1 — 2026-11-26

import { createClient } from 'https://esm.sh/@supabase/supabase-js@2.45.4';

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
const SERVICE_KEY = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
const EMAIL_OK = (Deno.env.get('INOLTRO_EMAIL') ?? 'garsal1971@gmail.com').toLowerCase();
const MAX_PER_CHIAMATA = 200;
const MAX_TESTO = 10_000;

function risposta(corpo: unknown, stato = 200) {
  return new Response(JSON.stringify(corpo), {
    status: stato,
    headers: { 'Content-Type': 'application/json' },
  });
}

async function chiChiama(req: Request): Promise<{ id: string; email: string } | null> {
  const header = req.headers.get('Authorization') ?? '';
  if (!header.toLowerCase().startsWith('bearer ')) return null;
  const r = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { Authorization: header, apikey: Deno.env.get('SUPABASE_ANON_KEY') ?? '' },
  });
  if (!r.ok) return null;
  const u = await r.json();
  if (!u?.id) return null;
  return { id: u.id, email: (u.email ?? '').toLowerCase() };
}

function testoONull(v: unknown, max = 500): string | null {
  if (typeof v !== 'string') return null;
  const t = v.trim();
  return t ? t.slice(0, max) : null;
}

Deno.serve(async (req) => {
  if (req.method !== 'POST') return risposta({ ok: false, error: 'usa POST' }, 405);

  const utente = await chiChiama(req);
  if (!utente) return risposta({ ok: false, error: 'serve un login valido' }, 401);
  if (utente.email !== EMAIL_OK) return risposta({ ok: false, error: 'non autorizzato' }, 403);

  let corpo: any;
  try { corpo = await req.json(); } catch { return risposta({ ok: false, error: 'JSON non valido' }, 400); }
  const elenco: any[] = Array.isArray(corpo?.messaggi) ? corpo.messaggi : [corpo];
  if (elenco.length > MAX_PER_CHIAMATA) {
    return risposta({ ok: false, error: `al più ${MAX_PER_CHIAMATA} messaggi per chiamata` }, 400);
  }

  const righe = [];
  const scartati: string[] = [];
  for (const m of elenco) {
    const fonte = m?.fonte;
    const testo = typeof m?.testo === 'string' ? m.testo.slice(0, MAX_TESTO) : '';
    const impronta = testoONull(m?.impronta, 128);
    const quando = new Date(m?.ricevuto_il ?? '');
    if ((fonte !== 'sms' && fonte !== 'whatsapp') || !testo.trim() || !impronta || isNaN(quando.getTime())) {
      scartati.push(impronta ?? '?');
      continue;
    }
    righe.push({
      user_id: utente.id,
      fonte,
      mittente: testoONull(m?.mittente),
      chat: testoONull(m?.chat),
      testo,
      ricevuto_il: quando.toISOString(),
      impronta,
    });
  }

  if (!righe.length) return risposta({ ok: true, ricevuti: elenco.length, scritti: 0, scartati });

  const sb = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
  const { data, error } = await sb
    .from('msg_messaggi')
    .upsert(righe, { onConflict: 'impronta', ignoreDuplicates: true })
    .select('id');
  if (error) return risposta({ ok: false, error: error.message }, 500);

  return risposta({ ok: true, ricevuti: elenco.length, scritti: data?.length ?? 0, scartati });
});
