package com.garsal.pronuncia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il ripasso: chi torna in una sessione, e quando (`Ripasso`, v1.33.0). */
class RipassoTest {
    private val giorno = 24 * 60 * 60 * 1000L
    private val ora = 100 * giorno
    private val soglia = 80.0

    private fun frase(id: String, testo: String = id) = Frase(id, "A1", "viaggio", testo)
    private fun passato(id: String, voto: Double, giorniFa: Double) = Passato(id, voto, ora - (giorniFa * giorno).toLong())
    private val fermo: (List<Frase>) -> List<Frase> = { it }

    @Test fun `lo stato conta gli ultimi due voti e le riuscite di fila`() {
        val s = Ripasso.stato(listOf(passato("a", 90.0, 1.0), passato("a", 84.0, 2.0), passato("a", 30.0, 3.0)), soglia)
        assertEquals(87.0, s.recente, 0.001)
        assertEquals(2, s.diFila)
        assertEquals(ora - giorno, s.ultimo)
    }

    @Test fun `un voto basso azzera la fila`() {
        val s = Ripasso.stato(listOf(passato("a", 50.0, 1.0), passato("a", 95.0, 2.0)), soglia)
        assertEquals(0, s.diFila)
        assertEquals(72.5, s.recente, 0.001)
    }

    @Test fun `gli intervalli crescono e si fermano a quattordici giorni`() {
        assertEquals(listOf(0, 1, 3, 7, 14, 14), (0..5).map { Ripasso.giorni(it) })
    }

    @Test fun `una frase sbagliata e subito da rifare`() {
        assertTrue(Ripasso.scaduta(Ripasso.Stato(40.0, 0, ora), ora))
    }

    @Test fun `una frase riuscita aspetta il suo intervallo`() {
        assertFalse(Ripasso.scaduta(Ripasso.Stato(90.0, 1, ora - giorno / 2), ora))
        assertTrue(Ripasso.scaduta(Ripasso.Stato(90.0, 1, ora - giorno), ora))
        assertFalse(Ripasso.scaduta(Ripasso.Stato(90.0, 2, ora - 2 * giorno), ora))
        assertTrue(Ripasso.scaduta(Ripasso.Stato(90.0, 2, ora - 3 * giorno), ora))
    }

    @Test fun `la sera e il pomeriggio dopo fanno un giorno`() {
        assertTrue(Ripasso.scaduta(Ripasso.Stato(90.0, 1, ora - 20 * giorno / 24), ora))
    }

    @Test fun `meta nuove e meta scadute, alternate, dalla peggiore di adesso`() {
        val catalogo = (1..6).map { frase("n$it") } + listOf(frase("bene"), frase("male"), frase("cosi"))
        val storia = listOf(
            passato("bene", 95.0, 30.0),
            passato("male", 20.0, 2.0),
            passato("cosi", 60.0, 2.0),
        )
        val s = Ripasso.componi(catalogo, storia, 4, ora, soglia, mescola = fermo)
        assertEquals(listOf("n1", "male", "n2", "cosi"), s.map { it.frase.id })
        assertNull(s[0].recente)
        assertEquals(20.0, s[1].recente!!, 0.001)
    }

    @Test fun `una frase sbagliata un tempo e poi riuscita non torna in cima`() {
        // Il difetto della v1.22.0: contava il voto più basso MAI preso.
        val catalogo = listOf(frase("guarita"), frase("malata"), frase("n1"), frase("n2"))
        val storia = listOf(
            passato("guarita", 10.0, 9.0), passato("guarita", 92.0, 8.0), passato("guarita", 94.0, 7.0),
            passato("malata", 70.0, 1.0),
        )
        val s = Ripasso.componi(catalogo, storia, 2, ora, soglia, mescola = fermo)
        assertEquals(listOf("n1", "malata"), s.map { it.frase.id })
    }

    @Test fun `una frase appena riuscita non torna nella sessione dopo`() {
        val catalogo = listOf(frase("ieri"), frase("n1"), frase("n2"), frase("n3"))
        val storia = listOf(passato("ieri", 90.0, 0.1))
        val s = Ripasso.componi(catalogo, storia, 2, ora, soglia, mescola = fermo)
        assertEquals(listOf("n1", "n2"), s.map { it.frase.id })
    }

    @Test fun `senza altro si completa con quelle in attesa, e la sessione resta di N`() {
        val catalogo = listOf(frase("a"), frase("b"), frase("c"))
        val storia = listOf(passato("a", 90.0, 0.2), passato("b", 90.0, 0.4), passato("c", 90.0, 0.3))
        val s = Ripasso.componi(catalogo, storia, 2, ora, soglia, mescola = fermo)
        assertEquals(listOf("b", "c"), s.map { it.frase.id })
    }

    @Test fun `se le nuove non bastano si prendono piu scadute`() {
        val catalogo = listOf(frase("n1"), frase("x"), frase("y"), frase("z"))
        val storia = listOf(passato("x", 30.0, 1.0), passato("y", 50.0, 1.0), passato("z", 40.0, 1.0))
        val s = Ripasso.componi(catalogo, storia, 4, ora, soglia, mescola = fermo)
        assertEquals(setOf("n1", "x", "y", "z"), s.map { it.frase.id }.toSet())
        assertEquals(4, s.size)
    }

    @Test fun `fra le nuove fino a meta sono scelte per un suono debole`() {
        val catalogo = listOf(frase("n1", "apple"), frase("n2", "bread"), frase("n3", "think"), frase("n4", "three"), frase("n5", "north"))
        val s = Ripasso.componi(
            catalogo, emptyList(), 4, ora, soglia, deboli = listOf("θ"),
            contiene = { f, d -> ProfiloSuoni.contiene(f.testo, d, emptySet()) }, mescola = fermo,
        )
        assertEquals(listOf("n3", "n4", "n1", "n2"), s.map { it.frase.id })
        assertEquals(listOf("θ", "θ", null, null), s.map { it.suono })
    }

    @Test fun `un catalogo vuoto da una sessione vuota`() {
        assertTrue(Ripasso.componi(emptyList(), emptyList(), 10, ora, soglia).isEmpty())
    }
}
