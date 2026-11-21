package app.easyjoy

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF3DDC57)
private val Red = Color(0xFFE5484D)
private val Bg = Color(0xFF0E0F12)
private val Panel = Color(0xFF1B1D23)
private val Muted = Color(0xFF9AA0AE)

private enum class Tab(val label: String, val icon: Int) {
    LAYOUTS("Layouts", R.drawable.ic_tab_layouts),
    CONTROLS("Controles", R.drawable.ic_tab_pad),
    SETTINGS("Ajustes", R.drawable.ic_tab_settings),
}

class MainActivity : ComponentActivity() {
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
                var tab by rememberSaveable { mutableStateOf(Tab.CONTROLS) }
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
                        Tab.LAYOUTS -> LayoutsTab(
                            padding,
                            onPlay = { l ->
                                if (Connection.connected) launchPad(l.base, l.name)
                                else Toast.makeText(this, "Conecte-se a um PC na aba Controles.", Toast.LENGTH_SHORT).show()
                            },
                            onEdit = { base, name -> launchPad(base, name, edit = true) },
                        )
                        Tab.CONTROLS -> ControlsTab(
                            padding,
                            onPad = { mode -> launchPad(mode, null) },
                            onMouse = { startActivity(Intent(this@MainActivity, TouchpadActivity::class.java)) },
                        )
                        Tab.SETTINGS -> SettingsTab(padding)
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

@Composable
private fun TabPage(padding: PaddingValues, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        content()
        Spacer(Modifier.height(8.dp))
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

// ---- Controles -----------------------------------------------------------------------------

@Composable
private fun ControlsTab(padding: PaddingValues, onPad: (mode: String) -> Unit, onMouse: () -> Unit) {
    val connected = Connection.connected
    val target = Connection.targetIp
    var manualIp by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf(false) }

    TabPage(padding, "EasyJoy") {
        // Connection status
        PanelCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(if (connected) Green else Red))
                    Spacer(Modifier.width(10.dp))
                    val name = Connection.targetName()
                    Text(
                        when {
                            connected -> "Conectado a ${name ?: target}"
                            target != null -> "Desconectado — procurando ${name ?: target}…"
                            else -> "Desconectado"
                        },
                        color = if (connected) Color(0xFFB5E8BE) else Color(0xFFFF8A8E),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (connected && Connection.driverMissing) {
                    Text("Driver de controle não instalado no PC — o modo mouse funciona", color = Color(0xFFFFB454), fontSize = 14.sp)
                }
                if (target != null) {
                    OutlinedButton(onClick = { Connection.disconnect() }) { Text("Trocar de PC") }
                }
            }
        }

        if (connected) {
            ModeCard("Completo", "Controle Xbox com todos os botões", Color(0xFF107C10)) { onPad(Layouts.FULL) }
            ModeCard("Simples", "Um analógico grande e botões grandes", Color(0xFF2F7CF6)) { onPad(Layouts.SIMPLE) }
            ModeCard("Volante", "Gire o celular para dirigir", Color(0xFFC0392B)) { onPad(Layouts.WHEEL) }
            ModeCard("Mouse", "Touchpad, rolagem e teclado", Color(0xFFB8860B), onMouse)
            Hint("Seus layouts salvos ficam na aba Layouts.")
            return@TabPage
        }

        // Not connected: pick a PC
        SectionTitle("PCs encontrados")
        val pcs = Connection.discovered
        if (pcs.isEmpty()) {
            Text("Procurando PCs na rede Wi-Fi… Abra o EasyJoy no PC.", color = Muted, fontSize = 14.sp)
        } else {
            for (pc in pcs) {
                PanelCard(Modifier.clickable { Connection.connect(pc.ip, pc.name) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(pc.ip, color = Muted, fontSize = 13.sp)
                        }
                        if (pc.ip == target) Text("conectando…", color = Green, fontSize = 13.sp)
                    }
                }
            }
        }

        SectionTitle("IP manual")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it.trim(); ipError = false },
                label = { Text("Endereço IP do PC") },
                placeholder = { Text("192.168.0.10") },
                isError = ipError,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                if (Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(manualIp) &&
                    manualIp.split('.').all { it.toInt() in 0..255 }
                ) Connection.connect(manualIp) else ipError = true
            }) { Text("Conectar") }
        }
        if (ipError) Text("IP inválido", color = Red, fontSize = 13.sp)
    }
}

@Composable
private fun ModeCard(title: String, subtitle: String, color: Color, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().height(96.dp).clickable(onClick = onClick),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text(subtitle, fontSize = 14.sp, color = Color(0xE6FFFFFF))
        }
    }
}

// ---- Layouts -------------------------------------------------------------------------------

@Composable
private fun LayoutsTab(
    padding: PaddingValues,
    onPlay: (Layout) -> Unit,
    onEdit: (base: String, name: String?) -> Unit,
) {
    var toDelete by remember { mutableStateOf<String?>(null) }

    TabPage(padding, "Layouts") {
        SectionTitle("Meus layouts")
        val layouts = Layouts.saved
        if (layouts.isEmpty()) {
            Hint("Nenhum layout salvo ainda. Crie um abaixo: arraste os botões, mude o tamanho e salve com um nome.")
        }
        for (l in layouts) {
            PanelCard(Modifier.clickable { onPlay(l) }) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(Layouts.baseLabel(l.base) + if (l.tilt) " · volante ligado" else "", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = { onEdit(l.base, l.name) }) { Text("Editar") }
                    TextButton(onClick = { toDelete = l.name }) { Text("Apagar", color = Color(0xFFFF8A8E)) }
                }
            }
        }
        if (layouts.isNotEmpty()) Hint("Toque num layout para jogar com ele.")

        SectionTitle("Criar layout")
        Hint("Escolha o ponto de partida:")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            for (base in listOf(Layouts.FULL, Layouts.SIMPLE, Layouts.WHEEL)) {
                OutlinedButton(onClick = { onEdit(base, null) }, modifier = Modifier.weight(1f)) {
                    Text(Layouts.baseLabel(base), maxLines = 1)
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

// ---- Ajustes -------------------------------------------------------------------------------

@Composable
private fun SettingsTab(padding: PaddingValues) {
    TabPage(padding, "Ajustes") {
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
