package io.github.ttabouch12.dilmac

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ttabouch12.dilmac.LiveTranslator.Line

class MainActivity : ComponentActivity() {
    private lateinit var engine: LiveTranslator
    private val lines = mutableStateListOf<Line>()
    private var status by mutableStateOf("Tap the mic and start talking")
    private var listening by mutableStateOf(false)
    private var speakTurkish by mutableStateOf(true)
    private var speakEnglish by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = LiveTranslator(this, object : LiveTranslator.Listener {
            override fun onLine(line: Line) {
                val i = lines.indexOfFirst { it.id == line.id }
                if (i >= 0) lines[i] = line else lines.add(line)
            }

            override fun onStatus(text: String) {
                status = text
            }
        })
        val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) setListening(true) else status = "Dilmaç needs the microphone to hear the conversation"
        }
        setContent {
            DilmacTheme {
                ConversationScreen(
                    lines = lines,
                    status = status,
                    listening = listening,
                    speakTurkish = speakTurkish,
                    speakEnglish = speakEnglish,
                    onSpeakTurkish = { speakTurkish = it; engine.speakTurkish = it },
                    onSpeakEnglish = { speakEnglish = it; engine.speakEnglish = it },
                    onMic = {
                        when {
                            listening -> setListening(false)
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> setListening(true)
                            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onClear = { lines.clear() },
                    onLab = {
                        setListening(false)
                        startActivity(Intent(this, LabActivity::class.java))
                    },
                )
            }
        }
    }

    private fun setListening(on: Boolean) {
        listening = on
        if (on) {
            engine.start()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            engine.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onDestroy() {
        engine.destroy()
        super.onDestroy()
    }
}

@Composable
private fun DilmacTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    lines: List<Line>,
    status: String,
    listening: Boolean,
    speakTurkish: Boolean,
    speakEnglish: Boolean,
    onSpeakTurkish: (Boolean) -> Unit,
    onSpeakEnglish: (Boolean) -> Unit,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onLab: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Dilmaç", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClear, enabled = lines.isNotEmpty()) { Icon(Icons.Default.Delete, "Clear conversation") }
                },
                actions = { IconButton(onClick = onLab) { Icon(Icons.Default.Settings, "Engine lab") } },
            )
        },
        bottomBar = {
            Controls(status, listening, speakTurkish, speakEnglish, onSpeakTurkish, onSpeakEnglish, onMic)
        },
    ) { padding ->
        if (lines.isEmpty()) {
            EmptyHint(Modifier.padding(padding))
        } else {
            val list = rememberLazyListState()
            val last = lines.last()
            LaunchedEffect(lines.size, last.translation) { list.animateScrollToItem(lines.lastIndex) }
            LazyColumn(
                state = list,
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(lines, key = { it.id }) { Bubble(it) }
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🇹🇷  ⇄  🇬🇧", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(16.dp))
        Text("Tap the mic and talk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Turkish turns into English captions while they speak.\nYour English is spoken back in Turkish.\nWorks offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Bubble(line: Line) {
    val mine = line.mine
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(
            if (mine) "Me" else "Person 1",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Surface(
            color = if (mine) colors.primaryContainer else colors.surfaceContainerHigh,
            contentColor = if (mine) colors.onPrimaryContainer else colors.onSurface,
            shape = RoundedCornerShape(
                topStart = 22.dp, topEnd = 22.dp,
                bottomStart = if (mine) 22.dp else 6.dp,
                bottomEnd = if (mine) 6.dp else 22.dp,
            ),
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            Column(Modifier.animateContentSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Big text = what the reader needs: English for me, Turkish for them.
                Text(
                    line.translation + if (line.final) "" else " …",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.alpha(if (line.final) 1f else 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                Text(line.original, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(0.65f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    status: String,
    listening: Boolean,
    speakTurkish: Boolean,
    speakEnglish: Boolean,
    onSpeakTurkish: (Boolean) -> Unit,
    onSpeakEnglish: (Boolean) -> Unit,
    onMic: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = speakTurkish, onClick = { onSpeakTurkish(!speakTurkish) }, label = { Text("🔊 Say TR") })
                MicButton(listening, onMic)
                FilterChip(selected = speakEnglish, onClick = { onSpeakEnglish(!speakEnglish) }, label = { Text("🎧 Hear EN") })
            }
            Spacer(Modifier.height(10.dp))
            Text("Türkçe  ⇄  English", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MicButton(listening: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring",
    )
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
        if (listening) {
            Box(Modifier.size(84.dp).scale(pulse).clip(CircleShape).background(colors.primary.copy(alpha = 0.25f)))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(if (listening) colors.error else colors.primary)
                .clickable(onClick = onClick),
        ) {
            if (listening) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(colors.onError))
            } else {
                Icon(painterResource(R.drawable.ic_mic), "Start listening", tint = colors.onPrimary, modifier = Modifier.size(36.dp))
            }
        }
    }
}
