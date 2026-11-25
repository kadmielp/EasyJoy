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

private enum class Tab(val icon: Int) {
    LAYOUTS(R.drawable.ic_tab_layouts),
    PLAY(R.drawable.ic_tab_pad),
    SETTINGS(R.drawable.ic_tab_settings);

    val label: String get() = when (this) {
        LAYOUTS -> tr("Layouts", "Layouts", "Diseños")
        PLAY -> tr("Jogar", "Play", "Jugar")
        SETTINGS -> tr("Ajustes", "Settings", "Ajustes")
    }
}

/** Built-in layouts, in menu order (a getter, so the subtitles follow the language). */
private val Bases get() = listOf(
    Triple(Layouts.FULL, tr("Controle Xbox com todos os botões", "Xbox controller with every button", "Control de Xbox con todos los botones"), Color(0xFF107C10)),
    Triple(Layouts.SIMPLE, tr("Um analógico grande e botões grandes", "One big stick and big buttons", "Un stick grande y botones grandes"), Color(0xFF2F7CF6)),
    Triple(Layouts.WHEEL, tr("Gire o celular para dirigir", "Turn the phone to steer", "Gira el teléfono para conducir"), Color(0xFFC0392B)),
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
                            Text(tr("Computador", "Computer", "Computadora"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
        target != null -> Amber to tr("Conectando…", "Connecting…", "Conectando…")
        else -> Red to tr("Sem PC", "No PC", "Sin PC")
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
                    Text(if (connected) tr("Conectado", "Connected", "Conectado") else tr("Procurando…", "Searching…", "Buscando…"), color = Muted, fontSize = 13.sp)
                }
                TextButton(onClick = { Connection.disconnect() }) { Text(tr("Desconectar", "Disconnect", "Desconectar"), color = Color(0xFFFF8A8E)) }
            }
        }
        if (connected && Connection.driverMissing) {
            Text(tr("Driver de controle não instalado no PC — o modo mouse funciona", "Controller driver not installed on the PC — mouse mode works", "Driver de control no instalado en el PC — el modo ratón funciona"), color = Amber, fontSize = 14.sp)
        }
    }

    SectionTitle(tr("PCs na rede", "PCs on the network", "PCs en la red"))
    val pcs = Connection.discovered.filter { it.ip != target }
    if (pcs.isEmpty()) {
        Hint(if (target == null) tr("Procurando PCs na rede Wi-Fi… Abra o EasyJoy no PC.", "Looking for PCs on the Wi-Fi… Open EasyJoy on the PC.", "Buscando PCs en la red Wi-Fi… Abre EasyJoy en el PC.")
            else tr("Nenhum outro PC encontrado.", "No other PC found.", "No se encontró otro PC."))
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
            label = { Text(tr("Ou digite o IP do PC", "Or type the PC's IP", "O escribe la IP del PC")) },
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
        }) { Text(tr("Conectar", "Connect", "Conectar")) }
    }
    if (ipError) Text(tr("IP inválido", "Invalid IP", "IP no válida"), color = Red, fontSize = 13.sp)
}

// ---- Jogar ---------------------------------------------------------------------------------

@Composable
private fun PlayTab(
    padding: PaddingValues,
    openPcs: () -> Unit,
    onPlay: (base: String, layout: String?) -> Unit,
    onMouse: () -> Unit,
) {
    TabPage(padding, Tab.PLAY.label, openPcs) {
        if (!Connection.connected) {
            Hint(tr("Conecte-se a um PC para começar. O celular e o PC precisam estar no mesmo Wi-Fi.", "Connect to a PC to start. The phone and the PC must be on the same Wi-Fi.", "Conéctate a un PC para empezar. El teléfono y el PC deben estar en la misma Wi-Fi."))
            PcPicker()
            return@TabPage
        }
        if (Connection.driverMissing) {
            Text(tr("Driver de controle não instalado no PC — o modo mouse funciona", "Controller driver not installed on the PC — mouse mode works", "Driver de control no instalado en el PC — el modo ratón funciona"), color = Amber, fontSize = 14.sp)
        }

        for ((base, subtitle, color) in Bases) {
            ModeCard(Layouts.baseLabel(base), subtitle, color) { onPlay(base, null) }
        }
        ModeCard(tr("Mouse", "Mouse", "Ratón"), tr("Touchpad, rolagem e teclado", "Touchpad, scrolling and keyboard", "Touchpad, desplazamiento y teclado"), Color(0xFFB8860B), onMouse)

        SectionTitle(tr("Meus layouts", "My layouts", "Mis diseños"))
        val layouts = Layouts.saved
        if (layouts.isEmpty()) Hint(tr("Você ainda não tem layouts. Crie o seu na aba Layouts.", "You have no layouts yet. Create yours in the Layouts tab.", "Aún no tienes diseños. Crea el tuyo en la pestaña Diseños."))
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
private fun layoutSubtitle(l: Layout) = "Base: " + Layouts.baseLabel(l.base) + if (l.tilt) tr(" · volante ligado", " · wheel on", " · volante activado") else ""

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

    TabPage(padding, Tab.LAYOUTS.label, openPcs) {
        Hint(tr("Mude botões de lugar, tamanho, esconda o que não usa e ligue o volante. Funciona mesmo sem PC conectado.", "Move and resize buttons, hide what you don't use and turn on the wheel. Works even with no PC connected.", "Mueve botones, cambia su tamaño, oculta lo que no usas y activa el volante. Funciona incluso sin PC conectado."))

        SectionTitle(tr("Meus layouts", "My layouts", "Mis diseños"))
        val layouts = Layouts.saved
        if (layouts.isEmpty()) Hint(tr("Nenhum ainda. Comece a partir de um modelo abaixo.", "None yet. Start from a template below.", "Ninguno aún. Empieza desde una plantilla abajo."))
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
                        TextButton(onClick = { onEdit(l.base, l.name) }) { Text(tr("Editar", "Edit", "Editar")) }
                        TextButton(onClick = { Layouts.save(l.copy(name = copyName(l.name))) }) { Text(tr("Duplicar", "Duplicate", "Duplicar")) }
                        TextButton(onClick = { toDelete = l.name }) { Text(tr("Apagar", "Delete", "Borrar"), color = Color(0xFFFF8A8E)) }
                    }
                }
            }
        }

        SectionTitle(tr("Criar a partir de um modelo", "Create from a template", "Crear desde una plantilla"))
        for ((base, subtitle, color) in Bases) {
            PanelCard(Modifier.clickable { onEdit(base, null) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(Layouts.baseLabel(base), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(subtitle, color = Muted, fontSize = 13.sp)
                    }
                    Text(tr("Personalizar", "Customize", "Personalizar"), color = Green, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(tr("Apagar layout?", "Delete layout?", "¿Borrar diseño?")) },
            text = { Text(tr("O layout \"$name\" será apagado. Isso não pode ser desfeito.", "The layout \"$name\" will be deleted. This can't be undone.", "El diseño \"$name\" se borrará. Esto no se puede deshacer.")) },
            confirmButton = { TextButton(onClick = { Layouts.delete(name); toDelete = null }) { Text(tr("Apagar", "Delete", "Borrar")) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(tr("Cancelar", "Cancel", "Cancelar")) } },
        )
    }
}

/** "Nome (cópia)", "Nome (cópia 2)", … — a name not used yet. */
private fun copyName(name: String): String {
    val taken = Layouts.saved.map { it.name }.toSet()
    val copy = tr("cópia", "copy", "copia")
    var candidate = "$name ($copy)"
    var n = 2
    while (candidate in taken) candidate = "$name ($copy ${n++})"
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
    TabPage(padding, Tab.SETTINGS.label, openPcs) {
        SectionTitle(tr("Controle", "Controller", "Control"))
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Vibração ao tocar", "Vibrate on touch", "Vibrar al tocar"), color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = Settings.vibration, onCheckedChange = { Settings.updateVibration(it) })
                }
                Text(tr("Tamanho dos botões", "Button size", "Tamaño de los botones") + ": ${Math.round(Settings.buttonScale * 100)}%", color = Color.White, fontSize = 16.sp)
                Slider(
                    value = Settings.buttonScale,
                    onValueChange = { Settings.updateButtonScale(Math.round(it * 20f) / 20f) },
                    valueRange = Settings.MIN_SCALE..Settings.MAX_SCALE,
                    steps = 7,
                )
            }
        }

        SectionTitle(tr("Giroscópio", "Gyroscope", "Giroscopio"))
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("Movimento para emuladores", "Motion for emulators", "Movimiento para emuladores"), color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = Settings.motion, onCheckedChange = { Settings.updateMotion(it) })
                }
                Hint(tr(
                    "Envia o giroscópio do celular para emuladores como Eden, Cemu e Dolphin (DSU). Só liga quando um emulador está ouvindo. Desligue para economizar bateria se não usa mira por movimento.",
                    "Sends the phone's gyroscope to emulators like Eden, Cemu and Dolphin (DSU). It only runs while an emulator is listening. Turn it off to save battery if you don't use motion aiming.",
                    "Envía el giroscopio del teléfono a emuladores como Eden, Cemu y Dolphin (DSU). Solo se activa cuando un emulador está escuchando. Desactívalo para ahorrar batería si no usas apuntado por movimiento.",
                ))
            }
        }

        SectionTitle(tr("Volante", "Wheel", "Volante"))
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Girar ${Math.round(Settings.tiltMax)}° = direção toda", "Turn ${Math.round(Settings.tiltMax)}° = full steering", "Girar ${Math.round(Settings.tiltMax)}° = giro completo"), color = Color.White, fontSize = 16.sp)
                Slider(
                    value = Settings.tiltMax,
                    onValueChange = { Settings.updateTiltMax((Math.round(it / 5f) * 5).toFloat()) },
                    valueRange = 15f..90f,
                    steps = 14,
                )
                Hint(tr(
                    "Menor = mais sensível. Use o botão \"Centralizar\" no volante para definir a posição reta.",
                    "Lower = more sensitive. Use the \"Center\" button on the wheel to set the straight position.",
                    "Menor = más sensible. Usa el botón \"Centrar\" en el volante para fijar la posición recta.",
                ))
            }
        }

        SectionTitle(tr("Botões de volume", "Volume buttons", "Botones de volumen"))
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VolumePicker(tr("Volume +", "Volume +", "Volumen +"), Settings.volumeUp, Settings::updateVolumeUp)
                VolumePicker(tr("Volume −", "Volume −", "Volumen −"), Settings.volumeDown, Settings::updateVolumeDown)
                Hint(tr(
                    "Nas telas de controle, os botões de volume do celular apertam o botão escolhido. Em \"Nenhum\", mudam o volume normalmente.",
                    "On controller screens, the phone's volume buttons press the chosen button. On \"None\", they change the volume as usual.",
                    "En las pantallas de control, los botones de volumen del teléfono pulsan el botón elegido. En \"Ninguno\", cambian el volumen normalmente.",
                ))
            }
        }

        SectionTitle(tr("Mouse", "Mouse", "Ratón"))
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Sensibilidade", "Sensitivity", "Sensibilidad") + ": ${"%.1f".format(Settings.sensitivity)}x", color = Color.White, fontSize = 16.sp)
                Slider(
                    value = Settings.sensitivity,
                    onValueChange = { Settings.updateSensitivity(Math.round(it * 10f) / 10f) },
                    valueRange = 0.5f..4f,
                )
            }
        }

        SectionTitle(tr("Idioma", "Language", "Idioma"))
        PanelCard {
            Column(Modifier.padding(16.dp)) {
                LanguagePicker()
            }
        }
    }
}

@Composable
private fun LanguagePicker() {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(I18n.choiceLabel(Settings.language) + "  ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in I18n.Choices) {
                DropdownMenuItem(
                    text = { Text(I18n.choiceLabel(c), color = if (c == Settings.language) Green else Color.Unspecified) },
                    onClick = { Settings.updateLanguage(c); open = false },
                )
            }
        }
    }
}
