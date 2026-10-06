package com.garsal.appsphere.spuntiamola

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// ── Righe del database ────────────────────────────────────────────────────
// Gli stessi campi che scrive spuntiamola.html: le due implementazioni si
// alternano sulle stesse righe, quindi i nomi non si toccano.

@Serializable
data class SpSettings(
    @SerialName("user_id") val userId: String? = null,
    val goal: String = "Il traguardo",
    val emoji: String = "🎯",
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("skip_weekend") val skipWeekend: Boolean = false,
    // 'attesa' | 'bei_giorni' — con che voce parla l'app. Le stecche aperte
    // prima della colonna non ce l'hanno: il default è quel che erano.
    val mood: String = MOOD_ATTESA,
)

@Serializable
data class SpCheck(
    @SerialName("user_id") val userId: String? = null,
    val day: String,
    val emoji: String = "✅",
)

@Serializable
data class SpKeyDay(
    @SerialName("user_id") val userId: String? = null,
    val day: String,
    val label: String = "",
)

@Serializable
data class SpStecca(
    /** Serve per cancellarla dall'archivio; null se la riga non l'ha restituito. */
    val id: String? = null,
    val goal: String = "",
    val emoji: String = "🎯",
    val mood: String = MOOD_ATTESA,
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    @SerialName("skip_weekend") val skipWeekend: Boolean = false,
    @SerialName("total_days") val totalDays: Int = 0,
    @SerialName("done_days") val doneDays: Int = 0,
    @SerialName("final_emoji") val finalEmoji: String = "🏁",
    @SerialName("final_check_at") val finalCheckAt: String? = null,
    val satisfaction: Int = 50,
    val note: String = "",
    val checks: JsonObject? = null,
    @SerialName("key_days") val keyDays: JsonObject? = null,
    @SerialName("closed_at") val closedAt: String? = null,
)

/** La riga da inserire alla chiusura: `user_id` esplicito come fa il web. */
@Serializable
data class SpSteccaNuova(
    @SerialName("user_id") val userId: String,
    val goal: String,
    val emoji: String,
    val mood: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("skip_weekend") val skipWeekend: Boolean,
    @SerialName("total_days") val totalDays: Int,
    @SerialName("done_days") val doneDays: Int,
    @SerialName("final_emoji") val finalEmoji: String,
    @SerialName("final_check_at") val finalCheckAt: String?,
    val satisfaction: Int,
    val note: String,
    val checks: JsonObject,
    @SerialName("key_days") val keyDays: JsonObject,
)

// ── L'umore della stecca: due voci per la stessa griglia ─────────────────
//
// Lo stesso conto alla rovescia si fa in due situazioni opposte. Si aspetta
// qualcosa: il tempo che passa è impazienza, e ogni giorno spuntato è un
// ostacolo tolto di mezzo. Oppure ci si sta dentro — una vacanza, una visita,
// un periodo che finirà — e allora il tempo che passa non avvicina niente,
// porta via: l'ultimo giorno non è un arrivo, è un addio.
//
// Griglia, spunte, traguardi e chiusura restano identici: cambia con che voce
// l'app li commenta. ⚠️ È la copia riga per riga di `MOODS` in
// spuntiamola.html — **se cambia là va cambiato anche qui**, o le due
// implementazioni commentano la stessa stecca in due modi diversi.

const val MOOD_ATTESA = "attesa"
const val MOOD_BEI_GIORNI = "bei_giorni"

data class Traguardo(val perc: Int, val emoji: String, val testo: String)

enum class TonoToast { NORMALE, GRANDE, CHIAVE, DOLCE }

data class MessaggioChiusura(
    val max: Int,
    val emoji: String,
    val tono: TonoToast,
    val testo: String,
)

/** Tutto quel che cambia fra un'attesa e dei bei giorni, in un posto solo. */
data class Mood(
    val id: String,
    val emoji: String,
    val nome: String,
    val badge: String,
    /** che stecca è, sulla scheda che si sceglie */
    val sottotitolo: String,
    /** cosa cambia scegliendolo, sotto le due schede */
    val spiegazione: String,
    val etichettaTraguardo: String,
    val etichettaEmoji: String,
    /** l'etichetta della casella accanto a «fatti» */
    val etichettaRestano: String,
    val bannerEmoji: String,
    val toastDespunta: String,
    val titoloChiusura: String,
    val domandaSoddisfazione: String,
    val invitoNota: String,
    /** ⚠️ al 100 % i fuochi dipendono dall'umore: vedi sotto */
    val fuochiAl100: Boolean,
    val frasi: List<String>,
    val emojiSpunta: List<String>,
    val traguardi: List<Traguardo>,
    val messaggiChiusura: List<MessaggioChiusura>,
) {
    /** «giorni che mancano» e «giorni che restano»: lo stesso numero, due letture. */
    fun etichettaConto(quanti: Int): String = when {
        id == MOOD_BEI_GIORNI && quanti == 1 -> "giorno che resta"
        id == MOOD_BEI_GIORNI -> "giorni che restano"
        quanti == 1 -> "giorno che manca"
        else -> "giorni che mancano"
    }

    fun banner(data: String): String =
        if (id == MOOD_BEI_GIORNI)
            "$bannerEmoji Oggi ($data) non l'hai ancora spuntato: non lasciarlo passare così."
        else
            "$bannerEmoji Oggi ($data) non l'hai ancora spuntato!"
}

private val MOOD_DI_ATTESA = Mood(
    id = MOOD_ATTESA,
    emoji = "⏳",
    nome = "Attesa",
    badge = "⏳ Attesa",
    sottotitolo = "Aspetti qualcosa. Ogni giorno spuntato è un giorno in meno da aspettare.",
    spiegazione = "Le frasi e i traguardi parlano di quanto manca: ogni spunta è un giorno " +
        "tolto di mezzo.",
    etichettaTraguardo = "Cosa stai aspettando?",
    etichettaEmoji = "Emoji del traguardo",
    etichettaRestano = "mancano",
    bannerEmoji = "⏳",
    toastDespunta = "Spunta tolta. Ci hai ripensato?",
    titoloChiusura = "Ci siamo!",
    domandaSoddisfazione = "Quanto sei soddisfatto di com'è andata?",
    invitoNota = "Due parole su com'è andata (facoltative).",
    // al 100 % il traguardo è raggiunto davvero: fuochi
    fuochiAl100 = true,
    frasi = listOf(
        "Grande! ...anche se non dipende da te. 😄",
        "E uno! Il traguardo trema. 😏",
        "Giorno archiviato. Prossimo!",
        "Un altro se n'è andato. Ciao ciao! 👋",
        "Bravo, hai aspettato benissimo. 🏅",
        "Complimenti: è passato un giorno. Merito tuo? Mah. 🤷",
        "Spuntato. La matematica non mente. 🧮",
        "Questo giorno non tornerà. Meglio così!",
        "Piano piano si arriva ovunque. 🐢",
        "Hai battuto il calendario. Di nuovo.",
        "Un giorno in meno, zero rimpianti.",
        "Il tempo passa, tu spunti. Squadra perfetta.",
        "Chi spunta, arriva. Regola numero uno. 📏",
        "Fatto! Ora puoi anche riposarti.",
        "Sei una macchina da spunte. 🤖",
        "Il calendario ti teme, ormai.",
        "Ancora uno? Ancora uno. 💪",
        "Segnato! La strada si accorcia.",
        "Oggi c'eri. È già tantissimo.",
        "Un mattoncino in più nel muro. 🧱",
        "Spunta fatta, coscienza a posto. 😌",
        "Il conto alla rovescia ringrazia.",
        "Costanza batte entusiasmo. Sempre. 🔁",
        "Nessuno ti toglie questo giorno.",
        "Eccellente. Continua così, campione! 🏆",
        "Un altro giorno domato. 🦁",
        "Questa spunta profuma di libertà. 🌬️",
        "Che soddisfazione, eh? 😎",
        "Piccolo passo, grande direzione. 🚶",
        "Spuntato con stile. ✨",
        "Fatto. Si torna a vivere. 🎈",
    ),
    emojiSpunta = listOf(
        "✅", "🎉", "⭐", "🔥", "💪", "🚀", "🌟", "🏆", "😎", "🍀",
        "🎯", "⚡", "🥳", "👏", "💎", "🌈", "🦾", "🧨", "🎊", "☀️",
    ),
    traguardi = listOf(
        Traguardo(25, "🌱", "Un quarto fatto! Il difficile è cominciare."),
        Traguardo(50, "⛰️", "Metà strada! Da qui si vede il traguardo."),
        Traguardo(75, "🔥", "Tre quarti! Ormai è in discesa."),
        Traguardo(100, "🏁", "FINITO! Li hai spuntati tutti. Campione!"),
    ),
    messaggiChiusura = listOf(
        MessaggioChiusura(
            20, "🫂", TonoToast.DOLCE,
            "Non è andata come volevi. Però ci sei arrivato in fondo, e quello non te lo toglie nessuno."
        ),
        MessaggioChiusura(
            40, "💪", TonoToast.DOLCE,
            "Poteva andare meglio, è vero. Ma l'hai chiusa lo stesso: chiuderla era la parte difficile."
        ),
        MessaggioChiusura(
            60, "👍", TonoToast.GRANDE,
            "Nel complesso ci sta! Una stecca chiusa è una stecca chiusa."
        ),
        MessaggioChiusura(
            80, "🎉", TonoToast.GRANDE,
            "Bella soddisfazione! Te la sei guadagnata un giorno alla volta."
        ),
        MessaggioChiusura(
            95, "🏆", TonoToast.CHIAVE,
            "Grandissimo! Questa è di quelle da ricordare."
        ),
        MessaggioChiusura(
            100, "🎆", TonoToast.CHIAVE,
            "PERFETTA! Meglio di così non si poteva chiudere. Che spettacolo!"
        ),
    ),
)

private val MOOD_DI_BEI_GIORNI = Mood(
    id = MOOD_BEI_GIORNI,
    emoji = "🌅",
    nome = "Bei giorni",
    badge = "🌅 Bei giorni",
    sottotitolo = "Ci sei dentro. Ogni giorno spuntato è un bel giorno che se ne va.",
    spiegazione = "Le frasi e i traguardi parlano di quel che finisce: ogni spunta è un bel " +
        "giorno che se ne va.",
    etichettaTraguardo = "Cosa stai vivendo?",
    etichettaEmoji = "Emoji di questi giorni",
    etichettaRestano = "restano",
    bannerEmoji = "🌅",
    toastDespunta = "Spunta tolta. Te lo riprendi? 🌿",
    titoloChiusura = "È finita.",
    domandaSoddisfazione = "Quanto sono stati belli?",
    invitoNota = "Cosa ti resta di questi giorni? (facoltativo)",
    // ⚠️ Al 100 % qui non c'è niente da festeggiare: è il giorno in cui
    // finisce. I coriandoli restano — è comunque un saluto — ma i fuochi
    // d'artificio sopra un addio suonerebbero come una presa in giro.
    fuochiAl100 = false,
    frasi = listOf(
        "Un giorno bello, e adesso è tuo per sempre. 🤍",
        "Segnato. Peccato solo che sia già passato.",
        "Uno in meno da vivere. Falli valere. 🌿",
        "Bello, vero? Domani ce n'è ancora.",
        "Questo giorno non torna. Ma c'è stato. ✨",
        "Spuntato, con un po' di magone. 🥲",
        "Archiviato fra le cose belle. 📦",
        "Il calendario si accorcia. Che peccato. 🍂",
        "Un altro pezzo di bello se n'è andato.",
        "Godilo finché dura. Sta durando. ☀️",
        "Giorno pieno. Di quelli che si ricordano.",
        "Se ne va uno dei buoni. 🌙",
        "Un giorno in meno, e sì: dispiace un po'.",
        "Fatto. E ne resta ancora un po'. 🌻",
        "Che bello essere stati qui, oggi.",
        "Spunta dolceamara. Le migliori. 🍯",
        "Il tempo corre proprio quando è bello, eh?",
        "Questo te lo porti dietro. 🎒",
        "Un giorno da tenere. Segnato. 📌",
        "Anche oggi è stato dei nostri. 🤝",
        "Piano, che finisce. 🐌",
        "Uno in meno. Ma che meraviglia. 🌊",
        "Il bello passa. Passa bene, però.",
        "Segnato. Domani ancora, per fortuna.",
        "Un giorno così vale doppio. ⭐",
        "Ci siamo stati. È già molto. 🕊️",
        "Fine giornata, cuore pieno. 💛",
        "Un altro da mettere da parte. 🫙",
        "Non era un giorno qualunque. Spuntato.",
        "Se ne va, ma resta. Funziona così. 🌱",
    ),
    emojiSpunta = listOf(
        "🤍", "🌅", "🌿", "☀️", "🍃", "🌙", "✨", "🕊️", "🌻", "🐚",
        "🍂", "💛", "🌊", "🎐", "🫧", "🌸", "🧡", "🌤️", "⛱️", "📸",
    ),
    traguardi = listOf(
        Traguardo(25, "🌤️", "Un quarto se n'è andato. Rallenta: è adesso che è bello."),
        Traguardo(50, "⏳", "Metà. Da qui in poi comincia a finire."),
        Traguardo(75, "🍂", "Tre quarti. Ne restano pochi: falli contare."),
        Traguardo(100, "🌙", "È finita. Sono stati giorni belli, e li hai vissuti tutti."),
    ),
    messaggiChiusura = listOf(
        MessaggioChiusura(
            20, "🫂", TonoToast.DOLCE,
            "Dovevano essere bei giorni e non lo sono stati. Capita, e non è colpa tua."
        ),
        MessaggioChiusura(
            40, "🍃", TonoToast.DOLCE,
            "Non proprio come te li immaginavi. Qualcosa di buono però te lo porti dietro."
        ),
        MessaggioChiusura(
            60, "🌤️", TonoToast.GRANDE,
            "Giorni normali, nel complesso. E i giorni normali sono la gran parte della vita."
        ),
        MessaggioChiusura(
            80, "🌻", TonoToast.GRANDE,
            "Sono stati bei giorni. Adesso finiscono, ed è giusto dispiacersi un po'."
        ),
        MessaggioChiusura(
            95, "🌅", TonoToast.CHIAVE,
            "Che periodo. Di quelli che si raccontano. Finisce, ma non se ne va."
        ),
        MessaggioChiusura(
            100, "🤍", TonoToast.CHIAVE,
            "Indimenticabile. Non si poteva chiedere di meglio — ed è per questo che adesso fa un po' male."
        ),
    ),
)

/** I due umori nell'ordine in cui si scelgono. */
val MOODS = listOf(MOOD_DI_ATTESA, MOOD_DI_BEI_GIORNI)

/**
 * L'umore di una stecca. Un valore che non conosciamo non ripiega su niente
 * di sensato — le frasi verrebbero da una tabella che non esiste — quindi si
 * torna a quello che l'app sapeva fare prima che la colonna esistesse.
 */
fun moodDi(id: String?): Mood = MOODS.firstOrNull { it.id == id } ?: MOOD_DI_ATTESA

// ── Soddisfazione: le fasce sono le stesse per tutt'e due gli umori ──────
// Qui non si commenta la stecca, si legge un numero da 1 a 100: la domanda
// cambia («quanto sei soddisfatto?» / «quanto sono stati belli?»), la scala no.

data class Fascia(val max: Int, val testo: String)

val SODD_ETICHETTE = listOf(
    Fascia(15, "Per niente. 😔"),
    Fascia(30, "Poco."),
    Fascia(45, "Insomma…"),
    Fascia(60, "Nella media."),
    Fascia(75, "Bene! 🙂"),
    Fascia(90, "Molto bene! 😄"),
    Fascia(100, "Alla grande! 🤩"),
)

fun etichettaSoddisfazione(valore: Int): String =
    (SODD_ETICHETTE.firstOrNull { valore <= it.max } ?: SODD_ETICHETTE.last()).testo

fun messaggioChiusura(valore: Int, mood: Mood): MessaggioChiusura =
    mood.messaggiChiusura.firstOrNull { valore <= it.max } ?: mood.messaggiChiusura.last()

// ── Calcoli sul periodo ───────────────────────────────────────────────────

// La data come si scrive in Italia. Sta qui e non nella schermata perché la
// usano anche i messaggi di perchePuoNonEsserci().
private val FORMATO_IT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun LocalDate.italiana(): String = format(FORMATO_IT)

/**
 * I giorni che compongono il periodo, sabato e domenica esclusi se richiesto.
 * Come `giorniDelPeriodo()` sul web, guardia sul numero di giri compresa: un
 * periodo assurdo non deve poter bloccare l'app.
 */
fun giorniDelPeriodo(impostazioni: SpSettings?): List<LocalDate> {
    if (impostazioni == null) return emptyList()
    val inizio = runCatching { LocalDate.parse(impostazioni.startDate) }.getOrNull() ?: return emptyList()
    val fine = runCatching { LocalDate.parse(impostazioni.endDate) }.getOrNull() ?: return emptyList()
    if (fine < inizio) return emptyList()

    val giorni = mutableListOf<LocalDate>()
    var corrente = inizio
    var guardia = 0
    while (corrente <= fine && guardia < 4000) {
        val weekend = corrente.dayOfWeek == DayOfWeek.SATURDAY || corrente.dayOfWeek == DayOfWeek.SUNDAY
        if (!(impostazioni.skipWeekend && weekend)) giorni += corrente
        corrente = corrente.plusDays(1)
        guardia++
    }
    return giorni
}

/**
 * ⚠️ Una giornata chiave fuori dal periodo si salva, si vede nell'elenco delle
 * impostazioni e poi NON C'È: la griglia disegna i giorni di
 * [giorniDelPeriodo], e quel giorno lì dentro non compare — non si può
 * spuntare, e i fuochi non partiranno mai. Non se ne accorge nessuno finché
 * non si va a cercarla.
 *
 * Il periodo arriva dai **campi in corso di modifica** e non dalle
 * impostazioni salvate: nel dialogo si sta ancora scegliendo, e quelle
 * salvate sono le precedenti (o non esistono affatto).
 *
 * Copia di `perchePuoNonEsserci()` in spuntiamola.html: torna il motivo, o
 * `null` se la data va bene.
 */
fun perchePuoNonEsserci(
    giorno: String,
    inizio: String,
    fine: String,
    saltaWeekend: Boolean,
): String? {
    val data = runCatching { LocalDate.parse(giorno) }.getOrNull() ?: return "Data non valida."
    val da = runCatching { LocalDate.parse(inizio) }.getOrNull()
    val a = runCatching { LocalDate.parse(fine) }.getOrNull()
    if (da == null || a == null) return "Scegli prima il periodo della stecca."
    if (a < da) return "Il periodo non è valido: sistemalo prima."

    if (data < da || data > a) {
        return "Il ${data.italiana()} è fuori dal periodo della stecca " +
            "(${da.italiana()} → ${a.italiana()}), quindi nella griglia quel giorno " +
            "non ci sarebbe e non lo potresti spuntare."
    }
    // stessa conseguenza, altra causa: col weekend saltato il sabato e la
    // domenica non sono giorni della stecca
    val weekend = data.dayOfWeek == DayOfWeek.SATURDAY || data.dayOfWeek == DayOfWeek.SUNDAY
    if (saltaWeekend && weekend) {
        val quale = if (data.dayOfWeek == DayOfWeek.SUNDAY) "domenica" else "sabato"
        return "Il ${data.italiana()} è un $quale, e questa stecca salta i fine " +
            "settimana: nella griglia quel giorno non ci sarebbe."
    }
    return null
}

/** Quanti giorni passati di fila sono spuntati, contando a ritroso da oggi. */
fun calcolaSerie(giorni: List<LocalDate>, spunte: Map<String, String>): Int {
    val oggi = LocalDate.now()
    val passati = giorni.filter { it <= oggi }
    var serie = 0
    for (giorno in passati.asReversed()) {
        if (spunte.containsKey(giorno.toString())) serie++ else break
    }
    return serie
}

/**
 * C'è ancora qualcosa da spuntare?
 * Il periodo è da chiudere quando sono tutti fatti *oppure* quando l'ultimo
 * giorno è passato: nel secondo caso restano delle caselle vuote, ed è giusto
 * così — la stecca si archivia com'è andata.
 */
fun steccaDaChiudere(giorni: List<LocalDate>, spunte: Map<String, String>, fine: String?): Boolean {
    if (giorni.isEmpty()) return false
    val tuttiFatti = giorni.all { spunte.containsKey(it.toString()) }
    val scaduta = fine?.let { runCatching { LocalDate.now() > LocalDate.parse(it) }.getOrDefault(false) } ?: false
    return tuttiFatti || scaduta
}
