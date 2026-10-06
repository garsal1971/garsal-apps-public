#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
# drive-refresh-token.py — ricava il refresh token di Google Drive, una volta
# ═══════════════════════════════════════════════════════════════════════════
#
# Si esegue **sul proprio PC**, una volta sola, e stampa il `refresh_token` da
# mettere nei Secrets. Non lo scrive da nessuna parte e non lo manda a nessuno:
# il giro OAuth avviene fra il tuo browser e Google, e la risposta torna su
# `127.0.0.1`.
#
#   python3 scripts/drive-refresh-token.py
#
# ⚠️ PERCHÉ SERVE UN GIRO COL BROWSER. Il refresh token si ottiene solo da un
# consenso dato da una persona: è quello che distingue «l'app agisce per conto
# di Salvatore» da «l'app agisce per conto proprio», ed è esattamente il motivo
# per cui non si usa un account di servizio (che su Drive non ha spazio suo).
#
# ⚠️ `access_type=offline` **e** `prompt=consent`, tutt'e due. Senza il primo
# Google non manda nessun refresh token; senza il secondo non lo manda **dalla
# seconda volta in poi**, perché il consenso lo ricorda — e si finisce a
# guardare una risposta che sembra riuscita e non ha il campo che serve.
#
# ⚠️ Il redirect è **loopback su una porta a caso** (`http://127.0.0.1:<porta>`)
# e non va registrato in console: per i client di tipo *Applicazione desktop*
# Google accetta qualunque porta locale. Il vecchio `urn:ietf:wg:oauth:2.0:oob`
# — quello che faceva incollare il codice a mano — è dismesso e risponde 400.
#
# ⚠️ Lo scope è `drive.file` e basta: dà accesso ai **soli file creati da questo
# client**, cioè i backup. Con `drive.readonly` si darebbe a un workflow di
# GitHub la lettura dell'intero Drive per arrivare a una cartella sola, e in
# più è uno scope «riservato», che vuole la verifica di Google.

import getpass
import http.server
import json
import secrets
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

AUTORIZZA = 'https://accounts.google.com/o/oauth2/v2/auth'
TOKEN     = 'https://oauth2.googleapis.com/token'
SCOPE     = 'https://www.googleapis.com/auth/drive.file'

ricevuto = {}


class Ponte(http.server.BaseHTTPRequestHandler):
    """Riceve il redirect di Google e chiude il giro."""

    def do_GET(self):
        q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
        ricevuto.update({k: v[0] for k, v in q.items()})
        ok = 'code' in ricevuto
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.end_headers()
        self.wfile.write((
            '<!doctype html><meta charset="utf-8">'
            '<body style="font:16px system-ui;padding:2rem;max-width:34rem">'
            + ('<h2>✔ Fatto</h2><p>Puoi chiudere questa pagina e tornare al terminale.</p>'
               if ok else
               f'<h2>✕ Non ha funzionato</h2><p>{ricevuto.get("error", "risposta senza codice")}</p>')
            + '</body>'
        ).encode())

    def log_message(self, *_):
        pass   # il server vive tre secondi: i suoi log sono solo rumore


def porta_libera():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]


def chiedi_token(dati):
    req = urllib.request.Request(
        TOKEN,
        data=urllib.parse.urlencode(dati).encode(),
        headers={'Content-Type': 'application/x-www-form-urlencoded'},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        # Il corpo dell'errore di Google dice quale campo non va: senza, resta
        # un 400 muto e si tira a indovinare.
        raise SystemExit(f'\n✕ Google ha risposto {e.code}:\n{e.read().decode("utf-8", "replace")}')


def main():
    print('── Refresh token per Google Drive ──────────────────────────────\n')
    print('Servono i due valori del client OAuth di tipo «Applicazione desktop».')
    print('Restano su questo computer: non vengono scritti né spediti a nessuno.\n')

    client_id = input('GDRIVE_CLIENT_ID     : ').strip()
    # getpass non lo mostra a schermo: un segreto non deve restare nella
    # cronologia del terminale né sopra la spalla di chi guarda.
    client_secret = getpass.getpass('GDRIVE_CLIENT_SECRET : ').strip()
    if not client_id or not client_secret:
        raise SystemExit('✕ Servono tutt\'e due.')

    porta = porta_libera()
    redirect = f'http://127.0.0.1:{porta}'
    # Lo `state` non serve a Google: serve a noi, per accorgerci se la risposta
    # che arriva sulla porta non è quella che abbiamo chiesto.
    stato = secrets.token_urlsafe(16)

    url = AUTORIZZA + '?' + urllib.parse.urlencode({
        'client_id': client_id,
        'redirect_uri': redirect,
        'response_type': 'code',
        'scope': SCOPE,
        'access_type': 'offline',
        'prompt': 'consent',
        'state': stato,
    })

    print('\n▶ Apro il browser. Accedi con garsal1971@gmail.com e dai il consenso.')
    print('  Se compare «App non verificata»: Avanzate → Vai a … (non sicuro).')
    print('  È la tua app, e stai dando accesso a te stesso.\n')
    print(f'  Se il browser non si apre da sé, incolla questo indirizzo:\n\n{url}\n')

    webbrowser.open(url)

    server = http.server.HTTPServer(('127.0.0.1', porta), Ponte)
    server.timeout = 300
    while 'code' not in ricevuto and 'error' not in ricevuto:
        server.handle_request()

    if 'error' in ricevuto:
        raise SystemExit(f'\n✕ Consenso negato: {ricevuto["error"]}')
    if ricevuto.get('state') != stato:
        raise SystemExit('\n✕ La risposta non corrisponde alla richiesta: rifai il giro.')

    print('▶ Scambio il codice…')
    r = chiedi_token({
        'code': ricevuto['code'],
        'client_id': client_id,
        'client_secret': client_secret,
        'redirect_uri': redirect,
        'grant_type': 'authorization_code',
    })

    refresh = r.get('refresh_token')
    if not refresh:
        raise SystemExit(
            '\n✕ Google non ha mandato nessun refresh_token.\n'
            '  Succede quando il consenso era già stato dato: revocalo da\n'
            '  https://myaccount.google.com/permissions e rifai il giro.'
        )

    # Il token si prova subito: scoprire fra una settimana che non funziona,
    # da un workflow notturno, è il modo peggiore di scoprirlo.
    print('▶ Provo che funzioni…')
    p = chiedi_token({
        'client_id': client_id,
        'client_secret': client_secret,
        'refresh_token': refresh,
        'grant_type': 'refresh_token',
    })
    if 'access_token' not in p:
        raise SystemExit('\n✕ Il refresh token non è stato accettato alla prova.')

    print('\n' + '═' * 66)
    print('GDRIVE_REFRESH_TOKEN')
    print('═' * 66)
    print(refresh)
    print('═' * 66)
    print("""
Va messo — insieme a CLIENT_ID, CLIENT_SECRET e FOLDER_ID — in DUE posti:

  • GitHub   → Settings → Secrets and variables → Actions
  • Supabase → Project Settings → Edge Functions → Secrets

⚠️ Gli stessi valori in tutt'e due: il workflow carica su Drive e la Edge
   Function legge da lì. Se divergono, i backup si scrivono in una cartella
   e relazione.html ne guarda un'altra — e l'elenco torna vuoto senza che
   niente dica perché.

GDRIVE_FOLDER_ID è la parte finale dell'indirizzo della cartella su Drive:
  drive.google.com/drive/folders/[QUESTO]

Su Drive la suite sta tutta in una cartella sola:
  AppSphere/
  ├─ backups/   ← GDRIVE_FOLDER_ID
  └─ rooms/     ← il Forziere, che la sua cartella se la cerca per nome
L'id di «AppSphere» va nel solo Supabase, come GDRIVE_APPSPHERE_FOLDER_ID
(facoltativo): dice a forziere-drive dove creare «rooms» se un giorno non
ci fosse.
""")


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        sys.exit('\nAnnullato.')
