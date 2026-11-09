package com.garsal.pronuncia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Il voto della sessione e le lettere dei suoni. */
class VarieTest {
    @Test fun `il voto e la media dei migliori diviso dieci`() {
        assertEquals(9, votoSessione(listOf(85.0, 95.0)))
        assertEquals(10, votoSessione(listOf(100.0)))
        assertEquals(8, votoSessione(listOf(84.0, 82.0)))
    }

    @Test fun `il voto non scende sotto uno e senza elementi non c e`() {
        assertEquals(1, votoSessione(listOf(3.0)))
        assertNull(votoSessione(emptyList()))
    }

    @Test fun `le lettere di un suono si trovano vicino a dove cade`() {
        assertEquals(0..1, Grafema.trova("think", "θ", 0, 4))
        assertEquals(3..4, Grafema.trova("north", "θ", 2, 3))
        assertEquals(2..3, Grafema.trova("fish", "ʃ", 2, 3))
        assertNull(Grafema.trova("think", "ʒ", 0, 4))
    }

    @Test fun `le lettere si dicono all italiana`() {
        assertEquals("ti acca", Grafema.lettere("th"))
        assertEquals("esse acca", Grafema.lettere("SH"))
    }

    @Test fun `il fonema si legge senza segni di lunghezza`() {
        assertEquals("i", Grafema.base("iː"))
        assertEquals("ɔ", Grafema.base("ɔ:"))
    }
}
