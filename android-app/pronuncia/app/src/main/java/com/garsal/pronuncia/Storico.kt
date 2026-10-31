package com.garsal.pronuncia

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Lo storico dei tentativi (fase 5): una riga per tentativo, mani libere o
 * esercizio a mano, con le sue parole.
 *
 * ⚠️ Si scrive PRIMA qui (Room) e poi parte verso `pr_tentativi` su Supabase
 * (v1.20.0, `StoricoRemoto`): senza rete il tentativo resta con `inviato`
 * falso e parte alla prossima occasione. Su un telefono nuovo, o dopo una
 * reinstallazione, lo storico vuoto si riprende dal database.
 *
 * ⚠️ La frase si cita per `fraseId` (che non si cambia mai, vedi `Frasi`) MA
 * il testo si copia sulla riga: un testo corretto domani non deve riscrivere
 * quel che si è pronunciato ieri — è la stessa scelta dei valori congelati
 * sulle righe di `al_log` nella suite.
 *
 * ⚠️ Un silenzio (`NessunaVoce`) e un comando a voce NON sono tentativi e non
 * si registrano: lo storico direbbe che si è sbagliata una frase mai detta.
 */
@Entity(tableName = "tentativi", indices = [Index("giorno"), Index("frase_id")])
data class Tentativo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Istante (ms) e giorno locale: il giorno si scrive e non si ricava,
     *  o un cambio di fuso sposterebbe i tentativi di giorno. */
    val quando: Long,
    val giorno: String,
    @ColumnInfo(name = "frase_id") val fraseId: String,
    val testo: String,
    val livello: String,
    val tema: String,
    /** `mani_libere` | `esercizio` */
    val fonte: String,
    val pronuncia: Double,
    val accuratezza: Double,
    val fluidita: Double,
    val completezza: Double,
    val riconosciuto: String,
    /** Già in `pr_tentativi`. Le righe riprese dal database nascono vere. */
    @ColumnInfo(defaultValue = "0") val inviato: Boolean = false,
)

/** Una parola di un tentativo. `parola` è minuscola e senza punteggiatura,
 *  così «London,» e «london» si contano insieme. */
@Entity(
    tableName = "parole",
    foreignKeys = [ForeignKey(Tentativo::class, ["id"], ["tentativo_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tentativo_id"), Index("parola")],
)
data class ParolaTentativo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tentativo_id") val tentativoId: Long,
    val parola: String,
    val accuratezza: Double,
    val errore: String,
)

data class Giorno(val giorno: String, val tentativi: Int, val frasi: Int, val media: Double, val riuscite: Int)

data class Punteggio(val fraseId: String, val pronuncia: Double)

/** Una parola del catalogo e com'è andata: quante volte, il migliore,
 *  l'ultimo e la media del voto complessivo (v1.20.0). */
data class StatParola(
    val fraseId: String, val testo: String, val livello: String, val tema: String,
    val volte: Int, val migliore: Double, val ultimo: Double, val media: Double, val quando: Long,
)

data class ParolaDifficile(val parola: String, val volte: Int, val sbagliate: Int, val media: Double)

@Dao
interface StoricoDao {
    @Insert suspend fun inserisci(t: Tentativo): Long
    @Insert suspend fun inserisci(p: List<ParolaTentativo>)

    @Transaction
    suspend fun registra(t: Tentativo, parole: List<ParolaTentativo>) {
        val id = inserisci(t)
        inserisci(parole.map { it.copy(tentativoId = id) })
    }

    /** ⚠️ «Riuscito» è sopra la stessa soglia del «Good» ([soglia] la passa
     *  chi chiama), non un secondo numero scritto qui. */
    @Query(
        """SELECT giorno, COUNT(*) AS tentativi, COUNT(DISTINCT frase_id) AS frasi,
           AVG(pronuncia) AS media, SUM(CASE WHEN pronuncia >= :soglia THEN 1 ELSE 0 END) AS riuscite
           FROM tentativi GROUP BY giorno ORDER BY giorno DESC LIMIT :quanti""",
    )
    fun perGiorno(soglia: Double, quanti: Int): Flow<List<Giorno>>

    /** Le parole in più (`Insertion`) non stanno nella frase: non contano. */
    @Query(
        """SELECT parola, COUNT(*) AS volte,
           SUM(CASE WHEN errore != 'None' OR accuratezza < :soglia THEN 1 ELSE 0 END) AS sbagliate,
           AVG(accuratezza) AS media
           FROM parole WHERE errore != 'Insertion' GROUP BY parola
           HAVING sbagliate > 0 ORDER BY sbagliate DESC, media ASC LIMIT :quanti""",
    )
    fun paroleDifficili(soglia: Double, quanti: Int): Flow<List<ParolaDifficile>>

    /** Per il ripasso (fase 6): letture una tantum, non Flow. */
    @Query("SELECT frase_id AS fraseId, pronuncia FROM tentativi ORDER BY quando DESC")
    suspend fun punteggi(): List<Punteggio>

    @Query(
        """SELECT parola, COUNT(*) AS volte,
           SUM(CASE WHEN errore != 'None' OR accuratezza < :soglia THEN 1 ELSE 0 END) AS sbagliate,
           AVG(accuratezza) AS media
           FROM parole WHERE errore != 'Insertion' GROUP BY parola
           HAVING sbagliate > 0 ORDER BY sbagliate DESC, media ASC LIMIT :quanti""",
    )
    suspend fun paroleDifficiliOra(soglia: Double, quanti: Int): List<ParolaDifficile>

    @Query("SELECT * FROM tentativi ORDER BY quando DESC LIMIT :quanti")
    fun ultimi(quanti: Int): Flow<List<Tentativo>>

    @Query("SELECT * FROM parole WHERE tentativo_id = :id ORDER BY id")
    suspend fun paroleDi(id: Long): List<ParolaTentativo>

    @Query("SELECT COUNT(*) FROM tentativi")
    fun totale(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tentativi")
    suspend fun quanti(): Int

    /** ⚠️ «ultimo» è il voto del tentativo più recente, non il massimo della data. */
    @Query(
        """SELECT t.frase_id AS fraseId, MAX(t.testo) AS testo, MAX(t.livello) AS livello, MAX(t.tema) AS tema,
           COUNT(*) AS volte, MAX(t.pronuncia) AS migliore, AVG(t.pronuncia) AS media, MAX(t.quando) AS quando,
           (SELECT u.pronuncia FROM tentativi u WHERE u.frase_id = t.frase_id ORDER BY u.quando DESC LIMIT 1) AS ultimo
           FROM tentativi t WHERE t.frase_id LIKE 'w-%' GROUP BY t.frase_id""",
    )
    fun perParola(): Flow<List<StatParola>>

    @Query("SELECT * FROM tentativi WHERE inviato = 0 ORDER BY id LIMIT :quanti")
    suspend fun daInviare(quanti: Int): List<Tentativo>

    @Query("UPDATE tentativi SET inviato = 1 WHERE id IN (:ids)")
    suspend fun segnaInviati(ids: List<Long>)

    @Query("DELETE FROM tentativi")
    suspend fun svuota()
}

@Database(entities = [Tentativo::class, ParolaTentativo::class], version = 2)
abstract class Archivio : RoomDatabase() {
    abstract fun storico(): StoricoDao
}

/** v1.20.0: la colonna che dice se il tentativo è già nel database. */
private val DA_1_A_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tentativi ADD COLUMN inviato INTEGER NOT NULL DEFAULT 0")
    }
}

object Storico {
    @Volatile private var db: Archivio? = null

    fun dao(ctx: Context): StoricoDao = (db ?: synchronized(this) {
        db ?: Room.databaseBuilder(ctx.applicationContext, Archivio::class.java, "storico.db")
            .addMigrations(DA_1_A_2).build().also { db = it }
    }).storico()

    fun normalizza(p: String) = p.lowercase().trim { !it.isLetterOrDigit() && it != '\'' }

    /** Registra un tentativo. ⚠️ Non lancia mai: uno storico che non si scrive
     *  non deve fermare il ciclo a metà frase. */
    suspend fun registra(ctx: Context, frase: Frase, esito: Esito, fonte: String) {
        runCatching {
            withContext(Dispatchers.IO) {
                val t = Tentativo(
                    quando = System.currentTimeMillis(),
                    giorno = LocalDate.now().toString(),
                    fraseId = frase.id, testo = frase.testo, livello = frase.livello, tema = frase.tema,
                    fonte = fonte,
                    pronuncia = esito.pronuncia, accuratezza = esito.accuratezza,
                    fluidita = esito.fluidita, completezza = esito.completezza,
                    riconosciuto = esito.riconosciuto,
                )
                val parole = esito.parole.mapNotNull { p ->
                    normalizza(p.testo).takeIf { it.isNotEmpty() }
                        ?.let { ParolaTentativo(tentativoId = 0, parola = it, accuratezza = p.accuratezza, errore = p.errore) }
                }
                dao(ctx).registra(t, parole)
            }
        }
        // Fuori dal runCatching di sopra: l'invio ha il suo, e non deve
        // ritardare il ciclo — parte e basta.
        StoricoRemoto.inviaPiuTardi(ctx)
    }
}
