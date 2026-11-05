package com.aide.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aide.assistant.graph
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab(val label: String) {
    HOME("Home"), TASKS("My list"), RESEARCH("Research"), REVIEW("Review"), SETTINGS("Settings");

    val icon: ImageVector
        get() = when (this) {
            HOME -> Icons.Filled.Home
            TASKS -> Icons.Filled.CheckCircle
            RESEARCH -> Icons.Filled.Search
            REVIEW -> Icons.Filled.Star
            SETTINGS -> Icons.Filled.Settings
        }
}

/**
 * App shell: bottom navigation between the five tabs, plus a detail page on top
 * ("interaction:<id>" or "research:<id>") that notifications can open directly.
 */
@Composable
fun AideRoot(openRequests: MutableStateFlow<String?>) {
    val context = LocalContext.current
    val graph = context.graph
    val data by graph.store.state.collectAsStateWithLifecycle()
    val open by openRequests.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(if (graph.settings.isReady) Tab.HOME else Tab.SETTINGS) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var debrief by remember { mutableStateOf<Debrief?>(null) }

    LaunchedEffect(open) {
        val target = open ?: return@LaunchedEffect
        openRequests.value = null
        when {
            target.startsWith("interaction:") || target.startsWith("research:") -> detail = target
            target.startsWith("debrief:") -> { debrief = Debrief.parse(target); tab = Tab.HOME; detail = null }
            target == "review" -> { tab = Tab.REVIEW; detail = null }
            target == "settings" -> { tab = Tab.SETTINGS; detail = null }
            else -> { tab = Tab.HOME; detail = null }
        }
    }

    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null && tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        bottomBar = {
            if (detail == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val d = detail
            when {
                d != null && d.startsWith("interaction:") ->
                    InteractionScreen(d.removePrefix("interaction:"), data, onBack = { detail = null }, onOpen = { detail = it })
                d != null && d.startsWith("research:") ->
                    ResearchDetailScreen(d.removePrefix("research:"), data, onBack = { detail = null })
                d == "ask" -> AskScreen(data, onBack = { detail = null }, onOpen = { detail = it })
                else -> when (tab) {
                    Tab.HOME -> HomeScreen(
                        data = data,
                        debrief = debrief,
                        onDebriefDone = { debrief = null },
                        onOpen = { detail = it },
                        goSettings = { tab = Tab.SETTINGS },
                    )
                    Tab.TASKS -> TasksScreen(data, onOpen = { detail = it })
                    Tab.RESEARCH -> ResearchListScreen(data, onOpen = { detail = it })
                    Tab.REVIEW -> ReviewScreen(data)
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}
