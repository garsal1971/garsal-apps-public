package com.garsal.pronuncia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le voci del corso (`pr_parole_corso`, v1.37.0; lettura e filtro dalla v1.39.0). */
class CorsoTest {
    private val json = """[
        {"id":"11111111-1111-1111-1111-111111111111","testo":" Nice to meet you ","traduzione":"Piacere","tipo":"frase"},
        {"id":"22222222-2222-2222-2222-222222222222","testo":"schedule","traduzione":"programma","tipo":"parola"},
        {"id":"33333333-3333-3333-3333-333333333333","testo":"   ","traduzione":"vuota","tipo":"parola"},
        {"testo":"senza id","traduzione":"x","tipo":"frase"},
        {"id":"44444444-4444-4444-4444-444444444444","testo":"deadline","tipo":"parola"}
    ]"""

    @Test fun `le voci prendono id c- tema corso e il tipo giusto`() {
        val l = Corso.leggi(json)!!
        val frase = l.voci.first { it.id == "c-11111111-1111-1111-1111-111111111111" }
        assertEquals("Nice to meet you", frase.testo)
        assertEquals(Corso.TEMA, frase.tema)
        assertEquals(Corso.LIVELLO, frase.livello)
        assertFalse(frase.parola)
        assertTrue(l.voci.first { it.testo == "schedule" }.parola)
    }

    @Test fun `una riga malformata si salta e le altre restano`() {
        val l = Corso.leggi(json)!!
        assertEquals(listOf("Nice to meet you", "schedule", "deadline"), l.voci.map { it.testo })
    }

    @Test fun `le traduzioni stanno sotto l id dell app e una mancante non si inventa`() {
        val l = Corso.leggi(json)!!
        assertEquals("Piacere", l.traduzioni["c-11111111-1111-1111-1111-111111111111"])
        assertEquals("programma", l.traduzioni["c-22222222-2222-2222-2222-222222222222"])
        assertNull(l.traduzioni["c-44444444-4444-4444-4444-444444444444"])
    }

    @Test fun `una risposta che non e un elenco torna null e un elenco vuoto no`() {
        assertNull(Corso.leggi("""{"message":"JWT expired"}"""))
        assertNull(Corso.leggi("non json"))
        assertEquals(0, Corso.leggi("[]")!!.voci.size)
    }

    private val catalogo = listOf(
        Frase("A1-k-01", "A1", "cibo", "I like pasta"),
        Frase("B2-h-01", "B2", "casa", "The house is quiet"),
    ) + Corso.leggi(json)!!.voci.filter { !it.parola }

    @Test fun `col tema corso escono solo le voci del corso`() {
        val f = Corso.filtra(catalogo, null, Corso.TEMA)
        assertEquals(listOf("Nice to meet you"), f.map { it.testo })
    }

    @Test fun `il filtro livello lascia sempre passare le voci del corso`() {
        val f = Corso.filtra(catalogo, "A1", null)
        assertEquals(setOf("I like pasta", "Nice to meet you"), f.map { it.testo }.toSet())
        assertEquals(1, Corso.filtra(catalogo, "B2", Corso.TEMA).size)
    }

    @Test fun `una voce del corso entra nel ripasso a intervalli come le altre`() {
        val giorno = 24 * 60 * 60 * 1000L
        val ora = 100 * giorno
        val voci = Corso.filtra(catalogo, null, Corso.TEMA)
        val id = voci.single().id
        // Sbagliata ieri: è da rifare, e torna come «già fatta» col suo voto.
        val s = Ripasso.componi(voci, listOf(Passato(id, 40.0, ora - giorno)), 1, ora, 80.0, mescola = { it })
        assertEquals(id, s.single().frase.id)
        assertEquals(40.0, s.single().recente!!, 0.001)
        // Riuscita poco fa: aspetta il suo intervallo, quindi si ripropone solo se non c'è altro.
        val riuscita = Ripasso.stato(listOf(Passato(id, 95.0, ora - giorno / 4)), 80.0)
        assertFalse(Ripasso.scaduta(riuscita, ora))
    }
}
