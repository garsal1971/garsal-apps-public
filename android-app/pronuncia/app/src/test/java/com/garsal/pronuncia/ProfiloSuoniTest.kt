package com.garsal.pronuncia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Il profilo dei suoni deboli (`ProfiloSuoni`, v1.33.0). */
class ProfiloSuoniTest {

    @Test fun `i fonemi si scrivono e si rileggono uguali`() {
        val cod = ProfiloSuoni.codifica(listOf("θ" to 44.6, "ɪ" to 80.0, "ŋ" to 100.0, "k" to 0.0))
        assertEquals("θ=45 ɪ=80 ŋ=100 k=0", cod)
        assertEquals(listOf("θ" to 45, "ɪ" to 80, "ŋ" to 100, "k" to 0), ProfiloSuoni.decodifica(cod!!))
    }

    @Test fun `la lunghezza scritta coi due punti non rompe la riga`() {
        val cod = ProfiloSuoni.codifica(listOf("i:" to 70.0, "ɔː" to 55.0))!!
        assertEquals(listOf("i:" to 70, "ɔː" to 55), ProfiloSuoni.decodifica(cod))
    }

    @Test fun `senza fonemi non si salva niente`() {
        assertNull(ProfiloSuoni.codifica(emptyList()))
        assertNull(ProfiloSuoni.codifica(listOf("" to 50.0)))
    }

    @Test fun `una riga rovinata si salta invece di fermare tutto`() {
        assertEquals(listOf("θ" to 45), ProfiloSuoni.decodifica("θ=45 boh =3 x=y"))
        assertTrue(ProfiloSuoni.decodifica("").isEmpty())
    }

    @Test fun `il profilo ordina dal suono peggiore e unisce lungo e breve`() {
        val righe = listOf(
            "think" to "θ=40 ɪ=90 ŋ=85 k=95",
            "three" to "θ=50 r=80 iː=90",
            "see" to "s=95 i=70",
        )
        val p = ProfiloSuoni.calcola(righe)
        assertEquals("θ", p.first().fonema)
        assertEquals(45.0, p.first().media, 0.001)
        assertEquals(2, p.first().volte)
        assertEquals(2, p.first().sbagliate)
        assertEquals(listOf("think", "three"), p.first().esempi)
        val i = p.first { it.fonema == "i" }
        assertEquals(2, i.volte)
        assertEquals(80.0, i.media, 0.001)
        assertTrue(i.esempi.isEmpty())
    }

    @Test fun `debole e solo un suono valutato abbastanza volte e con la media bassa`() {
        val righe = (1..5).map { "think" to "θ=50 k=95" } + listOf("ship" to "ʃ=10")
        val deboli = ProfiloSuoni.deboli(ProfiloSuoni.calcola(righe))
        assertEquals(listOf("θ"), deboli.map { it.fonema })
    }

    @Test fun `i deboli sono al piu tre`() {
        val righe = (1..5).map { "x" to "a=10 b=20 c=30 d=40 e=50" }
        assertEquals(listOf("a", "b", "c"), ProfiloSuoni.deboli(ProfiloSuoni.calcola(righe)).map { it.fonema })
    }

    @Test fun `una parola contiene un suono per grafia o perche gia sentita`() {
        assertTrue(ProfiloSuoni.contiene("I think so.", "θ", emptySet()))
        assertFalse(ProfiloSuoni.contiene("I see.", "θ", emptySet()))
        assertTrue(ProfiloSuoni.contiene("The cat sat.", "æ", setOf("cat")))
        assertFalse(ProfiloSuoni.contiene("The dog ran.", "æ", setOf("cat")))
    }

    @Test fun `la acca conta solo a inizio parola`() {
        assertTrue(ProfiloSuoni.contiene("my house", "h", emptySet()))
        assertFalse(ProfiloSuoni.contiene("which", "h", emptySet()))
    }

    @Test fun `le parole sentite si raccolgono per suono`() {
        val m = ProfiloSuoni.paroleCon(listOf("cat" to "k=90 æ=40 t=90", "hat" to "h=80 æ=50 t=95"))
        assertEquals(setOf("cat", "hat"), m["æ"])
        assertEquals(setOf("hat"), m["h"])
    }
}
