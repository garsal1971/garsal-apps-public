/* offusca-importi.js v1.0.0 — il FAB $ che sfoca gli importi in €
   ═══════════════════════════════════════════════════════════════════════════
   Lo caricano finanza.html e le pagine che Finanza richiama (Spese Familiari,
   Contribuzione, Conto Risparmio, Spese Ada, Spese Personali, Casa Rosa), più
   situazione-teresa.html e situazione-rosa.html.
   ⚠️ È una deroga dichiarata a «un'app = un file HTML», come spese-famiglia.js:
   sette copie dello stesso blocco sarebbero sette comportamenti diversi il giorno
   che una cambia. Si carica in fondo al <body>, dopo gli script della pagina.

   Il FAB in basso a destra sfoca saldi, valori, P&L, assi e fumetti; percentuali,
   quantità e date restano leggibili. $ = importi in chiaro, $ barrato = nascosti
   (🙈 è già della modalità nascosta). La scelta è UNA per tutte queste pagine e
   resta su questo dispositivo (localStorage `fnz_offusca`); l'evento `storage`
   la porta anche nelle altre schede e nell'iframe del Conto Risparmio aperto
   dentro Finanza. Nell'iframe (`?embed=1`) il FAB non si disegna: c'è già quello
   di Finanza attorno.

   ⚠️ I formattatori delle pagine NON si toccano: finiscono anche in attributi,
   CSV, PDF e negli appunti, dove uno <span> sarebbe un difetto. Si lavora sul DOM
   già disegnato: un MutationObserver avvolge in `.ofs` i pezzi di testo che sono
   un importo in €, e la classe `offusca` sul body decide se sfocarli. Spento il
   FAB gli span restano e non fanno niente.
   ⚠️ I grafici Chart.js sono su canvas, dove un testo non si prende da solo: a
   modalità accesa il canvas si sfoca per intero. Le caselle dei form restano
   leggibili — lì si sta scrivendo.
   ⚠️ Gemello nell'APK nativa: il FAB $ di finanza/FinanzaScreen.kt. */
(function offuscaImporti() {
  'use strict';
  const CHIAVE = 'fnz_offusca';
  // Solo escape \u, niente € scritto: se il file arrivasse letto in un'altra codifica,
  // un € letterale diventerebbe «â‚¬» e non riconoscerebbe più niente.
  const EURO = '\u20AC';
  const IMPORTO = /[+\-\u2212]?\d[\d.,]*\s?\u20AC|\u20AC\s?[+\-\u2212]?\d[\d.,]*/g;
  const SALTA = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'INPUT', 'SELECT', 'OPTION', 'BUTTON']);
  const EMBED = new URLSearchParams(location.search).has('embed');
  const leggi = () => { try { return localStorage.getItem(CHIAVE) === '1'; } catch (_) { return false; } };
  let attivo = leggi();

  const stile = document.createElement('style');
  stile.textContent = `
    #fab-offusca { position: fixed; right: 18px; bottom: 18px; z-index: 450; min-width: 52px; min-height: 52px; border-radius: 50%; border: none; background: #0081C8; color: #fff; line-height: 1; box-shadow: 0 4px 14px rgba(0,0,0,.25); cursor: pointer; display: flex; align-items: center; justify-content: center; padding: 0; }
    body.offusca #fab-offusca { background: #374151; }
    body.offusca .ofs { filter: blur(6px); user-select: none; }
    body.offusca svg text.ofs { filter: blur(3px); }
    body.offusca canvas { filter: blur(6px); }`;
  document.head.appendChild(stile);

  function avvolgi(nodo) {
    const testo = nodo.nodeValue;
    if (!testo || testo.indexOf(EURO) < 0) return;
    const padre = nodo.parentNode;
    if (!padre || SALTA.has(padre.nodeName) || padre.closest?.('.ofs, #fab-offusca')) return;
    IMPORTO.lastIndex = 0;
    if (!IMPORTO.test(testo)) return;
    // Dentro un SVG uno <span> non si disegna: si marca il <text> intero.
    if (padre.namespaceURI === 'http://www.w3.org/2000/svg') { padre.classList.add('ofs'); return; }
    const frag = document.createDocumentFragment();
    let ultimo = 0;
    // ⚠️ matchAll riparte dal lastIndex lasciato da test(): senza azzerarlo salterebbe l'importo.
    IMPORTO.lastIndex = 0;
    for (const m of testo.matchAll(IMPORTO)) {
      if (m.index > ultimo) frag.appendChild(document.createTextNode(testo.slice(ultimo, m.index)));
      const sp = document.createElement('span');
      sp.className = 'ofs';
      sp.textContent = m[0];
      frag.appendChild(sp);
      ultimo = m.index + m[0].length;
    }
    if (ultimo < testo.length) frag.appendChild(document.createTextNode(testo.slice(ultimo)));
    padre.replaceChild(frag, nodo);
  }

  function scorri(radice) {
    if (radice.nodeType === Node.TEXT_NODE) { avvolgi(radice); return; }
    if (radice.nodeType !== Node.ELEMENT_NODE || SALTA.has(radice.nodeName)) return;
    const w = document.createTreeWalker(radice, NodeFilter.SHOW_TEXT);
    const nodi = [];
    while (w.nextNode()) nodi.push(w.currentNode);
    nodi.forEach(avvolgi);
  }

  const osservatore = new MutationObserver(lista => {
    for (const m of lista) {
      if (m.type === 'characterData') avvolgi(m.target);
      else m.addedNodes.forEach(scorri);
    }
  });

  function applica() {
    document.body.classList.toggle('offusca', attivo);
    const b = document.getElementById('fab-offusca');
    if (b) {
      b.title = attivo ? 'Mostra gli importi' : 'Nascondi gli importi';
      b.setAttribute('aria-label', b.title);
      b.innerHTML = `<svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        stroke-width="2.2" stroke-linecap="round" aria-hidden="true"><path d="M12 2v20"/>
        <path d="M17 6.5c-.8-1.3-2.6-2-5-2-2.8 0-4.5 1.3-4.5 3.2 0 4.6 9.5 2.4 9.5 7.3 0 2-1.9 3.5-5 3.5-2.6 0-4.4-.9-5.3-2.4"/>
        ${attivo ? '<path d="M3.5 3.5l17 17" stroke="#FCA5A5" stroke-width="2.6"/>' : ''}</svg>`;
    }
    if (attivo) {
      scorri(document.body);
      osservatore.observe(document.body, { childList: true, subtree: true, characterData: true });
    } else {
      osservatore.disconnect();
    }
  }

  function avvia() {
    if (!EMBED) {
      const b = document.createElement('button');
      b.id = 'fab-offusca';
      b.type = 'button';
      b.addEventListener('click', () => {
        attivo = !attivo;
        try { localStorage.setItem(CHIAVE, attivo ? '1' : '0'); } catch (_) {}
        applica();
      });
      document.body.appendChild(b);
    }
    applica();
  }
  // Un'altra scheda, o Finanza attorno all'iframe, ha cambiato la scelta.
  window.addEventListener('storage', e => {
    if (e.key !== CHIAVE) return;
    attivo = leggi();
    applica();
  });
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', avvia);
  else avvia();
})();
