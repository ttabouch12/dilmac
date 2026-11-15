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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ttabouch12.dilmac.LiveTranslator.Line

class MainActivity : ComponentActivity() {
    private lateinit var engine: LiveTranslator
    private val lines = mutableStateListOf<Line>()
    private val languages = mutableStateListOf(Languages.ENGLISH, Languages.TURKISH)
    private var status by mutableStateOf("Tap the mic and start talking")
    private var listening by mutableStateOf(false)
    private var settings by mutableStateOf(Settings())

    /** Everything the user can change; saved between launches. */
    data class Settings(
        val me: Lang = Languages.ENGLISH,
        val them: Lang = Languages.TURKISH,
        val speakToThem: Boolean = true,
        val speakToMe: Boolean = false,
        val identifySpeakers: Boolean = true,
    )

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
        load()
        Languages.load(this) { found ->
            languages.clear()
            languages.addAll(found)
            // Refresh the "installed" flags of the saved choices.
            update(settings.copy(
                me = found.firstOrNull { it.code == settings.me.code } ?: settings.me,
                them = found.firstOrNull { it.code == settings.them.code } ?: settings.them,
            ))
        }
        val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) switchListening(true) else status = "Dilmaç needs the microphone to hear the conversation"
        }
        setContent {
            DilmacTheme {
                ConversationScreen(
                    lines = lines,
                    languages = languages,
                    status = status,
                    listening = listening,
                    settings = settings,
                    onSettings = ::update,
                    onMic = {
                        when {
                            listening -> switchListening(false)
                            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> switchListening(true)
                            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onClear = { lines.clear(); engine.resetSpeakers() },
                    onLab = {
                        switchListening(false)
                        startActivity(Intent(this, LabActivity::class.java))
                    },
                )
            }
        }
    }

    private fun update(s: Settings) {
        settings = s
        engine.speakToThem = s.speakToThem
        engine.speakToMe = s.speakToMe
        engine.identifySpeakers = s.identifySpeakers
        engine.setLanguages(s.me, s.them)
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("me", "${s.me.code}|${s.me.tag}|${s.me.name}")
            .putString("them", "${s.them.code}|${s.them.tag}|${s.them.name}")
            .putBoolean("speakToThem", s.speakToThem)
            .putBoolean("speakToMe", s.speakToMe)
            .putBoolean("identifySpeakers", s.identifySpeakers)
            .apply()
    }

    private fun load() {
        val p = getSharedPreferences("settings", MODE_PRIVATE)
        fun lang(key: String, default: Lang) = p.getString(key, null)?.split("|")
            ?.takeIf { it.size == 3 }?.let { Lang(it[0], it[1], it[2], installed = true) } ?: default
        update(Settings(
            me = lang("me", Languages.ENGLISH),
            them = lang("them", Languages.TURKISH),
            speakToThem = p.getBoolean("speakToThem", true),
            speakToMe = p.getBoolean("speakToMe", false),
            identifySpeakers = p.getBoolean("identifySpeakers", true),
        ))
    }

    private fun switchListening(on: Boolean) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    lines: List<Line>,
    languages: List<Lang>,
    status: String,
    listening: Boolean,
    settings: MainActivity.Settings,
    onSettings: (MainActivity.Settings) -> Unit,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onLab: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) } // "me" or "them"

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Dilmaç", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClear, enabled = lines.isNotEmpty()) { Icon(Icons.Default.Delete, "Clear conversation") }
                },
                actions = { IconButton(onClick = { showSheet = true }) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        bottomBar = {
            Controls(status, listening, settings, onSettings, onMic, onPick = { picking = it })
        },
    ) { padding ->
        if (lines.isEmpty()) {
            EmptyHint(settings, Modifier.padding(padding))
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

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            SettingsSheet(settings, onSettings, onLab = { showSheet = false; onLab() })
        }
    }

    picking?.let { who ->
        LanguagePicker(
            title = if (who == "me") "I speak" else "They speak",
            languages = languages,
            selected = if (who == "me") settings.me else settings.them,
            onPick = { lang ->
                picking = null
                val s = if (who == "me") settings.copy(me = lang) else settings.copy(them = lang)
                // Picking the other side's language swaps the two.
                onSettings(
                    when {
                        s.me.code != s.them.code -> s
                        who == "me" -> s.copy(them = settings.me)
                        else -> s.copy(me = settings.them)
                    }
                )
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun EmptyHint(settings: MainActivity.Settings, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(96.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_mic), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Tap the mic and talk", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "${settings.them.name} becomes ${settings.me.name} captions while they speak.\n" +
                "Your ${settings.me.name} is spoken back in ${settings.them.name}.\n" +
                "Each person gets their own colour. Works offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Colours per speaker, all from the Material You palette. */
@Composable
private fun speakerColors(speaker: Int): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when {
        speaker == LiveTranslator.ME -> c.primaryContainer to c.onPrimaryContainer
        speaker <= 0 -> c.surfaceContainerHigh to c.onSurface
        else -> listOf(
            c.secondaryContainer to c.onSecondaryContainer,
            c.tertiaryContainer to c.onTertiaryContainer,
            c.surfaceContainerHighest to c.onSurface,
            c.inversePrimary to c.onSurface,
        )[(speaker - 1) % 4]
    }
}

@Composable
private fun Bubble(line: Line) {
    val mine = line.mine
    val (bg, fg) = speakerColors(line.speaker)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(
            when {
                mine -> "Me"
                line.speaker == LiveTranslator.UNKNOWN -> "Someone"
                else -> "Person ${line.speaker}"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
        Surface(
            color = bg,
            contentColor = fg,
            shape = RoundedCornerShape(
                topStart = 22.dp, topEnd = 22.dp,
                bottomStart = if (mine) 22.dp else 6.dp,
                bottomEnd = if (mine) 6.dp else 22.dp,
            ),
            modifier = Modifier.widthIn(max = 330.dp),
        ) {
            Column(Modifier.animateContentSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                // Big text = what the reader needs: my language for me, theirs for them.
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
    settings: MainActivity.Settings,
    onSettings: (MainActivity.Settings) -> Unit,
    onMic: () -> Unit,
    onPick: (String) -> Unit,
) {
    Surface(tonalElevation = 3.dp, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { onPick("them") }) { Text(settings.them.name) }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward, null,
                    modifier = Modifier.padding(horizontal = 8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(onClick = { onPick("me") }) { Text(settings.me.name) }
            }
            Spacer(Modifier.height(6.dp))
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = settings.speakToThem,
                    onClick = { onSettings(settings.copy(speakToThem = !settings.speakToThem)) },
                    label = { Text("🔊 Say aloud") },
                )
                MicButton(listening, onMic)
                FilterChip(
                    selected = settings.speakToMe,
                    onClick = { onSettings(settings.copy(speakToMe = !settings.speakToMe)) },
                    label = { Text("🎧 Hear them") },
                )
            }
        }
    }
}

@Composable
private fun SettingsSheet(settings: MainActivity.Settings, onSettings: (MainActivity.Settings) -> Unit, onLab: () -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        SwitchRow(
            "Say my words aloud",
            "Your translated words play from the phone speaker for the other person",
            settings.speakToThem,
        ) { onSettings(settings.copy(speakToThem = it)) }
        SwitchRow(
            "Hear them in my headphones",
            "Each person is read to you in a different voice",
            settings.speakToMe,
        ) { onSettings(settings.copy(speakToMe = it)) }
        SwitchRow(
            "Tell people apart",
            "Recognise voices as Person 1, 2, 3… (offline)",
            settings.identifySpeakers,
        ) { onSettings(settings.copy(identifySpeakers = it)) }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Engine lab") },
            supportingContent = { Text("Speed tests for every engine on this phone") },
            modifier = Modifier.clickable(onClick = onLab),
        )
    }
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun LanguagePicker(title: String, languages: List<Lang>, selected: Lang, onPick: (Lang) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(languages, key = { it.code }) { lang ->
                    val note: (@Composable () -> Unit)? = if (lang.installed) null else { { Text("Downloads on first use") } }
                    val tick: (@Composable () -> Unit)? = if (lang.code != selected.code) null else { { Icon(Icons.Default.Check, "Selected") } }
                    ListItem(
                        headlineContent = { Text(lang.name) },
                        supportingContent = note,
                        trailingContent = tick,
                        modifier = Modifier.clickable { onPick(lang) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
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
                .clip(RoundedCornerShape(if (listening) 24.dp else 40.dp))
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
