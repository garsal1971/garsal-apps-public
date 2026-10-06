package com.garsal.appsphere.calorie

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.garsal.appsphere.core.Palette
import kotlin.math.abs
import kotlin.math.max

/**
 * 📊 La Dashboard — cinque sezioni, nell'ordine delle domande che ci si fa
 * aprendo l'app: ⚖️ le pesate, 🔥 le calorie di oggi, 📐 le calorie per tratto,
 * 📈 come sta andando, e il **giorno per giorno**.
 *
 * ⚠️ Tutto qui dentro copre l'**arco della dieta**, non una finestra di N
 * giorni: il periodo *è* il piano, e due archi diversi sulla stessa pagina
 * sarebbero due risposte diverse alla stessa domanda.
 */
@Composable
internal fun VistaDashboard(stato: CalorieState, onApriGiorno: (String) -> Unit) {
    val oggi = stato.oggi
    val obiettivo = stato.obiettivo
    val arco = stato.arco
    val piano = stato.piano
    val giorni = piano.giorni

    LazyColumn(
        contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text(
                    obiettivo?.nome ?: "nessun obiettivo attivo",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.dark,
                )
                arco?.let {
                    Text(
                        "${dataBreve(it.primoVero)} → ${dataBreve(it.ultimo)}",
                        color = Palette.muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (obiettivo == null) {
            item {
                Nota(
                    "Non c'è nessun obiettivo attivo, quindi non c'è nessuna dieta da seguire: il " +
                        "target è il semplice mantenimento del peso, e questa pagina non ha un piano " +
                        "da raccontare. L'obiettivo si crea in «Ti pisasti?» — qui si legge e basta.",
                    Palette.warning,
                )
            }
            // Le calorie di oggi hanno senso lo stesso: il mantenimento è un target.
            item { RiquadroCalorieDiOggi(stato) }
            return@LazyColumn
        }

        // ── 1. Le pesate ──
        item {
            val peso = CalorieRegole.pesoAl(stato.pesate, oggi)
            val pesatoOggi = stato.pesate.any { it.giorno == oggi }
            val pianoOggi = CalorieRegole.pesoPianoAl(obiettivo.traguardi, oggi)
            val finale = if (obiettivo.traguardi.size >= 2) obiettivo.traguardi.last().peso
            else obiettivo.pesoFinale
            val alFinale = if (peso != null && finale != null) peso - finale else null

            Riquadro(titolo = "⚖️ Le pesate") {
                Valore(
                    "Peso minimo di oggi", kgIt(peso),
                    nota = if (peso == null) "mai pesato" else if (pesatoOggi) "kg" else "kg · ultima pesata nota",
                )
                Valore(
                    "Peso che il piano chiede oggi", kgIt(pianoOggi),
                    nota = if (pianoOggi == null) "servono due traguardi" else "kg",
                )
                Valore(
                    "Mancano al peso finale",
                    alFinale?.let { kgIt(abs(it)) } ?: "—",
                    nota = when {
                        alFinale == null -> null
                        alFinale <= 0 -> "kg · già sotto il finale"
                        else -> "kg · finale ${kgIt(finale)}"
                    },
                    colore = if (alFinale != null && alFinale <= 0) VerdeCalorie else Palette.dark,
                )
                Nota("Il peso viene da «Ti pisasti?»: qui si legge e basta, e le pesate si segnano di là.")
            }
        }

        // ── 2. Le calorie di oggi ──
        item { RiquadroCalorieDiOggi(stato) }

        // ── 3. I tratti del piano ──
        item {
            Riquadro(titolo = "📐 Le calorie per tratto di dieta") {
                if (stato.tratti.isEmpty()) {
                    Nota(
                        "Il piano ha meno di due traguardi, quindi non ci sono tratti: il deficit è la " +
                            "media fino alla fine. Aggiungendo traguardi in «Ti pisasti?» ogni periodo " +
                            "avrà il suo target.",
                        Palette.warning,
                    )
                } else {
                    stato.tratti.forEach { SchedaTratto(it, stato.tratti.size) }
                    Nota(
                        "Ogni tratto è calcolato sul peso medio che il piano prevede lì, non su quello " +
                            "di oggi: il basale cala col peso, quindi a parità di ritmo l'ultimo tratto " +
                            "chiede meno del primo. Sono i valori «se stai sul piano»: il recupero dello " +
                            "scarto non c'è — nei tratti futuri sarebbe un'invenzione. Il conto di oggi " +
                            "per intero è nel riquadro del 📓 Diario."
                    )
                }
            }
        }

        // ── 4. Come sta andando ──
        item {
            // Il saldo somma gli scarti sui soli giorni **segnati**: contare i
            // giorni non segnati come se fossero a zero calorie darebbe un
            // deficit enorme e falso — un giorno non segnato non è un digiuno.
            val passati = giorni.filterNot { it.futuro }
            val segnati = passati.filter { it.kcal != null }
            val conTarget = segnati.filter { it.target != null }
            val media = if (segnati.isEmpty()) null else segnati.sumOf { it.kcal ?: 0.0 } / segnati.size
            val dentro = conTarget.count { (it.kcal ?: 0.0) <= (it.target ?: 0.0) }
            val saldo = piano.saldo

            Riquadro(titolo = "📈 Come sta andando") {
                Valore(
                    "Media al giorno", kcalIt(media),
                    nota = "kcal · su ${segnati.size} " +
                        (if (segnati.size == 1) "giorno segnato" else "giorni segnati"),
                )
                Valore(
                    "Dentro il target",
                    if (conTarget.isEmpty()) "—" else "$dentro/${conTarget.size}",
                    nota = if (conTarget.isEmpty()) null
                    else "${Math.round(dentro * 100.0 / conTarget.size)}%",
                )
                Valore(
                    "Saldo della dieta",
                    if (saldo == 0.0) "0" else conSegno(saldo),
                    nota = "kcal " + (if (saldo > 0) "di troppo" else "in meno") +
                        (if (piano.restanti > 0)
                            " · ${if (piano.alGiorno > 0) "−" else "+"}${kcalIt(abs(piano.alGiorno))}/giorno"
                        else ""),
                    colore = if (saldo > 0) RossoCalorie else VerdeCalorie,
                )
                Valore(
                    "Vale circa",
                    if (saldo == 0.0) "0" else
                        (if (saldo > 0) "+" else "−") + kgIt(abs(saldo) / CalorieRegole.KCAL_PER_KG),
                    nota = "kg rispetto al piano",
                    colore = if (saldo > 0) RossoCalorie else VerdeCalorie,
                )
            }
        }

        // ── Il grafico delle calorie ──
        if (giorni.isNotEmpty()) {
            item {
                Riquadro(titolo = "Calorie al giorno") {
                    GraficoCalorie(giorni)
                    Nota(
                        "Le colonne sono le calorie segnate, la linea il target di quel giorno. Un " +
                            "giorno senza colonna è un giorno non segnato, non un giorno a zero. " +
                            "Da domani in poi la linea è il target previsto: colonne non ce ne sono ancora."
                    )
                }
            }
        }

        // ── 5. Giorno per giorno ──
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Giorno per giorno", fontWeight = FontWeight.Bold, color = Palette.dark)
                if (arco?.tagliato == true) {
                    Nota(
                        "La dieta comincia il ${dataBreve(arco.primoVero)}, ma qui si parte dal " +
                            "${dataBreve(arco.primo)}: più indietro le righe del diario non vengono " +
                            "caricate, e mostrarle vuote direbbe che non hai segnato niente invece " +
                            "che «non lo so».",
                        Palette.warning,
                    )
                }
                IntestazioneGiorni()
            }
        }

        items(giorni, key = { it.giorno }) { g -> RigaGiorno(g, onApriGiorno) }

        item {
            Nota(
                "Dal primo all'ultimo giorno del piano. Per i giorni futuri il target parte da quello " +
                    "del tratto e si porta dietro il saldo accumulato — è il numero fra parentesi." +
                    (if (piano.restanti > 0)
                        " Il saldo di ${conSegno(piano.saldo)} kcal è spalmato sui ${piano.restanti} " +
                            "giorni che restano: ${if (piano.alGiorno > 0) "−" else "+"}" +
                            "${kcalIt(abs(piano.alGiorno))} kcal al giorno, uguale per tutti."
                    else "") +
                    " Spalmarlo è meglio che scaricarlo tutto sul giorno dopo: uno sforo di 900 kcal " +
                    "renderebbe il giorno seguente impraticabile, e un target che non si può seguire " +
                    "non lo si segue. ⚠️ Da oggi, che è ancora da finire, entra solo lo sforo: quello " +
                    "è già successo e non si disfa. Quel che non hai ancora segnato non è un risparmio, " +
                    "è solo un diario a metà."
            )
        }
    }
}

@Composable
private fun RiquadroCalorieDiOggi(stato: CalorieState) {
    val oggi = stato.oggi
    val target = stato.target(oggi)
    val mangiate = CalorieRegole.totali(stato.righePerGiorno[oggi].orEmpty()).kcal
    val restano = target.kcal?.let { it - mangiate }
    val sforato = restano != null && restano < 0

    Riquadro(titolo = "🔥 Le calorie di oggi") {
        Valore(
            "Target", kcalIt(target.kcal),
            nota = if (target.ok) "kcal" + (if (target.congelato) " · congelato" else "") else "da calcolare",
        )
        Valore("Mangiate", kcalIt(mangiate), nota = "kcal")
        Valore(
            if (sforato) "Sforate" else "Restano",
            restano?.let { kcalIt(abs(it)) } ?: "—",
            nota = if (restano == null) null else "kcal",
            colore = if (restano == null) Palette.dark else if (sforato) RossoCalorie else VerdeCalorie,
        )
        BarraTarget(
            percentuale(mangiate, target.kcal ?: 0.0),
            if (sforato) RossoCalorie else VerdeCalorie,
        )
        if (!target.ok) {
            Nota("Il target non si può calcolare: ${spiegaMotivo(target.motivo)}.", Palette.warning)
        }
    }
}

/**
 * ⚠️ **Un tratto è una scheda, non una riga di tabella.** Sette colonne coi
 * caratteri di sistema grandi o si tagliano o vanno a capo ognuna per conto
 * suo: è la stessa scelta della tabella giorno per giorno di «Ti pisasti?».
 * Quel che resta identico alla pagina è l'ordine di lettura — il **target**
 * subito dopo i pesi, perché è la risposta per cui questa tabella esiste, e
 * ritmo, deficit e consumo dopo, che sono la spiegazione.
 */
@Composable
private fun SchedaTratto(t: CalorieRegole.Tratto, quanti: Int) {
    val sfondo = when {
        t.corrente -> VerdeCalorie.copy(alpha = 0.10f)
        t.passato -> Palette.inputBg
        else -> Palette.inputBg.copy(alpha = 0.5f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(sfondo)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${if (t.corrente) "▶ " else ""}Tratto ${t.numero}/$quanti · " +
                "${dataBreve(t.inizio)} → ${dataBreve(t.fine)} · ${t.giorni} giorni",
            fontWeight = if (t.corrente) FontWeight.Bold else FontWeight.SemiBold,
            color = if (t.passato && !t.corrente) Palette.muted else Palette.dark,
            style = MaterialTheme.typography.bodyMedium,
        )
        Valore(
            "Target",
            "${kcalIt(t.target)} kcal",
            colore = if (t.corrente) VerdeCalorie else Palette.dark,
        )
        Valore(
            "Peso",
            "${kgIt(t.pesoInizio)} → ${kgIt(t.pesoFine)}",
            nota = "${if (t.kg > 0) "−" else if (t.kg < 0) "+" else ""}${kgIt(abs(t.kg))} kg",
        )
        Valore("Ritmo", "${ritmoIt(t.kgAlGiorno * 7)} kg/sett")
        Valore("Deficit", if (t.deficit > 0) "−${kcalIt(t.deficit)}" else kcalIt(0.0))
        Valore("Consumo", "${kcalIt(t.tdee)} kcal")
    }
}

@Composable
private fun IntestazioneGiorni() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Text("Giorno", color = Palette.muted, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1.1f))
        Text("Mangiate", color = Palette.muted, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f))
        Text("Target", color = Palette.muted, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f))
        Text("Scarto", color = Palette.muted, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f))
    }
}

/**
 * Una giornata. Si tocca e si apre nel 📓 Diario, che è quel che si vuole fare
 * guardando una riga storta: andare a vedere cosa c'era dentro.
 */
@Composable
private fun RigaGiorno(g: CalorieRegole.GiornoDieta, onApri: (String) -> Unit) {
    val sfondo = when {
        g.oggi -> VerdeCalorie.copy(alpha = 0.10f)
        g.futuro -> Color.Transparent
        else -> Palette.cardBg
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(sfondo)
            .clickable { onApri(g.giorno) }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            (if (g.oggi) "▶ " else "") + dataBreve(g.giorno),
            color = if (g.futuro) Palette.muted else Palette.dark,
            fontWeight = if (g.oggi) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1.1f),
        )
        Text(
            if (g.kcal == null) "—" else kcalIt(g.kcal),
            color = if (g.kcal == null) Palette.muted else Palette.dark,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Column(Modifier.weight(1f)) {
            Text(
                kcalIt(g.target),
                color = if (g.futuro) Palette.dark else Palette.muted,
                style = MaterialTheme.typography.bodyMedium,
            )
            g.riporto?.takeIf { it != 0.0 }?.let {
                Text(
                    "(${if (it > 0) "+" else "−"}${kcalIt(abs(it))})",
                    color = if (it > 0) VerdeCalorie else RossoCalorie,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(
            g.scarto?.let { (if (it > 0) "+" else "") + kcalIt(it) } ?: "—",
            color = when {
                g.scarto == null -> Palette.muted
                g.scarto > 0 -> RossoCalorie
                else -> VerdeCalorie
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Le calorie segnate contro il target, disegnate a mano su un `Canvas`
 * scorrevole — questa schermata non carica nessuna libreria di grafici, come la
 * pagina non ne carica una per le miniature di Memo.
 *
 * ⚠️ Le misure sono in **dp e mai in pixel grezzi**: su uno schermo denso un
 * `4f` vale un terzo di quello che sembra, ed è il difetto che tagliava a metà
 * le date sotto l'asse del grafico di «Ti pisasti?».
 *
 * Il colore dice il verdetto — verde entro il target, rosso sopra — così non
 * bisogna confrontare a occhio la colonna con la linea. Un giorno **senza
 * colonna è un giorno non segnato**, non un giorno a zero.
 */
@Composable
private fun GraficoCalorie(giorni: List<CalorieRegole.GiornoDieta>) {
    val perGiorno = 10.dp
    val massimo = max(
        giorni.mapNotNull { it.kcal }.maxOrNull() ?: 0.0,
        giorni.mapNotNull { it.target }.maxOrNull() ?: 0.0,
    ).takeIf { it > 0 } ?: return

    Box(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Canvas(
            Modifier
                .width(perGiorno * giorni.size)
                .height(160.dp)
        ) {
            val alto = size.height
            val passo = size.width / giorni.size
            val larghezzaColonna = passo * 0.7f
            fun y(valore: Double) = (alto - (valore / massimo * alto)).toFloat()

            giorni.forEachIndexed { i, g ->
                val kcal = g.kcal ?: return@forEachIndexed
                val sopra = g.target != null && kcal > g.target
                drawRect(
                    color = if (sopra) RossoCalorie.copy(alpha = 0.75f) else VerdeCalorie.copy(alpha = 0.75f),
                    topLeft = Offset(i * passo + (passo - larghezzaColonna) / 2, y(kcal)),
                    size = androidx.compose.ui.geometry.Size(larghezzaColonna, alto - y(kcal)),
                )
            }

            // La linea del target: si interrompe dove il target non c'è, invece
            // di saltare il buco — una linea continua su un giorno senza target
            // direbbe che ce n'era uno.
            var precedente: Offset? = null
            giorni.forEachIndexed { i, g ->
                val target = g.target
                if (target == null) { precedente = null; return@forEachIndexed }
                val punto = Offset(i * passo + passo / 2, y(target))
                precedente?.let {
                    drawLine(
                        color = Palette.dark,
                        start = it,
                        end = punto,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
                precedente = punto
            }
        }
    }
}
