package com.example.mp3tool

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

private val bitrates = listOf(64, 96, 128, 192, 256, 320)

/** "83", "1:23", "1:23.5" -> секунды. Пустая строка -> null. */
private fun parseTime(s: String): Double? {
    if (s.isBlank()) return null
    var total = 0.0
    for (p in s.trim().replace(',', '.').split(":")) total = total * 60 + (p.toDoubleOrNull() ?: return null)
    return total
}

private fun nameOf(ctx: Context, uri: Uri): String =
    ctx.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: uri.lastPathSegment ?: "файл"

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var files by remember { mutableStateOf(listOf<Uri>()) }
    var bitrate by remember { mutableStateOf(192) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        if (it != null) files = listOf(it)
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        if (it.isNotEmpty()) files = files + it
    }

    fun run(out: Uri) {
        busy = true
        status = "Обработка..."
        val tmp = File(ctx.cacheDir, "result.mp3").also { it.delete() }
        val ins = files.map { FFmpegKitConfig.getSafParameterForRead(ctx, it) }
        val args = mutableListOf("-y")
        when (tab) {
            0 -> args += listOf("-i", ins[0], "-vn", "-c:a", "libmp3lame", "-b:a", "${bitrate}k")
            1 -> {
                val s = parseTime(from) ?: 0.0
                val e = parseTime(to)
                args += listOf("-ss", s.toString())
                if (e != null) args += listOf("-t", (e - s).toString())
                args += listOf("-i", ins[0], "-vn", "-c:a", "libmp3lame", "-b:a", "${bitrate}k")
            }
            else -> {
                ins.forEach { args += listOf("-i", it) }
                // приводим все файлы к одному формату, иначе concat может упасть
                val norm = ins.indices.joinToString(";") {
                    "[$it:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo[a$it]"
                }
                val labels = ins.indices.joinToString("") { "[a$it]" }
                args += listOf(
                    "-filter_complex", "$norm;${labels}concat=n=${ins.size}:v=0:a=1[out]",
                    "-map", "[out]", "-c:a", "libmp3lame", "-b:a", "${bitrate}k"
                )
            }
        }
        args += tmp.absolutePath
        FFmpegKit.executeWithArgumentsAsync(args.toTypedArray()) { session ->
            status = if (ReturnCode.isSuccess(session.returnCode) && tmp.exists()) {
                try {
                    ctx.contentResolver.openOutputStream(out)!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    "Готово"
                } catch (e: Exception) { "Ошибка сохранения: ${e.message}" }
            } else "Ошибка обработки. Проверьте файлы и время."
            tmp.delete()
            busy = false
        }
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mpeg")) {
        if (it != null) run(it)
    }

    fun validate(): String? = when {
        files.isEmpty() -> "Выберите файл"
        tab == 2 && files.size < 2 -> "Для соединения нужно минимум 2 файла"
        tab == 1 && from.isNotBlank() && parseTime(from) == null -> "Неверное время «с»"
        tab == 1 && to.isNotBlank() && parseTime(to) == null -> "Неверное время «по»"
        tab == 1 && (parseTime(to) ?: Double.MAX_VALUE) <= (parseTime(from) ?: 0.0) -> "«по» должно быть больше «с»"
        else -> null
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TabRow(selectedTabIndex = tab) {
            listOf("Качество", "Обрезать", "Соединить").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i; files = emptyList(); status = "" }, text = { Text(t) })
            }
        }

        Button(onClick = {
            if (tab == 2) pickMany.launch(arrayOf("audio/*")) else pickOne.launch(arrayOf("audio/*"))
        }) { Text(if (tab == 2) "Добавить файлы" else "Выбрать файл") }

        files.forEachIndexed { i, u ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("${i + 1}. ${nameOf(ctx, u)}", Modifier.weight(1f))
                if (tab == 2) {
                    TextButton(enabled = i > 0, onClick = {
                        files = files.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }
                    }) { Text("↑") }
                    TextButton(enabled = i < files.lastIndex, onClick = {
                        files = files.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) }
                    }) { Text("↓") }
                    TextButton(onClick = { files = files.toMutableList().also { l -> l.removeAt(i) } }) { Text("✕") }
                }
            }
        }

        if (tab == 1) {
            OutlinedTextField(from, { from = it }, label = { Text("С (например 0:15)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(to, { to = it }, label = { Text("По (пусто = до конца)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth())
        }

        Text("Качество (кбит/с): $bitrate")
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            bitrates.forEach { b ->
                FilterChip(selected = bitrate == b, onClick = { bitrate = b }, label = { Text("$b") })
            }
        }

        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            val err = validate()
            if (err != null) status = err else saver.launch("result.mp3")
        }) { Text("Выполнить и сохранить") }

        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status)
    }
}
