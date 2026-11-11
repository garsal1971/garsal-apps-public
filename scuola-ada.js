/* ============================================================================
   scuola-ada.js v1.0.0 — accesso e database per compiti-ada.html e
   verifiche-ada.html.

   ⚠️ Deroga dichiarata alla regola «un'app = un file HTML»: le due pagine
   condividono login e chiamate a Supabase, e due copie divergerebbero.

   Chi entra:
   • Salvatore col token di AppSphere (localStorage.sb_token, login Google);
   • un ospite (Ada) con email e password, se in `cm_guest_access` ha la
     pagina. La prima password si imposta col link «Imposta la password».

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
.sc-login .link{font:inherit;background:none;border:0;color:var(--accent);cursor:pointer;padding:4px 0;text-align:left;text-decoration:underline}
.sc-login .msg{font-size:14px;margin:0}
.sc-login .err{color:var(--danger)}
.sc-login .ok{color:var(--ok)}
.sc-utente{display:flex;flex-wrap:wrap;gap:8px;align-items:center;font-size:13px;color:var(--muted)}
.sc-utente button{font:inherit;font-size:13px;background:none;border:1px solid var(--line);color:var(--fg);border-radius:8px;padding:4px 10px;cursor:pointer}`;
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
    const u = document.getElementById('sc-utente');
    if (u) {
      u.innerHTML = `<span>${sessione.user.email}</span><button type="button" id="sc-esci">Esci</button>`;
      document.getElementById('sc-esci').addEventListener('click', () => esci());
    }
    if (quandoPronto) quandoPronto();
  }

  function esci(messaggio) {
    // ⚠️ `sb_token` non si tocca: è il login di AppSphere, e toglierlo da qui
    // farebbe uscire Salvatore da tutte le altre app.
    sessione = null; scrivi(null);
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

    // Salvatore: il token di AppSphere.
    let sbt = null; try { sbt = localStorage.getItem('sb_token'); } catch (e) {}
    if (sbt) {
      const user = await utenteDi(sbt);
      if (user && (user.email || '').toLowerCase() === OWNER) {
        sessione = { access_token: sbt, refresh_token: null, expires_at: null, user };
        await entra(); return;
      }
    }

    // Ospite: la sessione salvata.
    sessione = leggi();
    if (sessione && sessione.user) { await entra(); return; }
    sessione = null;
    mostraLogin(sbt ? 'Il login di AppSphere è scaduto: riapri la pagina da AppSphere.' : '', !!sbt);
  }

  window.SC = { avvia, db, esci, utente: () => sessione && sessione.user };
})();
