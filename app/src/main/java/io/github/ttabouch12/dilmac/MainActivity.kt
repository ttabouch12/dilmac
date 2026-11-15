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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.ttabouch12.dilmac.LiveTranslator.Line
import io.github.ttabouch12.dilmac.LiveTranslator.Mode
import kotlinx.coroutines.delay
import java.util.Locale

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
        val headsetMic: Boolean = true,
        val mode: Mode = Mode.CONTINUOUS,
        val voiceForThem: String? = null,
        val voiceForMe: String? = null,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        engine = LiveTranslator(this, object : LiveTranslator.Listener {
            override fun onLine(line: Line) {
                val i = lines.indexOfFirst { it.id == line.id }
                if (i >= 0) lines[i] = line else lines.add(line)
            }

            override fun onRemove(id: Long) {
                lines.removeAll { it.id == id }
            }

            override fun onStatus(text: String) {
                status = text
            }
        })
        load()
        Languages.load(this) { found ->
            languages.clear()
            languages.addAll(found)
            update(settings.copy( // refresh the "installed" flags of the saved choices
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
                    level = { engine.level() },
                    voices = { lang -> engine.voices(lang) },
                    onPreview = { name, forThem -> engine.preview(name, forThem) },
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
        engine.voiceForThem = s.voiceForThem
        engine.voiceForMe = s.voiceForMe
        if (engine.headsetMic != s.headsetMic) {
            engine.headsetMic = s.headsetMic
            if (listening) { engine.stop(); engine.start() }
        }
        engine.preferredMode = s.mode
        engine.setLanguages(s.me, s.them)
        getSharedPreferences("settings", MODE_PRIVATE).edit()
            .putString("me", "${s.me.code}|${s.me.tag}|${s.me.name}")
            .putString("them", "${s.them.code}|${s.them.tag}|${s.them.name}")
            .putBoolean("speakToThem", s.speakToThem)
            .putBoolean("speakToMe", s.speakToMe)
            .putBoolean("identifySpeakers", s.identifySpeakers)
            .putBoolean("headsetMic", s.headsetMic)
            .putString("mode", s.mode.name)
            .putString("voiceForThem", s.voiceForThem)
            .putString("voiceForMe", s.voiceForMe)
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
            headsetMic = p.getBoolean("headsetMic", true),
            mode = runCatching { Mode.valueOf(p.getString("mode", null)!!) }.getOrDefault(Mode.CONTINUOUS)
                .let { if (it == Mode.BASIC) Mode.CONTINUOUS else it },
            voiceForThem = p.getString("voiceForThem", null),
            voiceForMe = p.getString("voiceForMe", null),
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

// ── screen ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationScreen(
    lines: List<Line>,
    languages: List<Lang>,
    status: String,
    listening: Boolean,
    settings: MainActivity.Settings,
    level: () -> Float,
    voices: (Lang) -> List<LiveTranslator.VoiceOption>,
    onPreview: (String?, Boolean) -> Unit,
    onSettings: (MainActivity.Settings) -> Unit,
    onMic: () -> Unit,
    onClear: () -> Unit,
    onLab: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) } // "me" or "them"
    val c = MaterialTheme.colorScheme

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(c.primaryContainer.copy(alpha = 0.55f), c.surface, c.surface)))
    ) {
        Column(Modifier.fillMaxSize()) {
            Header(status, listening, lines.isNotEmpty(), onClear, onSettings = { showSheet = true })
            LanguageCard(settings, onPick = { picking = it }, onSwap = { onSettings(settings.copy(me = settings.them, them = settings.me)) })
            Box(Modifier.weight(1f)) {
                if (lines.isEmpty()) EmptyHint(settings) else Conversation(lines)
            }
            Dock(listening, settings, level, onSettings, onMic)
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            SettingsSheet(settings, voices, onPreview, onSettings, onLab = { showSheet = false; onLab() })
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
private fun Header(status: String, listening: Boolean, hasLines: Boolean, onClear: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Dilmaç", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            StatusPill(status, listening)
        }
        IconButton(onClick = onClear, enabled = hasLines) { Icon(Icons.Default.Delete, "Clear conversation") }
        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
    }
}

@Composable
private fun StatusPill(status: String, listening: Boolean) {
    val c = MaterialTheme.colorScheme
    val dot by rememberInfiniteTransition(label = "dot").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp).animateContentSize()) {
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (listening) c.primary.copy(alpha = dot) else c.outline)
        )
        Spacer(Modifier.width(8.dp))
        Text(status, style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, maxLines = 1)
    }
}

/** "They speak  ⇄  I speak" with flags, tappable, and a swap button that spins. */
@Composable
private fun LanguageCard(settings: MainActivity.Settings, onPick: (String) -> Unit, onSwap: () -> Unit) {
    var turns by remember { mutableFloatStateOf(0f) }
    val spin by animateFloatAsState(turns, spring(dampingRatio = 0.55f), label = "swap")
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), shape = RoundedCornerShape(28.dp)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LangSide("They speak", settings.them, Modifier.weight(1f)) { onPick("them") }
            FilledIconButton(
                onClick = { turns += 180f; onSwap() },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
            ) {
                Icon(Icons.Default.Refresh, "Swap languages", modifier = Modifier.rotate(spin), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            LangSide("I speak", settings.me, Modifier.weight(1f)) { onPick("me") }
        }
    }
}

@Composable
private fun LangSide(caption: String, lang: Lang, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("${flag(lang.tag)}  ${lang.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun flag(tag: String): String {
    val region = Locale.forLanguageTag(tag).country.takeIf { it.length == 2 } ?: return "🌐"
    return region.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}

@Composable
private fun EmptyHint(settings: MainActivity.Settings) {
    val c = MaterialTheme.colorScheme
    val float by rememberInfiniteTransition(label = "float").animateFloat(
        -6f, 6f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y",
    )
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.padding(top = (12 + float).dp).size(112.dp).clip(RoundedCornerShape(36.dp))
                .background(Brush.linearGradient(listOf(c.primary, c.tertiary))),
            contentAlignment = Alignment.Center,
        ) {
            Text("💬", style = MaterialTheme.typography.displayMedium)
        }
        Spacer(Modifier.height(24.dp))
        Text("Ready when you are", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap the mic. ${settings.them.name} turns into ${settings.me.name} captions as they talk, " +
                "and your words are spoken back in ${settings.them.name}.",
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Conversation(lines: List<Line>) {
    val list = rememberLazyListState()
    val last = lines.last()
    LaunchedEffect(lines.size, last.translation) { list.animateScrollToItem(lines.lastIndex) }
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(lines, key = { it.id }) { Bubble(it, Modifier.animateItem()) }
    }
}

/** Colours per speaker, all from the Material You palette. */
@Composable
private fun speakerColors(speaker: Int): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when {
        speaker == LiveTranslator.ME -> c.primary to c.onPrimary
        speaker <= 0 -> c.surfaceContainerHighest to c.onSurface
        else -> listOf(
            c.secondaryContainer to c.onSecondaryContainer,
            c.tertiaryContainer to c.onTertiaryContainer,
            c.errorContainer to c.onErrorContainer,
            c.inversePrimary to c.onSurface,
        )[(speaker - 1) % 4]
    }
}

@Composable
private fun Bubble(line: Line, modifier: Modifier) {
    val mine = line.mine
    val (bg, fg) = speakerColors(line.speaker)
    val color by animateColorAsState(bg, label = "bubble")
    val label = when {
        mine -> "Me"
        line.speaker == LiveTranslator.UNKNOWN -> "…"
        else -> "P${line.speaker}"
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine) Avatar(label, color, fg)
        Spacer(Modifier.width(8.dp))
        Surface(
            color = color,
            contentColor = fg,
            shape = RoundedCornerShape(
                topStart = 24.dp, topEnd = 24.dp,
                bottomStart = if (mine) 24.dp else 6.dp,
                bottomEnd = if (mine) 6.dp else 24.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.animateContentSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (!mine && line.speaker > 0) {
                    Text("Person ${line.speaker}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                }
                // Big text = what the reader needs: my language for me, theirs for them.
                Text(
                    line.translation,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.alpha(if (line.final) 1f else 0.75f),
                )
                Spacer(Modifier.height(4.dp))
                Text(line.original, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(0.7f))
                if (!line.final) TypingDots()
            }
        }
        Spacer(Modifier.width(8.dp))
        if (mine) Avatar(label, color, fg)
    }
}

@Composable
private fun Avatar(label: String, bg: Color, fg: Color) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TypingDots() {
    val t = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.2f, 1f,
                infiniteRepeatable(tween(500, delayMillis = i * 150, easing = LinearEasing), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(6.dp).clip(CircleShape).background(LocalContentColor.current.copy(alpha = a)))
        }
    }
}

// ── bottom dock ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dock(
    listening: Boolean,
    settings: MainActivity.Settings,
    level: () -> Float,
    onSettings: (MainActivity.Settings) -> Unit,
    onMic: () -> Unit,
) {
    Surface(tonalElevation = 4.dp, shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModeSwitch(settings.mode) { onSettings(settings.copy(mode = it)) }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                DockToggle("🔊", "Say aloud", settings.speakToThem) { onSettings(settings.copy(speakToThem = it)) }
                MicButton(listening, level, onMic)
                DockToggle("🎧", "Hear them", settings.speakToMe) { onSettings(settings.copy(speakToMe = it)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSwitch(mode: Mode, onPick: (Mode) -> Unit) {
    val options = listOf(Mode.CONTINUOUS to "Continuous", Mode.SENTENCE to "Sentence by sentence")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                onClick = { onPick(m) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DockToggle(emoji: String, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(
        selected = on,
        onClick = { onChange(!on) },
        label = { Text("$emoji $label") },
        shape = RoundedCornerShape(16.dp),
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer),
    )
}

/** The mic: a gradient blob that rounds into a softer shape, spins slowly and pulses with your voice while listening. */
@Composable
private fun MicButton(listening: Boolean, level: () -> Float, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var loud by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(listening) {
        while (listening) {
            loud = loud * 0.6f + level() * 0.4f // smooth it out so the ring breathes, not flickers
            delay(50)
        }
        loud = 0f
    }
    val corner by animateDpAsState(if (listening) 30.dp else 44.dp, spring(dampingRatio = 0.5f), label = "corner")
    val spin by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "angle",
    )
    val ring by animateFloatAsState(1f + loud * 0.45f, spring(stiffness = 300f), label = "ring")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        if (listening) {
            Box(
                Modifier.size(88.dp).scale(ring).drawBehind {
                    drawCircle(Brush.radialGradient(listOf(c.tertiary.copy(alpha = 0.45f), Color.Transparent)))
                }
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(88.dp)
                .rotate(if (listening) spin else 0f)
                .clip(RoundedCornerShape(corner))
                .background(
                    if (listening) Brush.linearGradient(listOf(c.error, c.tertiary))
                    else Brush.linearGradient(listOf(c.primary, c.tertiary))
                )
                .clickable(onClick = onClick),
        ) {
            Box(Modifier.rotate(if (listening) -spin else 0f)) {
                if (listening) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(c.onError))
                } else {
                    Icon(painterResource(R.drawable.ic_mic), "Start listening", tint = c.onPrimary, modifier = Modifier.size(38.dp))
                }
            }
        }
    }
}

// ── settings ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsSheet(
    settings: MainActivity.Settings,
    voices: (Lang) -> List<LiveTranslator.VoiceOption>,
    onPreview: (String?, Boolean) -> Unit,
    onSettings: (MainActivity.Settings) -> Unit,
    onLab: () -> Unit,
) {
    var pickVoiceFor by remember { mutableStateOf<String?>(null) } // "them" or "me"
    Column(Modifier.padding(bottom = 24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Section("Listening")
        SwitchRow("Headset mic for me", "You on your Bluetooth headset, them on the phone: both translated even when talking at once. Headset audio becomes call-quality.", settings.headsetMic) {
            onSettings(settings.copy(headsetMic = it))
        }
        SwitchRow("Tell people apart", "Recognise voices as Person 1, 2, 3… (offline)", settings.identifySpeakers) {
            onSettings(settings.copy(identifySpeakers = it))
        }
        Section("Voices")
        ListItem(
            headlineContent = { Text("Voice they hear (${settings.them.name})") },
            supportingContent = { Text(voiceLabel(settings.voiceForThem, voices(settings.them))) },
            modifier = Modifier.clickable { pickVoiceFor = "them" },
        )
        ListItem(
            headlineContent = { Text("Voice I hear (${settings.me.name})") },
            supportingContent = { Text(voiceLabel(settings.voiceForMe, voices(settings.me)) + " · Person 2, 3… get other voices") },
            modifier = Modifier.clickable { pickVoiceFor = "me" },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        ListItem(
            headlineContent = { Text("Health check") },
            supportingContent = { Text("Check that everything works on this phone, and fix what doesn't") },
            modifier = Modifier.clickable(onClick = onLab),
        )
    }

    pickVoiceFor?.let { who ->
        val forThem = who == "them"
        val lang = if (forThem) settings.them else settings.me
        VoicePicker(
            title = if (forThem) "Voice they hear" else "Voice I hear",
            options = voices(lang),
            selected = if (forThem) settings.voiceForThem else settings.voiceForMe,
            onPreview = { onPreview(it, forThem) },
            onPick = {
                onSettings(if (forThem) settings.copy(voiceForThem = it) else settings.copy(voiceForMe = it))
                pickVoiceFor = null
            },
            onDismiss = { pickVoiceFor = null },
        )
    }
}

private fun voiceLabel(name: String?, options: List<LiveTranslator.VoiceOption>) =
    options.firstOrNull { it.name == name }?.let { it.label + if (it.online) " · HD online" else "" } ?: "Phone default"

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 4.dp),
    )
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
private fun VoicePicker(
    title: String,
    options: List<LiveTranslator.VoiceOption>,
    selected: String?,
    onPreview: (String?) -> Unit,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                item {
                    VoiceRow("Phone default", null, selected == null, { onPreview(null) }) { onPick(null) }
                }
                items(options, key = { it.name }) { v ->
                    VoiceRow(v.label, if (v.online) "HD · natural · needs internet" else "Offline", selected == v.name, { onPreview(v.name) }) { onPick(v.name) }
                }
                if (options.isEmpty()) item { Text("No extra voices found. Install more in Android Settings → Text-to-speech → Google → Install voice data.") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun VoiceRow(label: String, detail: String?, selected: Boolean, onPreview: () -> Unit, onPick: () -> Unit) {
    val sub: (@Composable () -> Unit)? = detail?.let { { Text(it) } }
    val tick: (@Composable () -> Unit)? = if (selected) { { Icon(Icons.Default.Check, "Selected") } } else null
    ListItem(
        leadingContent = { IconButton(onClick = onPreview) { Icon(Icons.Default.PlayArrow, "Preview $label") } },
        headlineContent = { Text(label) },
        supportingContent = sub,
        trailingContent = tick,
        modifier = Modifier.clickable(onClick = onPick),
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
                        leadingContent = { Text(flag(lang.tag), style = MaterialTheme.typography.titleLarge) },
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
