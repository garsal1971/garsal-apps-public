# Migrazioni in attesa di conferma

Migration già scritte e collaudate, **tenute fuori da `supabase/migrations/` di
proposito**: lì dentro un file viene applicato al primo push su `claude/**`
(`deploy.yml` fa partire `supabase db push` appena vede un file cambiato sotto
quel percorso). Qui invece il push non fa partire niente.

Serve per il caso in cui il file è pronto ma un dato che ci finisce dentro è
ancora da confermare — una data, un importo, il nome di un conto. Il lavoro sta
al sicuro sul remoto senza scrivere niente nella contabilità.

Quando i dati sono confermati: si correggono i valori e si sposta il file in
`supabase/migrations/`. È lo spostamento a renderlo attivo.
