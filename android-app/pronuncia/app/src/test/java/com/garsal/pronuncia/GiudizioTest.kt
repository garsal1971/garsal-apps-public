package com.garsal.pronuncia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il «Good» o «riprova» di un tentativo (`Giudizio.valuta`). */
class GiudizioTest {
    private fun parola(t: String, a: Double, errore: String = "None", vararg fonemi: Pair<String, Double>) =
        Parola(t, a, errore, fonemi.toList())

    private fun esito(voto: Double, vararg parole: Parola, ritmo: Double? = null) =
        Esito("", voto, voto, voto, 100.0, parole.toList(), ritmo)

    @Test fun `sopra soglia e senza controlli e buona`() {
        val v = Giudizio.valuta(esito(85.0, parola("think", 85.0)), null, null, null, true)
        assertTrue(v.buona)
        assertTrue(v.motivi.isEmpty())
        assertTrue(v.daCurare.isEmpty())
    }

    @Test fun `sotto il voto complessivo si ripete`() {
        val v = Giudizio.valuta(esito(79.0, parola("think", 79.0)), null, null, null, true)
        assertFalse(v.buona)
        assertTrue(v.difetti.single() is Difetto.Voto)
    }

    @Test fun `una parola sotto soglia boccia anche un voto alto`() {
        val v = Giudizio.valuta(esito(92.0, parola("I", 99.0), parola("think", 55.0), parola("so", 98.0)), 70, null, null, true)
        assertFalse(v.buona)
        assertEquals("think", (v.difetti.single() as Difetto.ParolaBassa).parola.testo)
        assertEquals(listOf("think"), v.daCurare.map { it.testo })
    }

    @Test fun `una parola saltata boccia e una in piu no`() {
        val saltata = Giudizio.valuta(esito(90.0, parola("I", 99.0), parola("think", 0.0, "Omission")), 70, null, null, true)
        assertTrue(saltata.difetti.any { it is Difetto.Saltata })
        val inPiu = Giudizio.valuta(esito(90.0, parola("I", 99.0), parola("uh", 10.0, "Insertion")), 70, null, null, true)
        assertTrue(inPiu.buona)
    }

    @Test fun `il suono peggiore si dice con le sue lettere`() {
        val think = parola("think", 80.0, "None", "θ" to 30.0, "ɪ" to 90.0, "ŋ" to 95.0, "k" to 95.0)
        val v = Giudizio.valuta(esito(88.0, think), null, 60, null, true)
        assertFalse(v.buona)
        val s = v.difetti.single() as Difetto.Suono
        assertEquals("θ", s.fonema)
        assertEquals(0..1, s.grafema)
    }

    @Test fun `un ritmo che Azure non da non boccia e lo dice`() {
        val v = Giudizio.valuta(esito(90.0, parola("hello", 90.0)), null, null, 70, true)
        assertTrue(v.buona)
        assertEquals(1, v.note.size)
    }

    @Test fun `un ritmo basso boccia`() {
        val v = Giudizio.valuta(esito(90.0, parola("hello", 90.0), ritmo = 50.0), null, null, 70, false)
        assertFalse(v.buona)
        assertTrue(v.difetti.single() is Difetto.Ritmo)
    }
}
