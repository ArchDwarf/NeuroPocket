package com.neuropocket.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--selftest") {
        val report = File(args.getOrNull(1) ?: File(System.getProperty("java.io.tmpdir"), "neuropocket-selftest.txt").path)
        exitProcess(SelfTest.run(report))
    }
    runApp()
}

private fun runApp() = application {
    val windowState = rememberWindowState(size = DpSize(1360.dp, 900.dp))
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    Window(
        onCloseRequest = {
            state.shutdown()
            exitApplication()
        },
        title = "NeuroPocket",
        state = windowState,
        icon = painterResource("icon.png"),
    ) {
        AppTheme { App(state) }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8AB4F8), secondaryContainer = Color(0xFF2D3A5C),
            surface = Color(0xFF15171C), background = Color(0xFF111317),
        )
    } else {
        lightColorScheme(primary = Color(0xFF3B5BDB), secondaryContainer = Color(0xFFDDE4FF))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun App(state: AppState) {
    Scaffold(snackbarHost = { SnackbarHost(state.snackbar) }) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            NavigationRail(Modifier.fillMaxHeight()) {
                Text(
                    "NeuroPocket", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 12.dp),
                )
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = state.tab == t,
                        onClick = { state.tab = t },
                        icon = { Text(t.icon, fontSize = 20.sp) },
                        label = { Text(t.title) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.downloads.isNotEmpty()) {
                    Text(
                        "⬇ ${state.downloads.size}", color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
            VerticalDivider()
            Surface(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    when (state.tab) {
                        Tab.CHAT -> ChatPane(state)
                        Tab.IMAGES -> ImagePane(state, state.images)
                        Tab.VIDEO -> ImagePane(state, state.video)
                        Tab.SPEECH -> SpeechPane(state)
                        Tab.MODELS -> ModelsPane(state)
                        Tab.SETTINGS -> SettingsPane(state)
                    }
                }
            }
        }
    }
}
