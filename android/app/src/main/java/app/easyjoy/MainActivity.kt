package app.easyjoy

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF3DDC57)
private val Red = Color(0xFFE5484D)
private val Amber = Color(0xFFFFB454)
private val Bg = Color(0xFF0E0F12)
private val Panel = Color(0xFF1B1D23)
private val Muted = Color(0xFF9AA0AE)

private enum class Tab(val label: String, val icon: Int) {
    LAYOUTS("Layouts", R.drawable.ic_tab_layouts),
    PLAY("Jogar", R.drawable.ic_tab_pad),
    SETTINGS("Ajustes", R.drawable.ic_tab_settings),
}

/** Built-in layouts, in menu order. */
private val Bases = listOf(
    Triple(Layouts.FULL, "Controle Xbox com todos os botões", Color(0xFF107C10)),
    Triple(Layouts.SIMPLE, "Um analógico grande e botões grandes", Color(0xFF2F7CF6)),
    Triple(Layouts.WHEEL, "Gire o celular para dirigir", Color(0xFFC0392B)),
)

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3DB43D),
                    onPrimary = Color.Black,
                    background = Bg,
                    surface = Panel,
                )
            ) {
                var tab by rememberSaveable { mutableStateOf(Tab.PLAY) }
                var pcSheet by remember { mutableStateOf(false) }
                val openPcs = { pcSheet = true }

                Scaffold(
                    containerColor = Bg,
                    bottomBar = {
                        NavigationBar(containerColor = Panel) {
                            for (t in Tab.entries) {
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(painterResource(t.icon), contentDescription = null) },
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Green,
                                        selectedTextColor = Green,
                                        indicatorColor = Color(0xFF1E3A24),
                                        unselectedIconColor = Muted,
                                        unselectedTextColor = Muted,
                                    ),
                                )
                            }
                        }
                    },
                ) { padding ->
                    when (tab) {
                        Tab.PLAY -> PlayTab(
                            padding,
                            openPcs,
                            onPlay = { base, name -> launchPad(base, name) },
                            onMouse = { startActivity(Intent(this@MainActivity, TouchpadActivity::class.java)) },
                        )
                        Tab.LAYOUTS -> LayoutsTab(padding, openPcs, onEdit = { base, name -> launchPad(base, name, edit = true) })
                        Tab.SETTINGS -> SettingsTab(padding, openPcs)
                    }
                }

                if (pcSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { pcSheet = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        containerColor = Color(0xFF15171C),
                    ) {
                        Column(
                            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Text("Computador", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            PcPicker(onPicked = { pcSheet = false })
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
        }
    }

    private fun launchPad(mode: String, layout: String?, edit: Boolean = false) {
        startActivity(Intent(this, ControllerActivity::class.java).apply {
            putExtra(ControllerActivity.EXTRA_MODE, mode)
            if (layout != null) putExtra(ControllerActivity.EXTRA_LAYOUT, layout)
            if (edit) putExtra(ControllerActivity.EXTRA_EDIT, true)
        })
    }

    override fun onStart() {
        super.onStart()
        Connection.acquire(this)
    }

    override fun onStop() {
        Connection.release()
        super.onStop()
    }
}

// ---- shared pieces -------------------------------------------------------------------------

/** Page with a title and the connection chip, shown on every tab. */
@Composable
private fun TabPage(padding: PaddingValues, title: String, openPcs: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
            ConnectionChip(openPcs)
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/** "● MY-PC ▾": connection state at a glance; tap to pick or change the PC. */
@Composable
private fun ConnectionChip(onClick: () -> Unit) {
    val connected = Connection.connected
    val target = Connection.targetIp
    val (dot, label) = when {
        connected -> Green to (Connection.targetName() ?: target ?: "")
        target != null -> Amber to "Conectando…"
        else -> Red to "Sem PC"
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Panel).clickable(onClick = onClick)
            .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        Text("  ▾", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)

@Composable
private fun Hint(text: String) = Text(text, color = Muted, fontSize = 13.sp)

@Composable
private fun PanelCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

/** PC list + manual IP. Used in the connection sheet and on the Jogar tab while disconnected. */
@Composable
private fun PcPicker(onPicked: () -> Unit = {}) {
    val connected = Connection.connected
    val target = Connection.targetIp
    var manualIp by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf(false) }

    if (target != null) {
        PanelCard {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Green else Amber))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(Connection.targetName() ?: target, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(if (connected) "Conectado" else "Procurando…", color = Muted, fontSize = 13.sp)
                }
                TextButton(onClick = { Connection.disconnect() }) { Text("Desconectar", color = Color(0xFFFF8A8E)) }
            }
        }
        if (connected && Connection.driverMissing) {
            Text("Driver de controle não instalado no PC — o modo mouse funciona", color = Amber, fontSize = 14.sp)
        }
    }

    SectionTitle("PCs na rede")
    val pcs = Connection.discovered.filter { it.ip != target }
    if (pcs.isEmpty()) {
        Hint(if (target == null) "Procurando PCs na rede Wi-Fi… Abra o EasyJoy no PC." else "Nenhum outro PC encontrado.")
    }
    for (pc in pcs) {
        PanelCard(Modifier.clickable { Connection.connect(pc.ip, pc.name); onPicked() }) {
            Column(Modifier.padding(16.dp)) {
                Text(pc.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(pc.ip, color = Muted, fontSize = 13.sp)
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = manualIp,
            onValueChange = { manualIp = it.trim(); ipError = false },
            label = { Text("Ou digite o IP do PC") },
            placeholder = { Text("192.168.0.10") },
            isError = ipError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Button(onClick = {
            if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(manualIp) &&
                manualIp.split('.').all { it.toInt() in 0..255 }
            ) {
                Connection.connect(manualIp); onPicked()
            } else ipError = true
        }) { Text("Conectar") }
    }
    if (ipError) Text("IP inválido", color = Red, fontSize = 13.sp)
}

// ---- Jogar ---------------------------------------------------------------------------------

@Composable
private fun PlayTab(
    padding: PaddingValues,
    openPcs: () -> Unit,
    onPlay: (base: String, layout: String?) -> Unit,
    onMouse: () -> Unit,
) {
    TabPage(padding, "Jogar", openPcs) {
        if (!Connection.connected) {
            Hint("Conecte-se a um PC para começar. O celular e o PC precisam estar no mesmo Wi-Fi.")
            PcPicker()
            return@TabPage
        }
        if (Connection.driverMissing) {
            Text("Driver de controle não instalado no PC — o modo mouse funciona", color = Amber, fontSize = 14.sp)
        }

        for ((base, subtitle, color) in Bases) {
            ModeCard(Layouts.baseLabel(base), subtitle, color) { onPlay(base, null) }
        }
        ModeCard("Mouse", "Touchpad, rolagem e teclado", Color(0xFFB8860B), onMouse)

        SectionTitle("Meus layouts")
        val layouts = Layouts.saved
        if (layouts.isEmpty()) Hint("Você ainda não tem layouts. Crie o seu na aba Layouts.")
        for (l in layouts) {
            PanelCard(Modifier.clickable { onPlay(l.base, l.name) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(baseColor(l.base)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(l.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(layoutSubtitle(l), color = Muted, fontSize = 13.sp)
                    }
                    Text("▶", color = Green, fontSize = 18.sp)
                }
            }
        }
    }
}

private fun baseColor(base: String) = Bases.first { it.first == base }.third
private fun layoutSubtitle(l: Layout) = "Base: " + Layouts.baseLabel(l.base) + if (l.tilt) " · volante ligado" else ""

@Composable
private fun ModeCard(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(88.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, fontSize = 14.sp, color = Color(0xE6FFFFFF))
        }
    }
}

// ---- Layouts -------------------------------------------------------------------------------

@Composable
private fun LayoutsTab(padding: PaddingValues, openPcs: () -> Unit, onEdit: (base: String, name: String?) -> Unit) {
    var toDelete by remember { mutableStateOf<String?>(null) }

    TabPage(padding, "Layouts", openPcs) {
        Hint("Mude botões de lugar, tamanho, esconda o que não usa e ligue o volante. Funciona mesmo sem PC conectado.")

        SectionTitle("Meus layouts")
        val layouts = Layouts.saved
        if (layouts.isEmpty()) Hint("Nenhum ainda. Comece a partir de um modelo abaixo.")
        for (l in layouts) {
            PanelCard {
                Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(baseColor(l.base)))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(l.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(layoutSubtitle(l), color = Muted, fontSize = 13.sp)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { onEdit(l.base, l.name) }) { Text("Editar") }
                        TextButton(onClick = { Layouts.save(l.copy(name = copyName(l.name))) }) { Text("Duplicar") }
                        TextButton(onClick = { toDelete = l.name }) { Text("Apagar", color = Color(0xFFFF8A8E)) }
                    }
                }
            }
        }

        SectionTitle("Criar a partir de um modelo")
        for ((base, subtitle, color) in Bases) {
            PanelCard(Modifier.clickable { onEdit(base, null) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Layouts.baseLabel(base), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(subtitle, color = Muted, fontSize = 13.sp)
                    }
                    Text("Personalizar", color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Apagar layout?") },
            text = { Text("O layout \"$name\" será apagado. Isso não pode ser desfeito.") },
            confirmButton = { TextButton(onClick = { Layouts.delete(name); toDelete = null }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

/** "Nome (cópia)", "Nome (cópia 2)", … — a name not used yet. */
private fun copyName(name: String): String {
    val taken = Layouts.saved.map { it.name }.toSet()
    var candidate = "$name (cópia)"
    var n = 2
    while (candidate in taken) candidate = "$name (cópia ${n++})"
    return candidate
}

// ---- Ajustes -------------------------------------------------------------------------------

@Composable
private fun VolumePicker(label: String, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(Settings.VolumeTargets.first { it.id == current }.label + "  ▾", maxLines = 1)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (t in Settings.VolumeTargets) {
                    DropdownMenuItem(
                        text = { Text(t.label, color = if (t.id == current) Green else Color.Unspecified) },
                        onClick = { onPick(t.id); open = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(padding: PaddingValues, openPcs: () -> Unit) {
    TabPage(padding, "Ajustes", openPcs) {
        SectionTitle("Controle")
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vibração ao tocar", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = Settings.vibration, onCheckedChange = { Settings.updateVibration(it) })
                }
                Text("Tamanho dos botões: ${Math.round(Settings.buttonScale * 100)}%", color = Color.White, fontSize = 16.sp)
                Slider(
                    value = Settings.buttonScale,
                    onValueChange = { Settings.updateButtonScale(Math.round(it * 20f) / 20f) },
                    valueRange = Settings.MIN_SCALE..Settings.MAX_SCALE,
                    steps = 7,
                )
            }
        }

        SectionTitle("Giroscópio")
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Movimento para emuladores", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = Settings.motion, onCheckedChange = { Settings.updateMotion(it) })
                }
                Hint("Envia o giroscópio do celular para emuladores como Eden, Cemu e Dolphin (DSU). Só liga quando um emulador está ouvindo. Desligue para economizar bateria se não usa mira por movimento.")
            }
        }

        SectionTitle("Volante")
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Girar ${Math.round(Settings.tiltMax)}° = direção toda", color = Color.White, fontSize = 16.sp)
                Slider(
                    value = Settings.tiltMax,
                    onValueChange = { Settings.updateTiltMax((Math.round(it / 5f) * 5).toFloat()) },
                    valueRange = 15f..90f,
                    steps = 14,
                )
                Hint("Menor = mais sensível. Use o botão \"Centralizar\" no volante para definir a posição reta.")
            }
        }

        SectionTitle("Botões de volume")
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VolumePicker("Volume +", Settings.volumeUp, Settings::updateVolumeUp)
                VolumePicker("Volume −", Settings.volumeDown, Settings::updateVolumeDown)
                Hint("Nas telas de controle, os botões de volume do celular apertam o botão escolhido. Em \"Nenhum\", mudam o volume normalmente.")
            }
        }

        SectionTitle("Mouse")
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sensibilidade: ${"%.1f".format(Settings.sensitivity)}x", color = Color.White, fontSize = 16.sp)
                Slider(
                    value = Settings.sensitivity,
                    onValueChange = { Settings.updateSensitivity(Math.round(it * 10f) / 10f) },
                    valueRange = 0.5f..4f,
                )
            }
        }
    }
}
