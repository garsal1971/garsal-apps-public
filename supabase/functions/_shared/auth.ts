// Il controllo "c'è un utente loggato" per le Edge Functions con verify_jwt = false.
//
// La chiave pubblica anon NON passa: /auth/v1/user risponde solo a un JWT di un utente vero.
// Senza questo controllo una funzione con verify_jwt = false è un accesso libero alle chiavi
// che usa (IA comprese) per chiunque ne conosca l'indirizzo.
export async function utenteLoggato(req: Request): Promise<boolean> {
  const header = req.headers.get('Authorization') ?? '';
  if (!header.toLowerCase().startsWith('bearer ')) return false;
  try {
    const r = await fetch(`${Deno.env.get('SUPABASE_URL')}/auth/v1/user`, {
      headers: { Authorization: header, apikey: Deno.env.get('SUPABASE_ANON_KEY') ?? '' },
    });
    if (!r.ok) return false;
    const u = await r.json();
    return !!u?.id;
  } catch { return false; }
}
