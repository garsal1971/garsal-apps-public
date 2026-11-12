/* ============================================================================
   scuola-ada.js v1.6.0 — accesso, database e barra in alto per le pagine
   della scuola di Ada (compiti, verifiche, calendario, impostazioni), e il
   piano di studio (SC.piano) che calendario e studio leggono uguale.

   La barra blu è quella di AppSphere (logo + «Garsal Apps», nome, ☰): il
   logo torna ad AppSphere, il ☰ porta alle quattro pagine e a «Esci».

   ⚠️ Deroga dichiarata alla regola «un'app = un file HTML»: le due pagine
   condividono login e chiamate a Supabase, e due copie divergerebbero.

   Chi entra:
   • Salvatore col token di AppSphere (localStorage.sb_token, login Google);
   • un ospite (Ada) con email e password, se in `cm_guest_access` ha la
     pagina. La prima password si imposta col link «Imposta la password»;
   • chiunque col pulsante «Accedi con Google» (v1.4.0): OAuth di Supabase,
     che torna sulla stessa pagina col token nell'ancora (letto in avvia()).
     ⚠️ La pagina deve stare fra i Redirect URLs di Supabase.

   🔔 Notifiche (v1.6.0): ☰ → 🔔 Notifiche abbona QUESTO browser al Web Push
   (riga di cm_push_devices, platform 'web', come ☰ → 💻 Browser in AppSphere).
   Le manda la Edge Function sc-promemoria a inizio e fine delle fasce di studio.
   ⚠️ VAPID è la stessa chiave pubblica di index.html e del secret VAPID_PUBLIC_KEY.
   ⚠️ Su iPhone il Web Push c'è solo per la pagina aggiunta alla schermata Home.

   ⚠️ La sessione dell'ospite sta in `localStorage.sc_session`, non in
   `garsal_session`: quella è di situazione-teresa / situazione-rosa, e sullo
   stesso browser le due pagine si ruberebbero il login a vicenda.
   ============================================================================ */
(function () {
  const URL_SB = 'https://jajlmmdsjlvzgcxiiypk.supabase.co';
  const KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImphamxtbWRzamx2emdjeGlpeXBrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Njk5NTU0NjYsImV4cCI6MjA4NTUzMTQ2Nn0.ikaipwxOvIn43epayQ4mSZQkXtin3aaGEPouafwJFxU';
  const OWNER = 'garsal1971@gmail.com';
  const CHIAVE = 'sc_session';

  let sessione = null, pagina = '', pendente = null;

  const leggi = () => { try { return JSON.parse(localStorage.getItem(CHIAVE)); } catch (e) { return null; } };
  const scrivi = s => { try { s ? localStorage.setItem(CHIAVE, JSON.stringify(s)) : localStorage.removeItem(CHIAVE); } catch (e) {} };
  const daToken = d => ({ access_token: d.access_token, refresh_token: d.refresh_token, expires_at: Date.now() + d.expires_in * 1000, user: d.user });

  async function utenteDi(token) {
    try {
      const r = await fetch(`${URL_SB}/auth/v1/user`, { headers: { apikey: KEY, Authorization: `Bearer ${token}` } });
      if (!r.ok) return null;
      const u = await r.json();
      return u && u.id ? u : null;
    } catch (e) { return null; }
  }

  async function rinnova() {
    if (!sessione || !sessione.refresh_token) return false;
    try {
      const r = await fetch(`${URL_SB}/auth/v1/token?grant_type=refresh_token`, {
        method: 'POST', headers: { apikey: KEY, 'Content-Type': 'application/json' },
        body: JSON.stringify({ refresh_token: sessione.refresh_token })
      });
      if (!r.ok) return false;
      sessione = daToken(await r.json());
      scrivi(sessione);
      return true;
    } catch (e) { return false; }
  }

  async function db(path, opz) {
    opz = opz || {};
    const fai = () => fetch(`${URL_SB}/rest/v1/${path}`, {
      method: opz.method || 'GET',
      headers: {
        apikey: KEY, Authorization: `Bearer ${sessione.access_token}`,
        'Content-Type': 'application/json', Prefer: opz.prefer || 'return=representation'
      },
      body: opz.body ? JSON.stringify(opz.body) : undefined
    });
    let r = await fai();
    if (r.status === 401 && await rinnova()) r = await fai();
    if (r.status === 401) { esci('La sessione è scaduta: rientra.'); throw new Error('Sessione scaduta'); }
    if (!r.ok) {
      const e = await r.json().catch(() => ({}));
      throw new Error(e.message || r.statusText);
    }
    return r.status === 204 ? null : r.json().catch(() => null);
  }

  async function autorizzato() {
    if ((sessione.user.email || '').toLowerCase() === OWNER) return true;
    try {
      const righe = await db(`cm_guest_access?select=page&page=eq.${encodeURIComponent(pagina)}&limit=1`);
      return righe.length > 0;
    } catch (e) { return false; }
  }

  /* ── Schermata di accesso ─────────────────────────────────────────── */
  function stili() {
    if (document.getElementById('sc-stili')) return;
    const s = document.createElement('style');
    s.id = 'sc-stili';
    s.textContent = `
.sc-login{max-width:400px;margin:8vh auto 0;padding-inline:max(16px,env(safe-area-inset-left,0px)) max(16px,env(safe-area-inset-right,0px));display:grid;gap:14px}
.sc-login .card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px;display:grid;gap:12px}
.sc-login h1{font-size:28px}
.sc-login label{display:grid;gap:4px;font-size:13px;color:var(--muted);font-weight:500}
.sc-login input{font:inherit;color:var(--fg);background:var(--bg);border:1px solid var(--line);border-radius:8px;padding:10px;min-width:0}
.sc-login .btn{font:inherit;font-weight:600;background:var(--accent);color:var(--on-accent);border:0;border-radius:8px;padding:11px 14px;cursor:pointer;min-height:44px}
.sc-login .google{font:inherit;font-weight:600;background:var(--card);color:var(--fg);border:1px solid var(--line);border-radius:8px;padding:11px 14px;cursor:pointer;min-height:44px;display:flex;align-items:center;justify-content:center;gap:10px}
.sc-login .oppure{text-align:center;font-size:13px;color:var(--muted);margin:0}
.sc-login .link{font:inherit;background:none;border:0;color:var(--accent);cursor:pointer;padding:4px 0;text-align:left;text-decoration:underline}
.sc-login .msg{font-size:14px;margin:0}
.sc-login .err{color:var(--danger)}
.sc-login .ok{color:var(--ok)}
.sc-utente{display:none}
body.sc-con-barra{padding-top:calc(56px + env(safe-area-inset-top,0px))}
#sc-barra{position:fixed;top:0;left:0;right:0;z-index:500;min-height:56px;box-sizing:border-box;display:flex;align-items:center;gap:10px;background:#0081C8;color:#fff;padding-top:env(safe-area-inset-top,0px);padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(8px,env(safe-area-inset-right,0px))}
#sc-barra a.sc-brand{display:flex;align-items:center;gap:8px;flex:1;min-width:0;color:#fff;text-decoration:none;font-family:"DM Sans",system-ui,sans-serif;font-size:15px;font-weight:600}
#sc-barra a.sc-brand svg{flex-shrink:0}
#sc-barra .sc-nome{color:rgba(255,255,255,.85);font-size:13px}
#sc-barra .sc-hb{background:none;border:0;cursor:pointer;padding:10px;display:flex;flex-direction:column;gap:5px;flex-shrink:0;min-height:44px;justify-content:center}
#sc-barra .sc-hb span{display:block;width:20px;height:2px;background:#fff;border-radius:2px}
#sc-menu{position:fixed;top:calc(56px + env(safe-area-inset-top,0px));right:0;min-width:200px;max-width:calc(100% - 32px);background:#fff;border-radius:0 0 0 14px;box-shadow:0 6px 24px rgba(0,0,0,.18);z-index:600;overflow:hidden}
#sc-menu a,#sc-menu button{display:block;width:100%;box-sizing:border-box;padding:15px 20px;text-align:left;background:none;border:0;border-bottom:1px solid #f0f0f0;font:inherit;font-size:15px;color:#222;text-decoration:none;cursor:pointer}
#sc-menu .attiva{font-weight:700;background:#eef5fb}
#sc-menu .esci{color:#c0392b;border-bottom:0}
#sc-nf{position:fixed;inset:0;z-index:700;background:rgba(0,0,0,.45);display:flex;align-items:flex-start;justify-content:center;padding:calc(70px + env(safe-area-inset-top,0px)) max(16px,env(safe-area-inset-right,0px)) 16px max(16px,env(safe-area-inset-left,0px))}
#sc-nf .card{background:var(--card,#fff);color:var(--fg,#222);border:1px solid var(--line,#ddd);border-radius:14px;padding:18px;width:100%;max-width:420px;display:grid;gap:12px}
#sc-nf .testa{display:flex;align-items:center;gap:8px}
#sc-nf h2{font-size:18px;margin:0;flex:1;min-width:0}
#sc-nf .x{font:inherit;font-size:18px;background:none;border:0;cursor:pointer;color:var(--muted,#666);min-width:44px;min-height:44px}
#sc-nf p{margin:0;font-size:14px;line-height:1.45}
#sc-nf .err{color:var(--danger,#c0392b)}
#sc-nf .ok{color:var(--ok,#1e8e3e)}
#sc-nf input{font:inherit;color:var(--fg,#222);background:var(--bg,#fff);border:1px solid var(--line,#ddd);border-radius:8px;padding:10px;min-width:0}
#sc-nf .btn{font:inherit;font-weight:600;background:var(--accent,#0081C8);color:var(--on-accent,#fff);border:0;border-radius:8px;padding:11px 14px;cursor:pointer;min-height:44px}
#sc-nf .btn.sec{background:none;color:var(--danger,#c0392b);border:1px solid var(--line,#ddd)}`;
    document.head.appendChild(s);
  }

  function box() {
    let el = document.getElementById('sc-login');
    if (!el) { el = document.createElement('div'); el.id = 'sc-login'; el.className = 'sc-login'; document.body.prepend(el); }
    return el;
  }

  function mostraLogin(messaggio, errore) {
    const app = document.getElementById('app'); if (app) app.hidden = true;
    const el = box(); el.hidden = false;
    el.innerHTML = `
      <h1>${document.title.replace(/\s+v[\d.]+$/, '')}</h1>
      <form class="card" id="sc-form">
        <button class="google" type="button" id="sc-google"><svg width="18" height="18" viewBox="0 0 48 48" aria-hidden="true"><path fill="#FFC107" d="M43.6 20.5H42V20H24v8h11.3C33.7 32.7 29.2 36 24 36c-6.6 0-12-5.4-12-12s5.4-12 12-12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 12.9 4 4 12.9 4 24s8.9 20 20 20 20-8.9 20-20c0-1.3-.1-2.4-.4-3.5z"/><path fill="#FF3D00" d="M6.3 14.7l6.6 4.8C14.7 15.1 19 12 24 12c3.1 0 5.8 1.2 7.9 3.1l5.7-5.7C34 6.1 29.3 4 24 4 16.3 4 9.7 8.3 6.3 14.7z"/><path fill="#4CAF50" d="M24 44c5.2 0 9.9-2 13.4-5.2l-6.2-5.2C29.2 35.1 26.7 36 24 36c-5.2 0-9.6-3.3-11.3-7.9l-6.5 5C9.5 39.6 16.2 44 24 44z"/><path fill="#1976D2" d="M43.6 20.5H42V20H24v8h11.3c-.8 2.2-2.2 4.2-4.1 5.6l6.2 5.2C37 39.2 44 34 44 24c0-1.3-.1-2.4-.4-3.5z"/></svg>Accedi con Google</button>
        <p class="oppure">oppure con email e password</p>
        <label>Email<input id="sc-email" type="email" autocomplete="username" required></label>
        <label>Password<input id="sc-pass" type="password" autocomplete="current-password"></label>
        <button class="btn" type="submit">Accedi</button>
        <button class="link" type="button" id="sc-imposta">Prima volta o password dimenticata? Imposta la password</button>
        <p class="msg ${errore ? 'err' : 'ok'}" id="sc-msg"${messaggio ? '' : ' hidden'}>${messaggio || ''}</p>
      </form>
      <p class="msg" style="color:var(--muted)">Salvatore: apri la pagina da <a href="/" style="color:var(--accent)">AppSphere</a>.</p>`;
    const msg = (t, err) => { const m = document.getElementById('sc-msg'); m.hidden = false; m.textContent = t; m.className = 'msg ' + (err ? 'err' : 'ok'); };
    document.getElementById('sc-form').addEventListener('submit', async e => {
      e.preventDefault();
      const email = document.getElementById('sc-email').value.trim().toLowerCase();
      const pass = document.getElementById('sc-pass').value;
      if (!pass) { msg('Scrivi la password, oppure tocca «Imposta la password».', true); return; }
      const b = e.target.querySelector('.btn'); b.disabled = true; b.textContent = 'Accesso in corso…';
      try {
        const r = await fetch(`${URL_SB}/auth/v1/token?grant_type=password`, {
          method: 'POST', headers: { apikey: KEY, 'Content-Type': 'application/json' },
          body: JSON.stringify({ email, password: pass })
        });
        if (!r.ok) throw new Error('Email o password non corrette.');
        sessione = daToken(await r.json()); scrivi(sessione);
        await entra();
      } catch (err) { msg(err.message, true); }
      finally { b.disabled = false; b.textContent = 'Accedi'; }
    });
    document.getElementById('sc-google').addEventListener('click', () => {
      const dove = encodeURIComponent(location.origin + location.pathname);
      location.href = `${URL_SB}/auth/v1/authorize?provider=google&redirect_to=${dove}`;
    });
    document.getElementById('sc-imposta').addEventListener('click', async () => {
      const email = document.getElementById('sc-email').value.trim().toLowerCase();
      if (!email) { msg('Scrivi prima la tua email qui sopra.', true); return; }
      try {
        const dove = encodeURIComponent(location.origin + location.pathname);
        const r = await fetch(`${URL_SB}/auth/v1/recover?redirect_to=${dove}`, {
          method: 'POST', headers: { apikey: KEY, 'Content-Type': 'application/json' }, body: JSON.stringify({ email })
        });
        if (!r.ok) throw new Error('Non sono riuscito a mandare la mail. Riprova fra un minuto.');
        msg('Ti ho mandato una mail: apri il link e scegli la password.', false);
      } catch (err) { msg(err.message, true); }
    });
  }

  function mostraNuovaPassword() {
    const el = box(); el.hidden = false;
    el.innerHTML = `
      <h1>Scegli la password</h1>
      <form class="card" id="sc-pw">
        <label>Nuova password (almeno 8 caratteri)<input id="sc-new" type="password" minlength="8" autocomplete="new-password" required></label>
        <button class="btn" type="submit">Salva e entra</button>
        <p class="msg err" id="sc-msg" hidden></p>
      </form>`;
    document.getElementById('sc-pw').addEventListener('submit', async e => {
      e.preventDefault();
      const b = e.target.querySelector('.btn'); b.disabled = true;
      try {
        const r = await fetch(`${URL_SB}/auth/v1/user`, {
          method: 'PUT',
          headers: { apikey: KEY, Authorization: `Bearer ${pendente.access_token}`, 'Content-Type': 'application/json' },
          body: JSON.stringify({ password: document.getElementById('sc-new').value })
        });
        if (!r.ok) { const d = await r.json().catch(() => ({})); throw new Error(d.msg || 'Password non salvata.'); }
        sessione = pendente; pendente = null; scrivi(sessione);
        await entra();
      } catch (err) { const m = document.getElementById('sc-msg'); m.hidden = false; m.textContent = err.message; b.disabled = false; }
    });
  }

  let quandoPronto = null;
  async function entra() {
    if (sessione.expires_at && Date.now() > sessione.expires_at - 60000 && !(await rinnova())) {
      sessione = null; scrivi(null); mostraLogin('La sessione è scaduta: rientra.', true); return;
    }
    if (!(await autorizzato())) {
      const email = sessione.user.email;
      sessione = null; scrivi(null);
      mostraLogin(`L'account ${email} non è autorizzato per questa pagina.`, true);
      return;
    }
    box().hidden = true;
    const app = document.getElementById('app'); if (app) app.hidden = false;
    barra();
    if (quandoPronto) quandoPronto();
  }

  /* ── Barra in alto, come in AppSphere ─────────────────────────────── */
  const PAGINE = [['compiti-ada.html', '📚 Compiti'], ['verifiche-ada.html', '📝 Verifiche'],
                  ['studio-ada.html', '⏱️ Studio di oggi'], ['calendario-ada.html', '📅 Calendario'], ['impostazioni-ada.html', '⚙️ Impostazioni'],
                  ['da-classeviva.html', '🏫 ClasseViva']];
  const LOGO = '<svg width="22" height="22" viewBox="0 0 64 64" aria-hidden="true"><circle cx="32" cy="32" r="32" fill="#fff"/><circle cx="20" cy="20" r="12" fill="#DE8026"/><circle cx="44" cy="20" r="12" fill="#CA372E"/><circle cx="20" cy="44" r="12" fill="#32974C"/><circle cx="44" cy="44" r="12" fill="#824FA5"/><circle cx="32" cy="32" r="12" fill="#067BC0"/></svg>';
  function barra() {
    let b = document.getElementById('sc-barra');
    if (!b) {
      b = document.createElement('header'); b.id = 'sc-barra'; document.body.prepend(b);
      const m = document.createElement('nav'); m.id = 'sc-menu'; m.hidden = true; document.body.prepend(m);
      document.addEventListener('click', e => {
        const menu = document.getElementById('sc-menu');
        if (e.target.closest('#sc-hb')) { menu.hidden = !menu.hidden; return; }
        if (!e.target.closest('#sc-menu')) menu.hidden = true;
      });
    }
    // Il nome: come in AppSphere, la prima parola del nome Google o dell'email.
    const u = sessione.user, nome = ((u.user_metadata && u.user_metadata.full_name) || u.email || '').split(/[ @.]/)[0];
    const esc = t => String(t).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    b.innerHTML = `<a class="sc-brand" href="/">${LOGO}<span>Garsal Apps</span></a>
      <span class="sc-nome">${esc(nome.charAt(0).toUpperCase() + nome.slice(1))}</span>
      <button type="button" class="sc-hb" id="sc-hb" aria-label="Menu"><span></span><span></span><span></span></button>`;
    const menu = document.getElementById('sc-menu');
    menu.innerHTML = PAGINE.map(([f, t]) => `<a href="${f}"${f === pagina ? ' class="attiva" aria-current="page"' : ''}>${t}</a>`).join('')
      + '<button type="button" id="sc-notifiche">🔔 Notifiche</button>'
      + '<a href="/">🏠 AppSphere</a><button type="button" class="esci" id="sc-esci">↩ Esci</button>';
    document.getElementById('sc-esci').addEventListener('click', () => { menu.hidden = true; esci(); });
    document.getElementById('sc-notifiche').addEventListener('click', () => { menu.hidden = true; notifiche(); });
    b.hidden = false; document.body.classList.add('sc-con-barra');
  }

  /* ── 🔔 Notifiche: questo browser e il Web Push ─────────────────────── */
  const VAPID = 'BEYSdajFGQIvzUtFrpn3HbzMVIFTyUWM-fOPnOYDXlqdq6qOjAjcfk5yCb1emwn1DMGPeaXTrIawYRLBwIr8Opo';
  const nfSupportato = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window;
  const nfIos = () => /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  const nfHome = () => (window.matchMedia && matchMedia('(display-mode: standalone)').matches) || navigator.standalone === true;
  const nfChiave = b64 => { const s = atob((b64 + '='.repeat((4 - b64.length % 4) % 4)).replace(/-/g, '+').replace(/_/g, '/')); return Uint8Array.from(s, c => c.charCodeAt(0)); };
  async function nfAbbonamento() {
    const reg = await navigator.serviceWorker.register('/sw-notifiche.js');
    return { reg, sub: await reg.pushManager.getSubscription() };
  }
  const nfEsc = t => String(t).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  async function notifiche(msg, errore) {
    let el = document.getElementById('sc-nf');
    if (!el) {
      el = document.createElement('div'); el.id = 'sc-nf'; document.body.appendChild(el);
      el.addEventListener('click', e => {
        if (e.target.closest('.x')) el.remove();
        else if (e.target.closest('#nf-attiva')) nfAttiva();
        else if (e.target.closest('#nf-togli')) nfTogli();
      });
    }
    const testa = '<div class="testa"><h2>🔔 Notifiche</h2><button type="button" class="x" aria-label="Chiudi">✕</button></div>'
      + '<p>All\'inizio di ogni fascia di studio, e alla fine se qualcosa di oggi è rimasto da fare.</p>';
    const riga = msg ? `<p class="${errore ? 'err' : 'ok'}">${nfEsc(msg)}</p>` : '';
    let corpo;
    if (!nfSupportato()) {
      corpo = nfIos() && !nfHome()
        ? '<p>Su iPhone le notifiche arrivano solo all\'app sulla schermata Home: tocca <b>Condividi</b> → <b>Aggiungi alla schermata Home</b>, apri lo Studio da lì e torna qui.</p>'
        : '<p class="err">Questo browser non sa ricevere notifiche push.</p>';
    } else if (Notification.permission === 'denied') {
      corpo = '<p class="err">Le notifiche per questo sito sono <b>bloccate</b>: riattivale dalle impostazioni del browser (o, su iPhone, da Impostazioni → Notifiche), poi riapri questa finestra.</p>';
    } else {
      el.innerHTML = `<div class="card">${testa}<p>Controllo…</p></div>`;
      let attivo = false;
      try {
        const { sub } = await nfAbbonamento();
        if (sub) {
          const r = await db(`cm_push_devices?select=id&token=eq.${encodeURIComponent(sub.endpoint)}&enabled=eq.true`);
          attivo = r.length > 0;
        }
      } catch (e) { console.warn('🔔', e); }
      corpo = attivo
        ? '<p class="ok">✅ Questo dispositivo riceve le notifiche.</p><button type="button" class="btn sec" id="nf-togli">Spegni su questo dispositivo</button>'
        : '<input type="text" id="nf-nome" placeholder="Nome del dispositivo (es. iPhone)" aria-label="Nome del dispositivo">'
          + '<button type="button" class="btn" id="nf-attiva">🔔 Attiva su questo dispositivo</button>';
    }
    el.innerHTML = `<div class="card">${testa}${riga}${corpo}</div>`;
  }

  async function nfAttiva() {
    const nome = (document.getElementById('nf-nome')?.value || '').trim() || (nfIos() ? 'iPhone' : 'Browser');
    try {
      if (await Notification.requestPermission() !== 'granted') return notifiche('Permesso per le notifiche non concesso.', true);
      const { reg, sub: gia } = await nfAbbonamento();
      const sub = gia || await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: nfChiave(VAPID) });
      const json = sub.toJSON();
      // Upsert sul token (= endpoint): riabbonando lo stesso browser si aggiorna la sua riga.
      await db('cm_push_devices?on_conflict=token', { method: 'POST', prefer: 'resolution=merge-duplicates,return=minimal', body: {
        user_id: sessione.user.id, token: json.endpoint, platform: 'web', device_name: nome, enabled: true,
        web_push: json, app_version: 'scuola-ada.js v1.6.0', last_seen_at: new Date().toISOString() } });
      notifiche('Fatto: le notifiche arriveranno qui.');
    } catch (e) { console.error('🔔', e); notifiche('Non sono riuscito ad attivarle: ' + e.message, true); }
  }

  async function nfTogli() {
    try {
      const { sub } = await nfAbbonamento();
      if (sub) {
        await db(`cm_push_devices?token=eq.${encodeURIComponent(sub.endpoint)}`, { method: 'DELETE', prefer: 'return=minimal' });
        await sub.unsubscribe().catch(() => {});
      }
      notifiche('Spente su questo dispositivo.');
    } catch (e) { notifiche('Non sono riuscito a spegnerle: ' + e.message, true); }
  }

  function esci(messaggio) {
    // ⚠️ `sb_token` non si tocca: è il login di AppSphere, e toglierlo da qui
    // farebbe uscire Salvatore da tutte le altre app.
    sessione = null; scrivi(null);
    const b = document.getElementById('sc-barra'); if (b) b.hidden = true;
    document.body.classList.remove('sc-con-barra');
    mostraLogin(messaggio || '', !!messaggio);
  }

  async function avvia(nomePagina, pronto) {
    pagina = nomePagina; quandoPronto = pronto; stili();
    const app = document.getElementById('app'); if (app) app.hidden = true;

    // Ritorno dal link della mail (recupero o invito): prima la password.
    const h = new URLSearchParams(location.hash.slice(1));
    if (h.get('access_token')) {
      const at = h.get('access_token'), tipo = h.get('type');
      history.replaceState(null, '', location.pathname);
      const user = await utenteDi(at);
      if (user) {
        const s = { access_token: at, refresh_token: h.get('refresh_token'), expires_at: Date.now() + (+h.get('expires_in') || 3600) * 1000, user };
        if (tipo === 'recovery' || tipo === 'invite' || tipo === 'signup') { pendente = s; mostraNuovaPassword(); return; }
        sessione = s; scrivi(s); await entra(); return;
      }
    }

    // Il token di AppSphere: Salvatore, oppure Ada se è entrata da lì (v1.0.1)
    // e ha la pagina in `cm_guest_access`. Senza, si passa al login suo.
    let sbt = null; try { sbt = localStorage.getItem('sb_token'); } catch (e) {}
    if (sbt) {
      const user = await utenteDi(sbt);
      if (user) {
        sessione = { access_token: sbt, refresh_token: null, expires_at: null, user };
        if ((user.email || '').toLowerCase() === OWNER || await autorizzato()) { await entra(); return; }
        sessione = null;
      }
    }

    // Ospite: la sessione salvata.
    sessione = leggi();
    if (sessione && sessione.user) { await entra(); return; }
    sessione = null;
    mostraLogin(sbt ? 'Il login di AppSphere è scaduto: accedi con Google qui sotto.' : '', !!sbt);
  }

  // ── Il piano di studio (v1.5.0) ───────────────────────────────────────────
  // Una regola sola per calendario-ada.html e studio-ada.html (prima erano due
  // copie). Per ogni giorno da oggi: tempo utile = fasce × 60 / (60 + pausa ogni
  // ora); prima i compiti (lezioni di domani, il pomeriggio prima), poi quel che
  // è stato rimandato a quel giorno (sc_spostamenti), poi nel tempo che avanza le
  // ore degli argomenti non «studiati», dalla verifica più vicina e solo nei
  // giorni prima di lei.
  // sc_spostamenti: un compito o un pezzo di argomento tolto da un giorno non ci
  // torna da solo; «cancellato» esce dal piano, «rimandato» va sul giorno `a`
  // (NULL = nel contenitore «da rimandare»). Un rimandato a un giorno ormai
  // passato si ignora: quel tempo torna nel piano normale.
  const pIso = d => `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
  const pPiu = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
  const pParse = s => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d); };
  const pMin = t => { const [h, m] = String(t || '').split(':').map(Number); return isNaN(h) ? 0 : h * 60 + (m || 0); };

  function piano(dati, finoA, giorniMin) {
    const { materie, orario, fatti, ver, args, imp } = dati, sposta = dati.sposta || [];
    const n = new Date(), t = new Date(n.getFullYear(), n.getMonth(), n.getDate()), tIso = pIso(t);
    const mat = new Map(materie.map(m => [m.id, m])), ora = new Map(orario.map(o => [o.id, o]));
    const argo = new Map(args.map(a => [a.id, a])), verOf = new Map(ver.map(v => [v.id, v]));
    const fatto = new Set(fatti.map(f => f.giorno + '|' + f.orario_id));
    const ultima = ver.length ? pParse(ver[ver.length - 1].data) : t;
    const fine = new Date(Math.max(pPiu(ultima, -1), pPiu(t, (giorniMin || 7) - 1), finoA || 0));
    const pausa = imp.pausa_minuti || 0, fasce = imp.fasce || {};

    // Gli spostamenti ancora validi, già risolti in nome e minuti.
    const validi = [];
    for (const s of sposta) {
      if (s.stato === 'rimandato' && s.a && s.a < tIso) continue;
      if (s.tipo === 'compito') {
        const o = ora.get(s.rif), m = o && mat.get(o.materia_id);
        if (!m) continue;
        if (s.stato === 'cancellato' && s.da < tIso) continue;
        validi.push({ sp: s, tipo: 'compito', rif: s.rif, nome: m.nome, min: s.min, ok: fatto.has(s.da + '|' + s.rif) });
      } else {
        const a = argo.get(s.rif), v = a && verOf.get(a.verifica_id);
        if (!a || !v || a.studiato) continue;
        validi.push({ sp: s, tipo: 'argomento', rif: s.rif, a, v, min: s.min });
      }
    }
    const tolti = new Set(validi.map(x => x.rif + '|' + x.sp.da));   // non tornano da soli su quel giorno
    const usati = new Map();                                          // minuti di argomento già spostati
    for (const x of validi) if (x.tipo === 'argomento') usati.set(x.rif, (usati.get(x.rif) || 0) + x.min);

    const daFare = [];
    for (const v of ver) for (const a of args.filter(a => a.verifica_id === v.id).sort((x, y) => x.posizione - y.posizione))
      if (!a.studiato && Number(a.ore) > 0) daFare.push({ v, a, resto: Math.max(0, Number(a.ore) * 60 - (usati.get(a.id) || 0)) });

    const giorni = [];
    for (let d = new Date(t); d <= fine; d = pPiu(d, 1)) {
      const g = d.getDay(), giornoIso = pIso(d), domani = pPiu(d, 1).getDay();
      const lf = fasce[g] || [];
      const lordo = lf.reduce((a, f) => a + Math.max(0, pMin(f[1]) - pMin(f[0])), 0);
      const cap = Math.floor(lordo * 60 / (60 + pausa)); // gemella di utile() in impostazioni-ada.html
      const qui = validi.filter(x => x.sp.stato === 'rimandato' && x.sp.a === giornoIso);
      const compiti = (domani >= 1 && domani <= 6 ? orario
        .filter(o => o.giorno === domani && !o.senza_compiti && (mat.get(o.materia_id)?.minuti || 0) > 0 && !tolti.has(o.id + '|' + giornoIso))
        .map(o => ({ id: o.id, da: giornoIso, nome: mat.get(o.materia_id).nome, min: mat.get(o.materia_id).minuti, ok: fatto.has(giornoIso + '|' + o.id) })) : [])
        .concat(qui.filter(x => x.tipo === 'compito').map(x => ({ id: x.rif, da: x.sp.da, nome: x.nome, min: x.min, ok: x.ok, sp: x.sp })));
      const minCompiti = compiti.filter(c => !c.ok).reduce((a, c) => a + c.min, 0);
      const studio = qui.filter(x => x.tipo === 'argomento').map(x => ({ v: x.v, a: x.a, min: x.min, sp: x.sp }));
      const fisso = minCompiti + studio.reduce((a, s) => a + s.min, 0);
      let libero = Math.max(0, cap - fisso);
      for (const x of daFare) {
        if (libero <= 0) break;
        if (x.resto <= 0 || x.v.data <= giornoIso || tolti.has(x.a.id + '|' + giornoIso)) continue;
        const q = Math.min(x.resto, libero);
        x.resto -= q; libero -= q;
        studio.push({ v: x.v, a: x.a, min: q });
      }
      giorni.push({ d, giornoIso, lf, cap, compiti, minCompiti, studio, libero, sopra: Math.max(0, fisso - cap),
        verifiche: ver.filter(v => v.data === giornoIso) });
    }
    const mancano = new Map();
    for (const x of daFare) if (x.resto > 0) mancano.set(x.v.id, { v: x.v, min: (mancano.get(x.v.id)?.min || 0) + x.resto });
    return { giorni, mancano: [...mancano.values()], senzaFasce: !Object.values(fasce).some(l => l && l.length), daFare,
      cancellati: validi.filter(x => x.sp.stato === 'cancellato'),
      daRimandare: validi.filter(x => x.sp.stato === 'rimandato' && !x.sp.a) };
  }

  window.SC = { avvia, db, esci, piano, utente: () => sessione && sessione.user };
})();
