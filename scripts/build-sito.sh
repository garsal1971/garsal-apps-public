#!/usr/bin/env bash
#
# Prepara la cartella che Cloudflare Pages pubblica.
#
# ⚠️ Serve a UNA cosa sola: tenere le APK fuori dall'upload. Pages rifiuta i
# file oltre i 25 MiB, e `GarsalApps-latest.apk` (57 MB) e
# `AppSphereNative-latest.apk` (45 MB) ci stanno sopra — ma il deploy non
# fallisce solo su quei due: **fallisce tutto**, e il sito non esce affatto.
#
# ⚠️ Si escludono TUTTE le APK e non le sole due grosse: le APK si scaricano
# da `apk.garsal.men` (R2), quindi sul sito non ci vanno comunque, e una
# regola «le due grosse» aspetterebbe solo il giorno in cui una terza passa i
# 25 MiB — di nuovo con l'intero sito che non si pubblica.
#
# ⚠️ Le schede `-latest.json` restano invece nella cartella `releases/` del
# sito: sono qualche centinaio di byte, le legge il pannello di comandi.html e
# ognuno dei quattro `Aggiornamento.kt`, e `_headers` gli dà già il no-store
# che serve perché non raccontino la build di ieri.
#
# ⚠️ Fuori anche tutto quel che NON è una pagina: sorgenti Android, Supabase,
# workflow, script, documentazione (CLAUDE.md), migration. Il sito pubblica le
# pagine e le loro risorse, non la repo: un file che sta qui e non serve a una
# pagina è un file che chiunque può scaricare da garsal.men.
#
# Su Cloudflare Pages: build command `bash scripts/build-sito.sh`,
# build output directory `dist`.

set -euo pipefail

rm -rf dist
mkdir -p dist

# tar invece di cp/rsync: `cp -r` non sa escludere e rsync non è garantito
# sull'immagine di build. Le esclusioni sono relative a `.`, com'è scritto
# l'archivio.
tar -cf - \
    --exclude=./.git \
    --exclude=./dist \
    --exclude='*.apk' \
    --exclude=./.github \
    --exclude=./android-app \
    --exclude=./supabase \
    --exclude=./scripts \
    --exclude=./tests \
    --exclude=./migrazioni-in-attesa \
    --exclude=./netlify \
    --exclude='*.md' \
    --exclude='*.sql' \
    --exclude='*.ps1' \
    --exclude='*.sh' \
    --exclude='*.py' \
    --exclude='*.bat' \
    . | tar -xf - -C dist

echo "Pubblico $(find dist -type f | wc -l) file, $(du -sh dist | cut -f1)"
echo "APK escluse (stanno su apk.garsal.men):"
find . -name '*.apk' -not -path './.git/*' -not -path './dist/*' -printf '  %f — %s byte\n' | sort
