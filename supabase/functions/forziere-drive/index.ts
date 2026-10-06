// Supabase Edge Function: il ponte fra `forziere.html` e la cartella del Forziere su
// Google Drive. Crea la cartella, apre i caricamenti, restituisce i file, li cancella.
//
// ⚠️ QUESTA FUNZIONE NON VEDE MAI NIENTE IN CHIARO. Tutto quello che le passa davanti è
// già cifrato con OpenPGP dalle 24 parole, che stanno solo nella testa di Salvatore e non
// arrivano qui né adesso né mai. È il motivo per cui il trasferimento può passare da un
// server senza che l'end-to-end si rompa.
//
// ⚠️ PERCHÉ NON DAL BROWSER, come per `backup-drive`: servirebbe un token Google con lo
// scope `drive` chiesto al login di ogni app. Qui la credenziale è un refresh token nei
// Secrets, e la pagina presenta il suo JWT Supabase.
//
// ⚠️ CHI PUÒ CHIAMARLA. Il JWT si verifica **contro Supabase**, non si decodifica e basta:
// un JWT si scrive a mano in dieci secondi. Passa il solo FORZIERE_EMAIL — Teresa, Rosa e
// Ada hanno un login valido e qui non c'entrano niente.
//
// ⚠️ IL CARICAMENTO NON PASSA DI QUI. `upload-url` chiede a Google un indirizzo di
// caricamento ripristinabile e lo restituisce: i byte vanno dal browser a Google diretti.
// Bufferare un video in una Edge Function vorrebbe dire un tetto di dimensione scritto
// da nessuna parte, che si scopre il giorno che si supera. Lo scaricamento invece passa
// di qui — Drive non ha indirizzi firmati — ma **in flusso**, senza mettersi il file in
// pancia: `new Response(risposta.body)` lo lascia scorrere.
//
// ⚠️ DA v2: UNA SOTTOCARTELLA PER SCOMPARTO. La cartella si chiama con un **uuid**, non
// col nome dello scomparto — quello sta cifrato in `scomparti.gpg` — quindi chi guarda il
// Drive vede quello che vedeva prima e nient'altro. Le azioni che ne discendono sono
// `mkdir`, `rmdir` e `move`; `upload-url`, `put`, `list` e `delete` accettano ora una
// cartella di destinazione.
//
// ⚠️ DA v3: LA CARTELLA SI CHIAMA «rooms» E STA DENTRO «AppSphere». Prima era
// «Forziere AppSphere» nella radice del Drive, accanto a «AppSphere_backups»: due
// cartelle sciolte per la stessa suite, e il nome diceva a chi guarda l'elenco che lì
// c'è un forziere — che è metà del lavoro buttato, come la bolla riservata in home.
// ⚠️ Il rename su Drive e questo cambio vanno INSIEME: la cartella si cerca per nome,
// quindi il codice vecchio su una cartella rinominata ne creerebbe una nuova e vuota.
//
// Secrets: GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, GDRIVE_REFRESH_TOKEN,
//          GDRIVE_APPSPHERE_FOLDER_ID e FORZIERE_EMAIL (facoltativi gli ultimi due).
//          ⚠️ NON usa GDRIVE_FOLDER_ID: quella è la cartella dei backup — oggi
//          «AppSphere/backups», sorella di questa — e mescolarle vorrebbe dire che una
//          rotazione sbagliata cancella il forziere.
// v4 — 2026-09-10

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};

const SUPABASE_URL = Deno.env.get('SUPABASE_URL')!;
const EMAIL_OK = (Deno.env.get('FORZIERE_EMAIL') ?? 'garsal1971@gmail.com').toLowerCase();

const DRIVE = 'https://www.googleapis.com/drive/v3';
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3';
const NOME_CARTELLA = 'rooms';
// La cartella «AppSphere» che tiene insieme forziere e backup. Facoltativa: se il secret
// non c'è, «rooms» nasce nella radice del Drive e tutto il resto funziona uguale.
const PADRE = Deno.env.get('GDRIVE_APPSPHERE_FOLDER_ID') ?? '';
const MIME_CARTELLA = 'application/vnd.google-apps.folder';

function risposta(corpo: unknown, stato = 200) {
  return new Response(JSON.stringify(corpo), {
    status: stato,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

// Un apostrofo o una barra rovescia dentro una query di Drive la spezzano. I nomi che
// passano di qui sono uuid, ma una funzione che si fida del chiamante è una funzione che
// prima o poi riceve altro.
function perQuery(s: string) {
  return s.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

// Il token di Drive dura un'ora e si tiene in memoria: rifarlo a ogni chiamata sarebbe un
// giro su oauth2.googleapis.com per ogni file caricato. Stessa scelta di `backup-drive`.
let tokenDrive: { valore: string; scade: number } | null = null;

async function accessoDrive(): Promise<string> {
  if (tokenDrive && tokenDrive.scade > Date.now() + 60_000) return tokenDrive.valore;

  const mancanti = ['GDRIVE_CLIENT_ID', 'GDRIVE_CLIENT_SECRET', 'GDRIVE_REFRESH_TOKEN']
    .filter((k) => !Deno.env.get(k));
  if (mancanti.length) throw new Error(`mancano i secret: ${mancanti.join(', ')}`);

  const r = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: Deno.env.get('GDRIVE_CLIENT_ID')!,
      client_secret: Deno.env.get('GDRIVE_CLIENT_SECRET')!,
      refresh_token: Deno.env.get('GDRIVE_REFRESH_TOKEN')!,
      grant_type: 'refresh_token',
    }),
  });
  const dati = await r.json();
  if (!r.ok || !dati.access_token) {
    throw new Error(`refresh token rifiutato: ${JSON.stringify(dati).slice(0, 200)}`);
  }
  tokenDrive = { valore: dati.access_token, scade: Date.now() + (dati.expires_in ?? 3600) * 1000 };
  return tokenDrive.valore;
}

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

// La cartella del forziere. Con lo scope `drive.file` l'applicazione vede soltanto ciò
// che ha creato lei, quindi cercarla per nome non può pescare una cartella altrui.
// ⚠️ Non è la cartella dei backup: quella contiene dump del database in chiaro-gzip, e
// mescolare le due cose vorrebbe dire che una rotazione sbagliata cancella il forziere.
//
// ⚠️ SI CERCA PER NOME E BASTA, anche col padre configurato: fra i risultati si PREFERISCE
// quello dentro «AppSphere», ma non lo si pretende. Pretendendolo, il giorno in cui il
// segreto arriva prima dello spostamento della cartella la ricerca non troverebbe niente.
// Il padre decide invece **dove nasce** una cartella nuova.
//
// ⚠️ LA CREA SOLO `init`, cioè la creazione del forziere, e per nessun altro motivo.
// Fino al 10 settembre 2026 la creava chiunque non la trovasse — ed è successo: quel
// giorno una ricerca andata a vuoto ha fatto nascere una SECONDA «rooms» vuota accanto a
// quella vera, e da lì in poi il forziere rispondeva «quel file non sta nella cartella del
// forziere» coi documenti tutti al loro posto. Una cartella nuova è un forziere nuovo:
// non è una cosa che si fa per ripiego.
//
// ⚠️ UNA RICERCA FALLITA NON È UNA CARTELLA CHE NON C'È. L'`if (r.ok)` lasciava cadere
// l'errore e si finiva a creare: un 401, un 500 di Drive o una rete storta diventavano un
// secondo forziere invece di un messaggio. Ora l'errore vero si alza.
//
// ⚠️ FRA PIÙ CARTELLE VINCE LA PIÙ VECCHIA (`orderBy=createdTime`), non la prima che
// Drive restituisce: l'ordine di quella risposta non è garantito, quindi «la prima» è un
// sorteggio che si rifà a ogni chiamata — il forziere si apriva o non si apriva a seconda
// del tiro. Il forziere vero è sempre il primo nato.
async function cartella(token: string, creaSeManca = false): Promise<string> {
  const q = new URLSearchParams({
    q: `name = '${NOME_CARTELLA}' and mimeType = '${MIME_CARTELLA}' and trashed = false`,
    fields: 'files(id,name,parents,createdTime)',
    orderBy: 'createdTime',
    pageSize: '10',
  });
  const r = await fetch(`${DRIVE}/files?${q}`, { headers: { Authorization: `Bearer ${token}` } });
  if (!r.ok) {
    throw new Error(`Drive cerca la cartella: HTTP ${r.status} — ${(await r.text()).slice(0, 200)}`);
  }
  const d = await r.json();
  const trovate: Array<{ id: string; parents?: string[] }> = d.files ?? [];
  if (trovate.length > 1) {
    console.warn(`⚠️ ${trovate.length} cartelle «${NOME_CARTELLA}» su Drive: uso la più vecchia`);
  }
  const dentro = PADRE ? trovate.filter((f) => (f.parents ?? []).includes(PADRE)) : [];
  if (dentro.length) return dentro[0].id;
  if (trovate.length) return trovate[0].id;

  if (!creaSeManca) {
    throw new Error(
      `la cartella «${NOME_CARTELLA}» non si trova su Drive. Non ne creo una nuova: ` +
      `sarebbe un forziere vuoto al posto del tuo`,
    );
  }
  const c = await fetch(`${DRIVE}/files?fields=id`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: NOME_CARTELLA,
      mimeType: MIME_CARTELLA,
      ...(PADRE ? { parents: [PADRE] } : {}),
    }),
  });
  if (!c.ok) throw new Error(`Drive mkdir: HTTP ${c.status} — ${(await c.text()).slice(0, 200)}`);
  return (await c.json()).id;
}

async function metadati(token: string, id: string) {
  const r = await fetch(
    `${DRIVE}/files/${encodeURIComponent(id)}?fields=id,name,mimeType,parents,size`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  if (!r.ok) throw new Error(`Drive get: HTTP ${r.status}`);
  return await r.json();
}

// La cartella dove scrivere: la radice del forziere, oppure una sua sottocartella DIRETTA.
// ⚠️ Un solo livello, e non è una semplificazione da togliere il giorno che servisse di
// più: un albero da percorrere è un controllo che prima o poi lascia passare qualcosa, e
// qui quel qualcosa sarebbe un file fuori dal forziere. Gli scomparti sono piatti per la
// stessa ragione per cui sono organizzazione e non separazione.
async function destinazione(token: string, padre: string, id: string): Promise<string> {
  if (!id || id === padre) return padre;
  const m = await metadati(token, id);
  if (m.mimeType !== MIME_CARTELLA) throw new Error('quella non è una cartella');
  if (!Array.isArray(m.parents) || !m.parents.includes(padre)) {
    throw new Error('quella cartella non sta nel forziere');
  }
  return id;
}

// ⚠️ Il file si tocca solo se sta nel forziere. Senza il controllo sul padre, un id
// qualsiasi raggiungerebbe qualunque file creato dall'applicazione — i backup compresi.
// «Nel forziere» vuol dire nella radice **o in una sua sottocartella diretta**: il
// controllo scende di un livello e si ferma lì, per la ragione scritta in `destinazione`.
async function dentroLaCartella(token: string, id: string, padre: string) {
  const m = await metadati(token, id);
  const genitori: string[] = Array.isArray(m.parents) ? m.parents : [];
  if (genitori.includes(padre)) return m;
  for (const g of genitori) {
    try {
      const n = await metadati(token, g);
      if (n.mimeType === MIME_CARTELLA && Array.isArray(n.parents) && n.parents.includes(padre)) {
        return m;
      }
    } catch { /* un genitore che non si legge non è un genitore buono */ }
  }
  throw new Error('quel file non sta nella cartella del forziere');
}

// L'elenco di una cartella, seguendo le pagine: fermarsi alla prima non darebbe un errore,
// darebbe qualche file in meno.
async function elenco(token: string, dove: string) {
  const file: Array<Record<string, unknown>> = [];
  let pagina: string | undefined;
  do {
    const q = new URLSearchParams({
      q: `'${perQuery(dove)}' in parents and trashed = false`,
      fields: 'nextPageToken, files(id,name,size,mimeType,modifiedTime)',
      pageSize: '500',
    });
    if (pagina) q.set('pageToken', pagina);
    const r = await fetch(`${DRIVE}/files?${q}`, { headers: { Authorization: `Bearer ${token}` } });
    if (!r.ok) throw new Error(`Drive list: HTTP ${r.status}`);
    const d = await r.json();
    file.push(...(d.files ?? []));
    pagina = d.nextPageToken;
  } while (pagina);
  return file;
}

Deno.serve(async (req) => {
  if (req.method === 'OPTIONS') return new Response('ok', { headers: corsHeaders });

  try {
    const email = await chiChiama(req);
    if (!email) return risposta({ ok: false, error: 'serve un login valido' }, 401);
    if (email !== EMAIL_OK) return risposta({ ok: false, error: 'non autorizzato' }, 403);

    const body = await req.json().catch(() => ({}));
    const azione = String(body.azione ?? '');
    const token = await accessoDrive();
    // ⚠️ `init` è la SOLA azione che può far nascere la cartella: la chiama il pulsante
    // che crea il forziere, e nessun altro. Ogni altra azione, se non la trova, lo dice.
    const padre = await cartella(token, azione === 'init');

    // Dove sta il forziere. La pagina se lo scrive in `frz_vault.drive_folder_id`.
    if (azione === 'init') {
      return risposta({ ok: true, cartella: padre });
    }

    // Una cartella per scomparto. Il NOME è un uuid che sceglie la pagina: il nome vero
    // dello scomparto è cifrato in `scomparti.gpg` e qui non arriva mai.
    if (azione === 'mkdir') {
      const nome = String(body.nome ?? '').trim();
      if (!nome) return risposta({ ok: false, error: 'serve il nome della cartella' }, 400);
      const r = await fetch(`${DRIVE}/files?fields=id`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: nome, mimeType: MIME_CARTELLA, parents: [padre] }),
      });
      if (!r.ok) {
        return risposta({ ok: false, error: `Drive mkdir: HTTP ${r.status} — ${(await r.text()).slice(0, 200)}` }, 502);
      }
      return risposta({ ok: true, id: (await r.json()).id });
    }

    // ⚠️ Si rifiuta di cancellare una cartella che contiene ancora qualcosa. Drive butta
    // via una cartella **col suo contenuto**, mentre `frz_files.box_id` è
    // `ON DELETE SET NULL`: cancellando uno scomparto i file devono tornare in «Senza
    // scomparto», non sparire. Chi chiama sposta prima e cancella dopo.
    if (azione === 'rmdir') {
      const id = String(body.id ?? '');
      if (!id) return risposta({ ok: false, error: "serve l'id della cartella" }, 400);
      const dove = await destinazione(token, padre, id);
      if (dove === padre) return risposta({ ok: false, error: 'la radice non si cancella' }, 400);
      const dentro = await elenco(token, dove);
      if (dentro.length) {
        return risposta({ ok: false, error: `la cartella contiene ancora ${dentro.length} file` }, 409);
      }
      const r = await fetch(`${DRIVE}/files/${encodeURIComponent(dove)}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!r.ok && r.status !== 404) {
        return risposta({ ok: false, error: `Drive rmdir: HTTP ${r.status}` }, 502);
      }
      return risposta({ ok: true });
    }

    // Sposta un file fra la radice e uno scomparto (o fra due scomparti). Drive non ha una
    // «move»: si aggiunge il padre nuovo e si tolgono quelli vecchi nella stessa PATCH.
    if (azione === 'move') {
      const id = String(body.id ?? '');
      if (!id) return risposta({ ok: false, error: "serve l'id del file" }, 400);
      const m = await dentroLaCartella(token, id, padre);
      const dove = await destinazione(token, padre, String(body.cartella ?? ''));
      const vecchi: string[] = (Array.isArray(m.parents) ? m.parents : []).filter((p: string) => p !== dove);
      if (!vecchi.length) return risposta({ ok: true, id, cartella: dove });
      const q = new URLSearchParams({
        addParents: dove,
        removeParents: vecchi.join(','),
        fields: 'id,parents',
      });
      const r = await fetch(`${DRIVE}/files/${encodeURIComponent(id)}?${q}`, {
        method: 'PATCH',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: '{}',
      });
      if (!r.ok) return risposta({ ok: false, error: `Drive move: HTTP ${r.status} — ${(await r.text()).slice(0, 200)}` }, 502);
      return risposta({ ok: true, id, cartella: dove });
    }

    // Apre un caricamento: i byte poi vanno dal browser a Google, non da qui.
    if (azione === 'upload-url') {
      const nome = String(body.nome ?? '').trim();
      if (!nome) return risposta({ ok: false, error: "serve il nome del file" }, 400);
      const dove = await destinazione(token, padre, String(body.cartella ?? ''));
      const r = await fetch(`${UPLOAD}/files?uploadType=resumable&fields=id`, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json; charset=UTF-8',
          // Google vuole sapere quanto arriverà, per poter riprendere un caricamento
          // interrotto invece di ricominciarlo.
          ...(body.bytes ? { 'X-Upload-Content-Length': String(body.bytes) } : {}),
          'X-Upload-Content-Type': 'application/octet-stream',
        },
        body: JSON.stringify({ name: nome, parents: [dove] }),
      });
      if (!r.ok) {
        return risposta({ ok: false, error: `Drive upload-url: HTTP ${r.status} — ${(await r.text()).slice(0, 200)}` }, 502);
      }
      const url = r.headers.get('Location');
      if (!url) return risposta({ ok: false, error: 'Google non ha dato l\'indirizzo di caricamento' }, 502);
      return risposta({ ok: true, url });
    }

    // Gli oggetti di servizio (indice.gpg, scorciatoia.gpg, scomparti.gpg, contenuto.gpg)
    // sono poche centinaia di byte: per loro un caricamento ripristinabile sarebbe tre
    // viaggi di rete per niente.
    // Fa anche da RIPIEGO per i file veri quando il caricamento diretto a Google non
    // passa (una policy del browser, un proxy aziendale che rompe la richiesta PUT fra
    // domini): meglio un file che entra passando di qui che un forziere in cui non si
    // riesce a mettere niente.
    // ⚠️ Il tetto è scritto qui e non lasciato al caso: questi byte stanno tutti nella
    // memoria della funzione, e senza un limite esplicito il punto di rottura si
    // scoprirebbe il giorno che si carica il file sbagliato.
    if (azione === 'put') {
      const nome = String(body.nome ?? '').trim();
      const dati = String(body.base64 ?? '');
      if (!nome || !dati) return risposta({ ok: false, error: 'servono nome e contenuto' }, 400);
      if (dati.length > 12_000_000) {
        return risposta({ ok: false, error: 'troppo grande per «put»: serve «upload-url»' }, 400);
      }
      const bin = Uint8Array.from(atob(dati), (c) => c.charCodeAt(0));
      const dove = await destinazione(token, padre, String(body.cartella ?? ''));

      // Se esiste già lo si SOVRASCRIVE invece di crearne un secondo: due `indice.gpg`
      // nella stessa cartella e non si saprebbe più quale è quello buono.
      let id = String(body.id ?? '');
      if (id) { try { await dentroLaCartella(token, id, padre); } catch { id = ''; } }

      // ⚠️ `perNome` lo chiedono SOLO gli indici, e non è una comodità estesa ai documenti:
      // là il nome è un uuid e due file non si chiamano mai uguale, quindi un upsert per
      // nome non aggiungerebbe niente e aggiungerebbe un modo di sovrascrivere un
      // documento per sbaglio. Un indice invece è «il contenuto di questa cartella»: ce
      // n'è uno solo per definizione, e il suo id può essersi perso col database.
      if (!id && body.perNome) {
        const q = new URLSearchParams({
          q: `name = '${perQuery(nome)}' and '${perQuery(dove)}' in parents and trashed = false`,
          fields: 'files(id)',
          pageSize: '2',
        });
        const cerca = await fetch(`${DRIVE}/files?${q}`, { headers: { Authorization: `Bearer ${token}` } });
        if (cerca.ok) {
          const d = await cerca.json();
          if (d.files?.length) id = d.files[0].id;
        }
      }

      const meta = id ? {} : { name: nome, parents: [dove] };
      const confine = 'frz' + crypto.randomUUID().replace(/-/g, '');
      const testa = `--${confine}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(meta)}\r\n--${confine}\r\nContent-Type: application/octet-stream\r\n\r\n`;
      const coda = `\r\n--${confine}--\r\n`;
      const enc = new TextEncoder();
      const t = enc.encode(testa), c = enc.encode(coda);
      // ⚠️ Non `[...t, ...bin, ...c]`: lo spread di centinaia di migliaia di elementi in un
      // letterale d'array sfonda il limite degli argomenti e fallisce solo sui file grandi.
      const corpo = new Uint8Array(t.length + bin.length + c.length);
      corpo.set(t, 0); corpo.set(bin, t.length); corpo.set(c, t.length + bin.length);

      const r = await fetch(
        id ? `${UPLOAD}/files/${encodeURIComponent(id)}?uploadType=multipart&fields=id`
           : `${UPLOAD}/files?uploadType=multipart&fields=id`,
        {
          method: id ? 'PATCH' : 'POST',
          headers: { Authorization: `Bearer ${token}`, 'Content-Type': `multipart/related; boundary=${confine}` },
          body: corpo,
        },
      );
      if (!r.ok) return risposta({ ok: false, error: `Drive put: HTTP ${r.status} — ${(await r.text()).slice(0, 200)}` }, 502);
      return risposta({ ok: true, id: (await r.json()).id, cartella: dove });
    }

    // Scaricamento IN FLUSSO: i byte attraversano la funzione senza fermarcisi dentro.
    if (azione === 'get') {
      const id = String(body.id ?? '');
      if (!id) return risposta({ ok: false, error: "serve l'id del file" }, 400);
      const m = await dentroLaCartella(token, id, padre);
      const bin = await fetch(`${DRIVE}/files/${encodeURIComponent(id)}?alt=media`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!bin.ok || !bin.body) {
        return risposta({ ok: false, error: `Drive download: HTTP ${bin.status}` }, 502);
      }
      return new Response(bin.body, {
        headers: {
          ...corsHeaders,
          'Content-Type': 'application/octet-stream',
          ...(m.size ? { 'Content-Length': String(m.size) } : {}),
        },
      });
    }

    if (azione === 'delete') {
      const id = String(body.id ?? '');
      if (!id) return risposta({ ok: false, error: "serve l'id del file" }, 400);
      await dentroLaCartella(token, id, padre);
      const r = await fetch(`${DRIVE}/files/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!r.ok && r.status !== 404) {
        return risposta({ ok: false, error: `Drive delete: HTTP ${r.status}` }, 502);
      }
      return risposta({ ok: true });
    }

    // Serve a ritrovare gli oggetti di servizio quando la riga di `frz_vault` si è persa:
    // il forziere deve poter ripartire dal solo Drive. `cartella` guarda dentro uno
    // scomparto; `dentro: true` scende anche nelle sottocartelle, un livello solo, che è
    // tutto quel che c'è.
    if (azione === 'list') {
      const dove = await destinazione(token, padre, String(body.cartella ?? ''));
      const file = await elenco(token, dove);
      if (body.dentro && dove === padre) {
        for (const f of file.filter((x) => x.mimeType === MIME_CARTELLA)) {
          for (const g of await elenco(token, String(f.id))) {
            file.push({ ...g, cartella: f.id });
          }
        }
      }
      return risposta({ ok: true, cartella: dove, radice: padre, file });
    }

    return risposta({ ok: false, error: `azione sconosciuta: ${azione}` }, 400);
  } catch (e) {
    console.error('forziere-drive:', e);
    return risposta({ ok: false, error: String((e as Error)?.message ?? e) }, 500);
  }
});
