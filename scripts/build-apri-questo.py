#!/usr/bin/env python3
"""Costruisce APRI-QUESTO.html incorporando OpenPGP.js dentro la pagina.

⚠️ La libreria va INCORPORATA e non collegata: questo file viaggia da solo — su una
chiavetta, dentro un export, allegato a un'email — e deve aprire i .gpg su un computer
staccato dalla rete. Un `<script src>` lo renderebbe inservibile proprio nel giorno in
cui serve.

Uso:  python3 scripts/build-apri-questo.py
"""
import pathlib, sys

radice = pathlib.Path(__file__).resolve().parent.parent
lib = (radice / 'vendor' / 'openpgp.min.js').read_text(encoding='utf-8')
tpl = (radice / 'scripts' / 'apri-questo.template.html').read_text(encoding='utf-8')

if '</script' in lib.lower():
    sys.exit('la libreria contiene «</script»: incorporarla romperebbe la pagina')
if '/*__OPENPGP__*/' not in tpl:
    sys.exit('il segnaposto /*__OPENPGP__*/ non c\'è più nel template')

out = tpl.replace('/*__OPENPGP__*/', lib)
(radice / 'APRI-QUESTO.html').write_text(out, encoding='utf-8')
print(f'APRI-QUESTO.html scritto: {len(out)//1024} kB')
