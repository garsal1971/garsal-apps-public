package com.garsal.sos

import org.json.JSONArray
import org.json.JSONObject

/** Una risposta alla domanda «com'è andata?». */
data class SosOutcome(
    val id: String,
    val label: String,
    val emoji: String,
    val points: Int,
    val timeDeltaPct: Double
) {
    /** "−500 punti · +10% tempo" — quello che il bottone dice prima di essere premuto:
        la scelta si fa sapendo quanto costa, non scoprendolo dopo. */
    fun sottotitolo(): String {
        val p = when {
            points > 0 -> "+$points punti"
            points < 0 -> "−${-points} punti"
            else       -> "nessun punto"
        }
        val t = when {
            timeDeltaPct > 0 -> " · +${pct(timeDeltaPct)}% tempo"
            timeDeltaPct < 0 -> " · −${pct(-timeDeltaPct)}% tempo"
            else             -> " · tempo invariato"
        }
        return p + t
    }

    private fun pct(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
}

/** Un SOS: una pagina dello swipe. */
data class SosType(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val color: String,
    /** durata del prossimo giro, già decisa dal server sugli esiti precedenti */
    val seconds: Int,
    val pointsTotal: Int,
    val roundsTotal: Int,
    val outcomes: List<SosOutcome>,
    val messages: List<String>
)

object Model {

    fun parseTypes(root: JSONObject): List<SosType> {
        val arr = root.optJSONArray("types") ?: JSONArray()
        val out = mutableListOf<SosType>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            out.add(
                SosType(
                    id          = t.optString("id"),
                    name        = t.optString("name", "SOS"),
                    description = t.optString("description", ""),
                    emoji       = t.optString("emoji", "🆘"),
                    color       = t.optString("color", "#EE334E"),
                    seconds     = t.optInt("seconds", 600),
                    pointsTotal = t.optInt("points_total", 0),
                    roundsTotal = t.optInt("rounds_total", 0),
                    outcomes    = parseOutcomes(t.optJSONArray("outcomes")),
                    messages    = parseMessages(t.optJSONArray("messages"))
                )
            )
        }
        return out
    }

    private fun parseOutcomes(arr: JSONArray?): List<SosOutcome> {
        val out = mutableListOf<SosOutcome>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val o = arr!!.optJSONObject(i) ?: continue
            out.add(
                SosOutcome(
                    id           = o.optString("id"),
                    label        = o.optString("label", "?"),
                    emoji        = o.optString("emoji", ""),
                    points       = o.optInt("points", 0),
                    timeDeltaPct = o.optDouble("time_delta_pct", 0.0)
                )
            )
        }
        return out
    }

    private fun parseMessages(arr: JSONArray?): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until (arr?.length() ?: 0)) {
            val s = arr!!.optString(i, "")
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    /** 600 → "10:00", 3661 → "61:01". Le ore non servono: il tetto è mezz'ora. */
    fun mmss(seconds: Int): String {
        val s = if (seconds < 0) 0 else seconds
        return String.format("%02d:%02d", s / 60, s % 60)
    }

    /** "10 minuti" / "12 min 30 s" — per le scritte discorsive, non per il countdown. */
    fun durata(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0      -> "$s secondi"
            s == 0      -> "$m minut${if (m == 1) "o" else "i"}"
            else        -> "$m min $s s"
        }
    }
}
