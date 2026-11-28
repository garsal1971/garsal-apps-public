#!/usr/bin/env bash
#
# Carica una o più APK sul bucket R2 che le serve, `apk.garsal.men`.
#
# ⚠️ Le APK NON stanno più sul sito: Cloudflare Pages rifiuta i file oltre i
# 25 MiB e il file troppo grosso non fallisce da solo — fallisce l'intero
# deploy (vedi `scripts/build-sito.sh`). Le schede `-latest.json` restano
# invece su garsal.men, che è dove ogni `Aggiornamento.kt` le va a leggere.
#
# Il nome sul bucket è il **nome del file e basta**, senza cartelle: gli APK
# già installati chiedono `https://apk.garsal.men/<Nome>-latest.apk`, e un
# prefisso qui vorrebbe dire un indirizzo diverso da quello che sanno.
#
# Vuole due variabili d'ambiente (segreti della repo):
#   CLOUDFLARE_API_TOKEN   token con «Workers R2 Storage: Edit»
#   CLOUDFLARE_ACCOUNT_ID  l'account su cui sta il bucket
#
#   bash scripts/apk-su-r2.sh /tmp/Sos-latest.apk
#
# ⚠️ Ogni APK sale DUE volte: `<Nome>-latest.apk` e `<Nome>-<versione>.apk`
# (es. `Sos-1.1.2.apk`). Il pulsante «Scarica» delle app punta alla copia col
# numero: il file nella cartella Download dice quale versione è, e un indirizzo
# nuovo non può servire dalla cache la build di prima. `-latest.apk` è
# TRANSITORIO: resta finché tutti i telefoni non hanno la versione che scarica
# il file col numero (e lo usano ancora le pagine situazione-rosa/teresa).
# ⚠️ La versione si legge DENTRO l'APK (aapt2 dell'Android SDK), non da una
# variabile del workflow: un nome che dicesse un'altra versione sarebbe peggio
# di nessun nome. Se non si legge, il caricamento FALLISCE: le app puntano a
# quell'indirizzo, e saltarlo in silenzio sarebbe un pulsante che dà 404.
# ⚠️ Si tengono le ultime TENERE (3) versioni per app; le più vecchie si
# cancellano. Quali siano lo dice la storia git di `releases/<Nome>-latest.apk`
# (fetch-depth: 0 nei workflow): R2 da wrangler non si elenca.

set -euo pipefail

BUCKET="${R2_BUCKET:-garsal-apk}"
TENERE=3

# aapt2 più recente dell'Android SDK del runner.
AAPT2=""
for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk; do
    [ -n "$d" ] && [ -d "$d/build-tools" ] || continue
    AAPT2=$(ls "$d"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)
    [ -n "$AAPT2" ] && break
done

# versionName dentro l'APK, o vuoto.
versione_di() {
    [ -n "$AAPT2" ] || return 0
    "$AAPT2" dump badging "$1" 2>/dev/null \
        | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1
}

carica() {   # carica <file> <nome sul bucket>
    npx --yes wrangler@4 r2 object put "$BUCKET/$2" \
        --file="$1" \
        --content-type=application/vnd.android.package-archive \
        --cache-control='no-cache' \
        --remote
}

# Versioni già pubblicate di releases/<nome>, dalla più recente, senza doppioni.
versioni_storiche() {
    local percorso="releases/$1" tmp c v
    tmp=$(mktemp)
    git log -n 12 --format=%H -- "$percorso" 2>/dev/null | while read -r c; do
        git show "$c:$percorso" > "$tmp" 2>/dev/null || continue
        v=$(versione_di "$tmp"); [ -n "$v" ] && echo "$v"
    done | awk '!visto[$0]++'
    rm -f "$tmp"
}

if [ $# -eq 0 ]; then
    echo "uso: apk-su-r2.sh <file.apk> [altri.apk …]" >&2
    exit 2
fi

: "${CLOUDFLARE_API_TOKEN:?manca CLOUDFLARE_API_TOKEN}"
: "${CLOUDFLARE_ACCOUNT_ID:?manca CLOUDFLARE_ACCOUNT_ID}"

for f in "$@"; do
    [ -f "$f" ] || { echo "❌ non esiste: $f" >&2; exit 1; }
    nome=$(basename "$f")

    # ⚠️ `--remote` non è facoltativo: senza, wrangler scrive nella copia
    # locale simulata e il comando **riesce** senza che sul bucket vero
    # arrivi niente — cioè un caricamento che non si vede fallire.
    # ⚠️ `--cache-control no-cache` non è prudenza generica, è il difetto del
    # 13 settembre 2026: il nome sul bucket non cambia mai
    # (`Modifiche-latest.apk`), quindi senza questo header Cloudflare tiene
    # l'oggetto della build precedente e lo serve a chi scarica — una build
    # nuova resta invisibile dietro un indirizzo identico, e dal telefono si
    # vede come «ho aggiornato e scende sempre la stessa versione». Successo
    # davvero: la v1.0.1 di Modifiche era su R2 e il telefono scaricava la
    # v1.0.0.
    # ⚠️ Non spegne la cache: obbliga a **rivalidare**, quindi appena l'ETag
    # cambia scende il pacchetto nuovo e per il resto la banda si risparmia
    # com'è giusto. Il `?v=` che i client mettono in coda resta la seconda
    # rete — quella funziona solo se chi scarica passa dal pulsante.
    carica "$f" "$nome"
    echo "↑ $nome — $(stat -c%s "$f") byte → https://apk.garsal.men/$nome"

    # La copia col numero, solo per i file `-latest.apk`.
    case "$nome" in *-latest.apk) ;; *) continue ;; esac
    base="${nome%-latest.apk}"
    ver=$(versione_di "$f")
    if [ -z "$ver" ]; then
        echo "❌ non leggo la versione dentro $nome (aapt2: ${AAPT2:-assente})" >&2
        exit 1
    fi
    carica "$f" "$base-$ver.apk"
    echo "↑ $base-$ver.apk → https://apk.garsal.men/$base-$ver.apk"

    # Rotazione: tiene la versione appena caricata più le ultime pubblicate,
    # TENERE in tutto, e cancella le altre. Un errore qui non ferma la build:
    # una copia vecchia rimasta costa spazio, non rompe niente.
    { echo "$ver"; versioni_storiche "$nome"; } | awk '!visto[$0]++' \
        | tail -n +$((TENERE + 1)) | while read -r vecchia; do
            if npx --yes wrangler@4 r2 object delete "$BUCKET/$base-$vecchia.apk" --remote >/dev/null 2>&1; then
                echo "🗑 $base-$vecchia.apk"
            else
                echo "· $base-$vecchia.apk: niente da togliere"
            fi
        done
done
