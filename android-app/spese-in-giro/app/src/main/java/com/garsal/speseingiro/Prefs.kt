package com.garsal.speseingiro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Quello che questo telefono sa: l'elenco dei viaggi a cui è accoppiato, e
 * quale si sta guardando.
 *
 * ⚠️ Il token È la credenziale, e sta qui e in nessun altro posto: non c'è un
 * account da cui recuperarlo. Perso il telefono si rientra col **codice del
 * viaggio** (`vg_entra` con lo stesso nome), che è la ragione per cui il codice
 * si tiene scritto accanto al viaggio invece di buttarlo dopo l'accoppiamento.
 *
 * ⚠️ Nome del viaggio e nome delle persone si tengono in locale perché la
 * schermata dei viaggi si deve poter aprire **senza rete**: dire «viaggio
 * 7f3a-…» a chi non ha campo è come non dire niente.
 */
class Prefs(ctx: Context) {

    private val p = ctx.getSharedPreferences("spese_in_giro", Context.MODE_PRIVATE)

    data class Viaggio(
        val token: String,
        val viaggioId: String,
        val nome: String,
        val codice: String,
        val io: String,
    )

    var tokenAttivo: String?
        get() = p.getString("token_attivo", null)
        set(v) = p.edit().putString("token_attivo", v).apply()

    fun viaggi(): List<Viaggio> {
        val raw = p.getString("viaggi", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Viaggio(
                token = o.optString("token"),
                viaggioId = o.optString("viaggio_id"),
                nome = o.optString("nome"),
                codice = o.optString("codice"),
                io = o.optString("io"),
            )
        }
    }

    /** Aggiunge o aggiorna: la chiave è il **viaggio**, non il token — rientrando
     *  col codice il token è nuovo, ma il viaggio è lo stesso e restare in elenco
     *  due volte lo farebbe scegliere a caso. */
    fun salva(v: Viaggio) {
        val altri = viaggi().filter { it.viaggioId != v.viaggioId }
        scrivi(altri + v)
        tokenAttivo = v.token
    }

    fun aggiornaNome(viaggioId: String, nome: String, codice: String, io: String) {
        val nuovi = viaggi().map {
            if (it.viaggioId == viaggioId) it.copy(nome = nome, codice = codice, io = io) else it
        }
        scrivi(nuovi)
    }

    fun dimentica(viaggioId: String) {
        val restanti = viaggi().filter { it.viaggioId != viaggioId }
        scrivi(restanti)
        if (viaggi().none { it.token == tokenAttivo }) tokenAttivo = restanti.lastOrNull()?.token
    }

    fun attivo(): Viaggio? = viaggi().firstOrNull { it.token == tokenAttivo } ?: viaggi().lastOrNull()

    private fun scrivi(lista: List<Viaggio>) {
        val arr = JSONArray()
        lista.forEach {
            arr.put(JSONObject().apply {
                put("token", it.token); put("viaggio_id", it.viaggioId)
                put("nome", it.nome); put("codice", it.codice); put("io", it.io)
            })
        }
        p.edit().putString("viaggi", arr.toString()).apply()
    }
}
