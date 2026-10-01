package com.kinodaran.vast.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kinodaran.vast.core.VastVersion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var selected by rememberSaveable { mutableStateOf<String?>(null) }
                    val scenario = DemoCatalog.scenarios.firstOrNull { it.id == selected }
                    if (scenario == null) {
                        ScenarioList { selected = it.id }
                    } else {
                        BackHandler { selected = null }
                        PlayerScreen(scenario, onClose = { selected = null })
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioList(onSelect: (DemoScenario) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        item {
            Text("VastSDK ${VastVersion.CURRENT}", Modifier.padding(16.dp), style = MaterialTheme.typography.headlineSmall)
        }
        items(DemoCatalog.scenarios, key = { it.id }) { scenario ->
            Column(Modifier.clickable { onSelect(scenario) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(scenario.title, style = MaterialTheme.typography.titleMedium)
                Text(scenario.detail, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        item {
            // Must read zero once no player screen is open: a session or player
            // left alive by a closed screen is a leak, and this is where it shows.
            Text("Live player screens: ${LiveScreens.count}", Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium)
        }
    }
}
