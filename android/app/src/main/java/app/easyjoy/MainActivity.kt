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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Green = Color(0xFF3DDC57)
private val Red = Color(0xFFE5484D)
private val Bg = Color(0xFF0E0F12)
private val Panel = Color(0xFF1B1D23)

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
                Box(Modifier.fillMaxSize().background(Bg).safeDrawingPadding()) {
                    Home(
                        onPad = { mode, layout -> launchPad(mode, layout) },
                        onMouse = { startActivity(Intent(this@MainActivity, TouchpadActivity::class.java)) },
                    )
                }
            }
        }
    }

    private fun launchPad(mode: String, layout: String?) {
        startActivity(Intent(this, ControllerActivity::class.java).apply {
            putExtra(ControllerActivity.EXTRA_MODE, mode)
            if (layout != null) putExtra(ControllerActivity.EXTRA_LAYOUT, layout)
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

@Composable
private fun Home(onPad: (mode: String, layout: String?) -> Unit, onMouse: () -> Unit) {
    val connected = Connection.connected
    val target = Connection.targetIp
    var manualIp by remember { mutableStateOf("") }
    var ipError by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("EasyJoy", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)

        // Connection status
        Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
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
                    )
                }
                if (connected && Connection.driverMissing) {
                    Text(
                        "Driver de controle não instalado no PC — o modo mouse funciona",
                        color = Color(0xFFFFB454),
                        fontSize = 14.sp,
                    )
                }
                if (target != null) {
                    OutlinedButton(onClick = { Connection.disconnect() }) { Text("Desconectar") }
                }
            }
        }

        if (connected) {
            ModeCard("Completo", "Controle Xbox com todos os botões", Color(0xFF107C10)) { onPad(Layouts.FULL, null) }
            ModeCard("Simples", "Um analógico grande e botões grandes", Color(0xFF2F7CF6)) { onPad(Layouts.SIMPLE, null) }
            ModeCard("Volante", "Gire o celular para dirigir", Color(0xFFC0392B)) { onPad(Layouts.WHEEL, null) }
            ModeCard("Mouse", "Touchpad, rolagem e teclado", Color(0xFFB8860B), onMouse)

            val layouts = Layouts.saved
            if (layouts.isNotEmpty()) {
                Text("Meus layouts", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                for (l in layouts) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Panel),
                        modifier = Modifier.fillMaxWidth().clickable { onPad(l.base, l.name) },
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    Layouts.baseLabel(l.base) + if (l.tilt) " · volante ligado" else "",
                                    color = Color(0xFF9AA0AE),
                                    fontSize = 13.sp,
                                )
                            }
                            TextButton(onClick = { toDelete = l.name }) { Text("Apagar", color = Color(0xFFFF8A8E)) }
                        }
                    }
                }
            } else {
                Text(
                    "Dica: dentro do controle, toque em \"Editar\" para mudar botões de lugar e salvar seu próprio layout.",
                    color = Color(0xFF9AA0AE),
                    fontSize = 13.sp,
                )
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

        // Discovered PCs
        Text("PCs encontrados", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        val pcs = Connection.discovered
        if (pcs.isEmpty()) {
            Text(
                "Procurando PCs na rede Wi-Fi… Abra o EasyJoy no PC.",
                color = Color(0xFF9AA0AE),
                fontSize = 14.sp,
            )
        } else {
            for (pc in pcs) {
                val sel = pc.ip == target
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    modifier = Modifier.fillMaxWidth().clickable { Connection.connect(pc.ip, pc.name) },
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pc.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                            Text(pc.ip, color = Color(0xFF9AA0AE), fontSize = 13.sp)
                        }
                        if (sel) Text(if (connected) "conectado" else "conectando…", color = Green, fontSize = 13.sp)
                    }
                }
            }
        }

        // Manual IP
        Text("IP manual", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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

        // Settings
        Text("Ajustes", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Card(colors = CardDefaults.cardColors(containerColor = Panel), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vibração ao tocar", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    Switch(checked = Settings.vibration, onCheckedChange = { Settings.updateVibration(it) })
                }
                Text(
                    "Sensibilidade do mouse: ${"%.1f".format(Settings.sensitivity)}x",
                    color = Color.White,
                    fontSize = 16.sp,
                )
                Slider(
                    value = Settings.sensitivity,
                    onValueChange = { Settings.updateSensitivity(Math.round(it * 10f) / 10f) },
                    valueRange = 0.5f..4f,
                )
                Text(
                    "Tamanho dos botões do controle: ${Math.round(Settings.buttonScale * 100)}%",
                    color = Color.White,
                    fontSize = 16.sp,
                )
                Slider(
                    value = Settings.buttonScale,
                    onValueChange = { Settings.updateButtonScale(Math.round(it * 20f) / 20f) },
                    valueRange = Settings.MIN_SCALE..Settings.MAX_SCALE,
                    steps = 7,
                )
                Text(
                    "Volante: girar ${Math.round(Settings.tiltMax)}° = direção toda",
                    color = Color.White,
                    fontSize = 16.sp,
                )
                Slider(
                    value = Settings.tiltMax,
                    onValueChange = { Settings.updateTiltMax((Math.round(it / 5f) * 5).toFloat()) },
                    valueRange = 15f..90f,
                    steps = 14,
                )
                Text(
                    "Menor = mais sensível. Use o botão \"Centralizar\" no volante para definir a posição reta.",
                    color = Color(0xFF9AA0AE),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
