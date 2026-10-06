// Supabase Edge Function: una richiesta di 🛠️ Modifiche diventa una **issue**
// su GitHub, con le sue schermate dentro.
//
// ⚠️ PERCHÉ NON DAL BROWSER. Per aprire una issue serve un token GitHub con
// diritto di scrittura sulla repo: in chiaro dentro `modifiche.html` sarebbe
// un token che chiunque apra la pagina si porta via — e quel token scrive
// nella repo di tutte le app. Qui sta nei Secrets, e la pagina presenta il
// suo JWT Supabase.
//
// ⚠️ CHI PUÒ CHIAMARLA. Il JWT si verifica **contro Supabase**, non si
// decodifica e basta: un JWT si scrive a mano in dieci secondi. Passa il solo
// `MODIFICHE_EMAIL` — Teresa, Rosa e Ada hanno un login valido e qui non
// c'entrano niente. È la stessa guardia di `backup-drive`.
//
// ⚠️ LA REPO DEV'ESSERE PRIVATA. Una issue porta dentro titolo, descrizione e
// schermate, e quelle schermate sono di Finanza, spese e patrimonio. Su una
// repo pubblica le leggerebbe chiunque passi — che è esattamente ciò che
// CLAUDE.md dice di non far finire lì. `garsal1971/garsal-apps` è privata dal
// 13 settembre 2026, ed è la condizione per cui questa funzione esiste.
//
// ⚠️ NIENTE COLONNA «issue_number» IN TABELLA. La verità è una sola e sta su
// GitHub: il corpo della issue porta `<!-- richiesta: <uuid> -->`, e da lì si
// sa cosa è già stato mandato. Una colonna accanto sarebbe una seconda verità
// che diverge il giorno che una issue si cancella a mano — e sarebbe una
// modifica di schema, che qui non è stata chiesta.
//
// 🤖 AZIONE `fix`. Apre la issue se non c'è ancora e ci scrive un commento
// con `@claude`: è quel commento a far partire `.github/workflows/claude.yml`,
// cioè una sessione di Claude Code che fa la modifica su un ramo `claude/…` —
// e da lì `deploy.yml` la porta su master, cioè in produzione. ⚠️ Il commento
// lo scrive il PAT, cioè Salvatore: un commento scritto col GITHUB_TOKEN non
// sveglierebbe nessun workflow, e l'action accetta solo chi ha diritto di
// scrittura sulla repo.
//
// Secrets: GH_PAT (obbligatorio), GH_REPO e MODIFICHE_EMAIL (facoltativi).
// 📨 STATI E ESITO (v3, issue #46). La pagina porta la richiesta a «inviata»
// dopo 🐙 e a «in corso» dopo 🤖; a «fatta» la porta la issue CHIUSA — il
// commento della fix chiede a Claude `Closes #N` nel commit SOLO a lavoro
// finito, quindi una fix fermata a metà resta «in corso», che è la verità.
// L'azione `esito` restituisce l'ultimo commento di Claude sulla issue, che la
// pagina mostra nella scheda.
// v3 — 2026-10-02

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
const SERVICE      = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? '';
const ANON         = Deno.env.get('SUPABASE_ANON_KEY') ?? '';
const PAT          = Deno.env.get('GH_PAT') ?? '';
const REPO         = Deno.env.get('GH_REPO') ?? 'garsal1971/garsal-apps';
const EMAIL_OK     = (Deno.env.get('MODIFICHE_EMAIL') ?? 'garsal1971@gmail.com').toLowerCase();

const BUCKET    = 'mod-immagini';
const ETICHETTA = 'modifiche';

// ⚠️ Un anno, e non due ore come nella pagina: quell'indirizzo resta scritto
// dentro la issue: firmato per due ore, il giorno dopo la issue mostrerebbe
// dei riquadri rotti. GitHub fa comunque da sé una copia delle immagini che
// incontra (il suo proxy), quindi quel che si vede nella issue sopravvive
// anche alla scadenza — l'indirizzo no, e il corpo porta accanto i percorsi
// nel bucket, che non scadono mai.
const FIRMA_SECONDI = 365 * 24 * 3600;

const STATI: Record<string, string> = {
  aperta: '📌 Da fare', inviata: '📨 Inviata', in_corso: '🔧 In corso', fatta: '✅ Fatta', scartata: '🚫 Scartata',
};

function risposta(corpo: unknown, stato = 200) {
  return new Response(JSON.stringify(corpo), {
    status: stato,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

const marcatore = (id: string) => `<!-- richiesta: ${id} -->`;
const RE_MARCATORE = /<!--\s*richiesta:\s*([0-9a-fA-F-]{36})\s*-->/;

// Chi sta chiamando. Si chiede a Supabase invece di leggere il JWT: la firma
// la può verificare solo lui.
async function chiChiama(req: Request): Promise<{ id: string; email: string } | null> {
  const header = req.headers.get('Authorization') ?? '';
  if (!header.toLowerCase().startsWith('bearer ')) return null;
  const r = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: { Authorization: header, apikey: ANON },
  });
  if (!r.ok) return null;
  const u = await r.json();
  if (!u?.id) return null;
  return { id: String(u.id), email: String(u.email ?? '').toLowerCase() };
}

// ── GitHub ────────────────────────────────────────────────────────────────
// ⚠️ L'errore vero si riporta com'è. Un «non sono riuscito a mandarla» su un
// token scaduto manda a cercare il guasto dalla parte sbagliata: è lo stesso
// inciampo del prefisso «Drive: » nel Forziere.
async function gh(percorso: string, init: RequestInit = {}) {
  if (!PAT) throw new Error('manca il secret GH_PAT: senza token non si apre nessuna issue');
  const r = await fetch(`https://api.github.com${percorso}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${PAT}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      'User-Agent': 'garsal-modifiche/1.0',
      'Content-Type': 'application/json',
      ...(init.headers ?? {}),
    },
  });
  const testo = await r.text();
  const dati = testo ? JSON.parse(testo) : null;
  if (!r.ok) {
    const e = new Error(`GitHub ${r.status}: ${dati?.message ?? testo.slice(0, 200)}`);
    (e as Error & { stato?: number }).stato = r.status;
    throw e;
  }
  return dati;
}

function perche(stato: number | undefined): string {
  if (stato === 401) return 'il token è scaduto o revocato: rifallo e riscrivi il secret GH_PAT';
  if (stato === 404) return `il token non vede ${REPO}: se è fine-grained va autorizzato proprio su questa repo`;
  if (stato === 403) return 'permessi insufficienti: al token serve Issues → Read and write';
  if (stato === 410) return `le issue sono spente su ${REPO}: si riaccendono da Settings → General → Features`;
  return '';
}

// L'etichetta si crea la prima volta e poi non serve più: con lei l'elenco
// delle richieste già mandate è una query sola e non una ricerca su tutte le
// issue della repo.
async function assicuraEtichetta() {
  try {
    await gh(`/repos/${REPO}/labels`, {
      method: 'POST',
      body: JSON.stringify({ name: ETICHETTA, color: '0F766E', description: 'Richieste scritte da 🛠️ Modifiche' }),
    });
  } catch (e) {
    // 422 = c'è già. Qualunque altro motivo non deve fermare la issue: senza
    // etichetta si apre lo stesso, si perde solo il modo comodo di ritrovarla.
    console.warn('etichetta:', String((e as Error).message));
  }
}

async function issueEsistenti(): Promise<Record<string, unknown>> {
  const fuori: Record<string, unknown> = {};
  for (let pagina = 1; pagina <= 5; pagina++) {
    const lista = await gh(
      `/repos/${REPO}/issues?state=all&per_page=100&labels=${ETICHETTA}&page=${pagina}`,
    ) as Array<Record<string, unknown>>;
    for (const i of lista) {
      if (i.pull_request) continue;            // /issues restituisce anche le PR
      const m = RE_MARCATORE.exec(String(i.body ?? ''));
      if (!m) continue;
      fuori[m[1].toLowerCase()] = {
        numero: i.number, url: i.html_url, stato: i.state, titolo: i.title,
        motivo: i.state_reason ?? null,   // 'completed' | 'not_planned' — scartata o fatta
      };
    }
    if (lista.length < 100) break;
  }
  return fuori;
}

// ── Supabase ──────────────────────────────────────────────────────────────
// ⚠️ Ogni lettura porta il filtro `user_id`: qui si scrive col service role,
// dove la RLS non vale, e senza quel filtro basterebbe l'id di una riga altrui
// per pubblicarla. È la stessa regola delle RPC `ob_action_*`.
async function db(percorso: string) {
  const r = await fetch(`${SUPABASE_URL}/rest/v1/${percorso}`, {
    headers: { apikey: SERVICE, Authorization: `Bearer ${SERVICE}` },
  });
  if (!r.ok) throw new Error(`database: HTTP ${r.status} — ${(await r.text()).slice(0, 200)}`);
  return await r.json();
}

async function firma(percorsi: string[]): Promise<Record<string, string>> {
  if (!percorsi.length) return {};
  const r = await fetch(`${SUPABASE_URL}/storage/v1/object/sign/${BUCKET}`, {
    method: 'POST',
    headers: { apikey: SERVICE, Authorization: `Bearer ${SERVICE}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ expiresIn: FIRMA_SECONDI, paths: percorsi }),
  });
  if (!r.ok) {
    console.warn('firma:', r.status, (await r.text()).slice(0, 200));
    return {};
  }
  const dati = await r.json() as Array<Record<string, string>>;
  const fuori: Record<string, string> = {};
  for (const d of dati) {
    // L'API risponde `signedURL`, il client JS `signedUrl`: si accettano
    // tutt'e due invece di scommettere su quale sia oggi, e l'indirizzo torna
    // relativo (`/object/sign/…`) quindi va completato.
    const firmato = d.signedURL ?? d.signedUrl;
    if (!d.path || !firmato) continue;
    fuori[d.path] = firmato.startsWith('http') ? firmato : `${SUPABASE_URL}/storage/v1${firmato}`;
  }
  return fuori;
}

/**
 * Le app di una richiesta, sempre come elenco.
 *
 * ⚠️ **Ripiega su `app` quando `apps` è vuoto**, e non è prudenza generica:
 * la colonna è arrivata dopo le righe (issue #23), e una richiesta che la
 * migration non avesse toccato aprirebbe altrimenti una issue con «**App:**
 * —» — cioè un dato sparito, non un dato mancante. È la stessa ragione per
 * cui il filtro di `riservato` va scritto `eq.false,is.null`. Gemella di
 * `appDi()` in `modifiche.html`: se cambia una, cambia anche l'altra.
 */
function appDi(r: Record<string, unknown>): string[] {
  const elenco = r.apps;
  if (Array.isArray(elenco) && elenco.length) return elenco.map(String);
  return r.app ? [String(r.app)] : [];
}

function corpo(r: Record<string, unknown>, foto: Array<Record<string, unknown>>, url: Record<string, string>, aggiornamento: boolean) {
  const righe: string[] = [];
  if (!aggiornamento) righe.push(marcatore(String(r.id)), '');
  righe.push(`**App:** ${appDi(r).join(' · ') || '—'}`);
  righe.push(`**Stato:** ${STATI[String(r.stato)] ?? String(r.stato)}`);
  righe.push(`**Scritta il** ${new Date(String(r.created_at)).toLocaleDateString('it-IT')}`);
  righe.push('', '### Cosa c\'è da cambiare', '', String(r.descrizione || '_(nessuna descrizione)_'));

  if (foto.length) {
    righe.push('', `### ${foto.length === 1 ? 'La schermata' : `Le ${foto.length} schermate`}`, '');
    foto.forEach((f, n) => {
      const u = url[String(f.storage_path)];
      righe.push(u ? `![schermata ${n + 1}](${u})` : `- (schermata ${n + 1} non firmata)`);
      righe.push('');
    });
    righe.push('<details><summary>Dove stanno davvero</summary>', '');
    righe.push(`Bucket privato \`${BUCKET}\`, un file per riga:`, '');
    foto.forEach((f) => righe.push(`- \`${f.storage_path}\``));
    righe.push('', 'Gli indirizzi qui sopra sono firmati e valgono un anno; i percorsi no,',
                   'quelli restano.', '', '</details>');
  }

  righe.push('', '---', '',
    aggiornamento
      ? '_Aggiornamento mandato da 🛠️ Modifiche._'
      : '_Scritta da 🛠️ Modifiche, l\'app delle richieste._');
  return righe.join('\n');
}

async function richiestaDi(userId: string, id: string) {
  const righe = await db(`mod_richieste?id=eq.${id}&user_id=eq.${userId}&select=*`) as Array<Record<string, unknown>>;
  if (!righe.length) throw new Error('richiesta non trovata');
  const foto = await db(
    `mod_immagini?richiesta_id=eq.${id}&user_id=eq.${userId}&select=storage_path,position&order=position.asc`,
  ) as Array<Record<string, unknown>>;
  const url = await firma(foto.map((f) => String(f.storage_path)));
  return { r: righe[0], foto, url };
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });

  try {
    const chi = await chiChiama(req);
    if (!chi) return risposta({ ok: false, error: 'serve un login valido' }, 401);
    if (chi.email !== EMAIL_OK) return risposta({ ok: false, error: 'non autorizzato' }, 403);

    const { azione = 'elenco', richiesta_id = '', testo = '' } =await req.json().catch(() => ({}));

    if (azione === 'prova') {
      if (!PAT) return risposta({ ok: false, error: 'manca il secret GH_PAT', rimedio: 'Supabase → Edge Functions → Secrets' });
      const repo = await gh(`/repos/${REPO}`) as Record<string, unknown>;
      if (!repo.has_issues) return risposta({ ok: false, error: `le issue sono spente su ${REPO}` });
      await gh(`/repos/${REPO}/issues?per_page=1&state=all`);
      return risposta({
        ok: true, repo: REPO, privata: !!repo.private,
        scrittura: !!(repo.permissions as Record<string, unknown> | undefined)?.push,
      });
    }

    if (azione === 'elenco') return risposta({ ok: true, repo: REPO, issue: await issueEsistenti() });

    if (azione === 'fix') {
      if (!richiesta_id) return risposta({ ok: false, error: 'serve l\'id della richiesta' }, 400);
      const id = String(richiesta_id).toLowerCase();
      const esistenti = await issueEsistenti();
      let issue = esistenti[id] as { numero: number; url: string; stato?: string; titolo?: string } | undefined;
      if (!issue) {
        const { r, foto, url } = await richiestaDi(chi.id, id);
        await assicuraEtichetta();
        const nuova = await gh(`/repos/${REPO}/issues`, {
          method: 'POST',
          body: JSON.stringify({ title: String(r.titolo).slice(0, 240), body: corpo(r, foto, url, false), labels: [ETICHETTA] }),
        }) as Record<string, unknown>;
        issue = { numero: Number(nuova.number), url: String(nuova.html_url), stato: 'open', titolo: String(nuova.title) };
      }
      const c = await gh(`/repos/${REPO}/issues/${issue.numero}/comments`, {
        method: 'POST',
        body: JSON.stringify({ body: [
          '@claude Implementa la modifica descritta in questa issue (e negli eventuali commenti di aggiornamento).',
          '',
          'Segui CLAUDE.md alla lettera: versione e BUILD_TIME aggiornati nello stesso commit,',
          'nessuna modifica a tabelle o campi JSON senza chiedere — in quel caso fermati e spiega qui cosa serve.',
          'Lavora su un ramo `claude/…`: il push lo porta in produzione da solo.',
          '',
          `Quando la modifica è completa PER INTERO, metti \`Closes #${issue.numero}\` nel messaggio del commit:`,
          'la issue chiusa porta la richiesta a «Fatta». Se ti fermi a metà (una domanda, una migration da',
          'confermare) NON metterlo: la richiesta deve restare «In corso».',
          '',
          '_Avviata da 🛠️ Modifiche._',
        ].join('\n') }),
      }) as Record<string, unknown>;
      return risposta({ ok: true, avviata: true, issue, commento: c.html_url });
    }

    // 🤖 L'ultimo commento di Claude sulla issue: è lì che scrive com'è andata
    // (e, mentre lavora, a che punto è). Si riconosce dall'autore — un bot il
    // cui login comincia per «claude» — e non dal testo, che cambia.
    if (azione === 'esito') {
      if (!richiesta_id) return risposta({ ok: false, error: 'serve l\'id della richiesta' }, 400);
      const id = String(richiesta_id).toLowerCase();
      // ⚠️ La richiesta deve essere di chi chiama, come in `richiestaDi`.
      const mie = await db(`mod_richieste?id=eq.${id}&user_id=eq.${chi.id}&select=id`) as unknown[];
      if (!mie.length) return risposta({ ok: false, error: 'richiesta non trovata' }, 404);
      const issue = (await issueEsistenti())[id] as { numero: number; url: string } | undefined;
      if (!issue) return risposta({ ok: true, testo: '' });
      let ultimo: Record<string, unknown> | null = null;
      for (let pagina = 1; pagina <= 5; pagina++) {
        const lista = await gh(`/repos/${REPO}/issues/${issue.numero}/comments?per_page=100&page=${pagina}`) as Array<Record<string, unknown>>;
        for (const c of lista) {
          const u = (c.user ?? {}) as Record<string, unknown>;
          if (u.type === 'Bot' && String(u.login ?? '').toLowerCase().startsWith('claude')) ultimo = c;
        }
        if (lista.length < 100) break;
      }
      if (!ultimo) return risposta({ ok: true, testo: '', issue });
      return risposta({
        ok: true, issue,
        testo: String(ultimo.body ?? ''),
        quando: ultimo.updated_at ?? ultimo.created_at,
        url: ultimo.html_url,
      });
    }

    // 💬 Una risposta all'esito, scritta dalla pagina: diventa un commento
    // `@claude …` sulla issue, che sveglia di nuovo il workflow. ⚠️ Il commento
    // lo scrive il PAT e non il GITHUB_TOKEN, come in `fix`: un evento creato dal
    // GITHUB_TOKEN non sveglia nessun workflow.
    if (azione === 'rispondi') {
      if (!richiesta_id) return risposta({ ok: false, error: 'serve l\'id della richiesta' }, 400);
      const scritto = String(testo ?? '').trim();
      if (!scritto) return risposta({ ok: false, error: 'scrivi la risposta' }, 400);
      const id = String(richiesta_id).toLowerCase();
      const mie = await db(`mod_richieste?id=eq.${id}&user_id=eq.${chi.id}&select=id`) as unknown[];
      if (!mie.length) return risposta({ ok: false, error: 'richiesta non trovata' }, 404);
      const issue = (await issueEsistenti())[id] as { numero: number; url: string } | undefined;
      if (!issue) return risposta({ ok: false, error: 'questa richiesta non ha ancora una issue' }, 404);
      const c = await gh(`/repos/${REPO}/issues/${issue.numero}/comments`, {
        method: 'POST',
        body: JSON.stringify({ body: ['@claude ' + scritto.slice(0, 20000), '', '_Risposta da 🛠️ Modifiche._'].join('\n') }),
      }) as Record<string, unknown>;
      return risposta({ ok: true, issue, commento: c.html_url });
    }

    if (azione === 'manda' || azione === 'aggiorna') {
      if (!richiesta_id) return risposta({ ok: false, error: 'serve l\'id della richiesta' }, 400);
      const id = String(richiesta_id).toLowerCase();
      const esistenti = await issueEsistenti();
      const gia = esistenti[id] as { numero: number; url: string } | undefined;

      // ⚠️ Due tocchi non fanno due issue. Il controllo è la issue stessa, non
      // una colonna in tabella: cancellata la issue, la richiesta torna da
      // mandare — che è quel che è davvero.
      if (gia && azione === 'manda') return risposta({ ok: true, gia: true, issue: gia });

      const { r, foto, url } = await richiestaDi(chi.id, id);

      if (gia) {
        const c = await gh(`/repos/${REPO}/issues/${gia.numero}/comments`, {
          method: 'POST', body: JSON.stringify({ body: corpo(r, foto, url, true) }),
        }) as Record<string, unknown>;
        return risposta({ ok: true, aggiornata: true, issue: gia, commento: c.html_url });
      }

      await assicuraEtichetta();
      const nuova = await gh(`/repos/${REPO}/issues`, {
        method: 'POST',
        body: JSON.stringify({
          title: String(r.titolo).slice(0, 240),
          body: corpo(r, foto, url, false),
          labels: [ETICHETTA],
        }),
      }) as Record<string, unknown>;
      return risposta({
        ok: true,
        issue: { numero: nuova.number, url: nuova.html_url, stato: 'open', titolo: nuova.title },
      });
    }

    return risposta({ ok: false, error: `azione sconosciuta: ${azione}` }, 400);
  } catch (e) {
    const err = e as Error & { stato?: number };
    console.error('modifiche-issue:', err);
    const spiega = perche(err.stato);
    return risposta({ ok: false, error: err.message, rimedio: spiega || undefined }, 500);
  }
});
