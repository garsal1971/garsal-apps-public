#!/usr/bin/env python3
# ═══════════════════════════════════════════════════════════════════════════
# backup-drive.py — porta dump e relazione su Google Drive, e fa la rotazione
# ═══════════════════════════════════════════════════════════════════════════
#
# ⚠️ PERCHÉ NON NEL REPOSITORY. `garsal1971/garsal-apps` è **pubblico**, e
# `netlify.toml` pubblica la radice (`publish = "."`): un file su master è
# scaricabile da chiunque ne conosca l'indirizzo, e un ramo a parte è comunque
# leggibile da chiunque apra la pagina del repo. Patrimonio, spese, task e
# reddito non ci vanno — né su master né su un ramo.
#
# ⚠️ PERCHÉ UN REFRESH TOKEN E NON UN ACCOUNT DI SERVIZIO. Un service account
# non ha spazio proprio su Drive: caricando in una cartella condivisa da un
# account Google personale il file resterebbe di sua proprietà e la richiesta
# fallisce con `storageQuotaExceeded`. Funziona solo su un Drive condiviso, che
# è roba di Workspace. Col refresh token dell'account personale i file nascono
# **suoi**, nel suo spazio, e si vedono da Drive come tutti gli altri. È la
# stessa scelta già fatta per `YT_OAUTH_TOKEN`.
#
# ⚠️ I NOMI SONO PIATTI, NON UNA CARTELLA PER DATA: `2026-09-06-relazione.html`.
# Un albero di cartelle costerebbe una chiamata in più per ogni giro e una
# ricerca per ogni lettura; col prefisso la data si legge dal nome, l'elenco è
# già ordinato, e la rotazione è un confronto di stringhe.
#
# Uso:
#   python3 scripts/backup-drive.py 2026-09-06 /tmp/dump/*.gz /tmp/dump/relazione.html
#
# Variabili d'ambiente: GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET,
# GDRIVE_REFRESH_TOKEN, GDRIVE_FOLDER_ID, e QUANTE_TENERE (opzionale).

import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TOKEN_URL = 'https://oauth2.googleapis.com/token'
DRIVE     = 'https://www.googleapis.com/drive/v3'
UPLOAD    = 'https://www.googleapis.com/upload/drive/v3/files'


def chiedi(url, dati=None, intestazioni=None, metodo=None):
    req = urllib.request.Request(url, data=dati, headers=intestazioni or {}, method=metodo)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            corpo = r.read()
            return json.loads(corpo) if corpo else {}
    except urllib.error.HTTPError as e:
        # Il corpo dell'errore di Google dice quale campo non va: senza, resta
        # un 400 muto e si tira a indovinare.
        raise SystemExit(f'Drive {url}: HTTP {e.code} — {e.read().decode("utf-8", "replace")[:500]}')


def accesso():
    for nome in ('GDRIVE_CLIENT_ID', 'GDRIVE_CLIENT_SECRET', 'GDRIVE_REFRESH_TOKEN', 'GDRIVE_FOLDER_ID'):
        if not os.environ.get(nome):
            raise SystemExit(f'backup-drive: manca il segreto {nome}')
    corpo = urllib.parse.urlencode({
        'client_id':     os.environ['GDRIVE_CLIENT_ID'],
        'client_secret': os.environ['GDRIVE_CLIENT_SECRET'],
        'refresh_token': os.environ['GDRIVE_REFRESH_TOKEN'],
        'grant_type':    'refresh_token',
    }).encode()
    r = chiedi(TOKEN_URL, corpo, {'Content-Type': 'application/x-www-form-urlencoded'})
    if 'access_token' not in r:
        raise SystemExit('backup-drive: il refresh token non è stato accettato')
    return r['access_token']


def elenco(token, cartella):
    fuori, pagina = [], None
    while True:
        q = {
            'q': f"'{cartella}' in parents and trashed = false",
            'fields': 'nextPageToken, files(id,name,size,modifiedTime)',
            'orderBy': 'name',
            'pageSize': '200',
        }
        if pagina:
            q['pageToken'] = pagina
        r = chiedi(f'{DRIVE}/files?{urllib.parse.urlencode(q)}', intestazioni={'Authorization': f'Bearer {token}'})
        fuori.extend(r.get('files', []))
        pagina = r.get('nextPageToken')
        if not pagina:
            return fuori


def carica(token, cartella, percorso, nome):
    tipo = mimetypes.guess_type(nome)[0] or 'application/octet-stream'
    meta = json.dumps({'name': nome, 'parents': [cartella]}).encode()
    dati = open(percorso, 'rb').read()
    confine = '===garsal-backup==='
    corpo = b''.join([
        f'--{confine}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n'.encode(), meta,
        f'\r\n--{confine}\r\nContent-Type: {tipo}\r\n\r\n'.encode(), dati,
        f'\r\n--{confine}--\r\n'.encode(),
    ])
    r = chiedi(f'{UPLOAD}?uploadType=multipart&fields=id,name,size',
               corpo,
               {'Authorization': f'Bearer {token}', 'Content-Type': f'multipart/related; boundary={confine}'})
    return r


def main():
    if len(sys.argv) < 3:
        raise SystemExit('uso: backup-drive.py <YYYY-MM-DD> <file...>')
    giorno, file = sys.argv[1], sys.argv[2:]
    cartella = os.environ['GDRIVE_FOLDER_ID'] if os.environ.get('GDRIVE_FOLDER_ID') else None
    token = accesso()

    caricati = {}
    for p in file:
        if not os.path.isfile(p):
            continue
        nome = f'{giorno}-{os.path.basename(p)}'
        r = carica(token, cartella, p, nome)
        caricati[nome] = r.get('id')
        print(f'  ↑ {nome} ({int(r.get("size", 0)) // 1024} KB)')
    if not caricati:
        raise SystemExit('backup-drive: nessun file da caricare')

    # Rotazione: si tengono le ultime N date. ⚠️ Si contano le **date**, non i
    # file: un giro che ha prodotto tre file e uno che ne ha prodotto uno solo
    # devono valere una fotografia per uno, o un backup a metà ne farebbe
    # sparire uno intero.
    quante = int(os.environ.get('QUANTE_TENERE', '12'))
    tutti = elenco(token, cartella)
    date = sorted({f['name'][:10] for f in tutti if len(f['name']) >= 10 and f['name'][4] == '-'}, reverse=True)
    da_buttare = set(date[quante:])
    # ⚠️ Drive ammette due file con lo stesso nome, quindi rilanciare il backup
    # nello stesso giorno lascerebbe DUE «2026-09-04-relazione.html» e la pagina
    # ne elencherebbe due sotto la stessa data, indistinguibili. La copia
    # vecchia si toglie **dopo** che la nuova è salita: al contrario, un
    # caricamento fallito porterebbe via anche quella che c'era.
    doppioni = {f['id'] for f in tutti
                if f['name'] in caricati and f['id'] != caricati[f['name']]}

    for f in tutti:
        if f['name'][:10] in da_buttare or f['id'] in doppioni:
            chiedi(f'{DRIVE}/files/{f["id"]}', intestazioni={'Authorization': f'Bearer {token}'}, metodo='DELETE')
            print(f'  ✂ {f["name"]}')

    print(f'✔ {len(caricati)} file su Drive · {min(len(date), quante)} fotografie tenute')


if __name__ == '__main__':
    main()
