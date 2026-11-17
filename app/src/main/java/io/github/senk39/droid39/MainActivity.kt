package io.github.senk39.droid39

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("39droid") }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
