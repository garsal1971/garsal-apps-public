package com.garsal.solstizio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import com.garsal.solstizio.ui.Home
import com.garsal.solstizio.ui.Oro2
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    /** «Oggi» si rilegge a ogni ritorno in primo piano: un'app lasciata aperta
     *  da ieri sera deve mostrare la casella di stamattina, non quella di ieri. */
    private val oggi = mutableStateOf(LocalDate.now(Luogo.ZONA))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Oro2)) {
                Home(oggi.value)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val ora = LocalDate.now(Luogo.ZONA)
        if (ora != oggi.value) oggi.value = ora
    }
}
