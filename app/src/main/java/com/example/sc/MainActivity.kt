package com.example.sc

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

private const val CONTROL_PORT = 55555
private const val FILE_PORT = 55556
private const val SERVICE_TYPE = "_soundsync._udp."
private const val SERVICE_NAME = "SoundSyncMaster"
private const val TAG = "SoundSync"

data class FoundMaster(val name: String, val host: String)

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val nsdManager = remember { context.getSystemService(Context.NSD_SERVICE) as NsdManager }
    val scope = rememberCoroutineScope()

    var role by remember { mutableStateOf("none") }
    var status by remember { mutableStateOf("Ожидание...") }
    var myIp by remember { mutableStateOf("—") }
    var manualIp by remember { mutableStateOf("") }
    var log by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var foundMasters by remember { mutableStateOf(listOf<FoundMaster>()) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var connectedClients by remember { mutableStateOf(listOf<String>()) }
    var fileReady by remember { mutableStateOf(false) }
    var delayMs by remember { mutableStateOf(0f) }
    var measuredOffsetMs by remember { mutableStateOf(0L) }
    var offsetMasterToClient by remember { mutableStateOf<Long?>(null) }
    var offsetClientToMaster by remember { mutableStateOf<Long?>(null) }

    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var registrationListener by remember { mutableStateOf<NsdManager.RegistrationListener?>(null) }
    var discoveryListener by remember { mutableStateOf<NsdManager.DiscoveryListener?>(null) }
    var controlServerSocket by remember { mutableStateOf<DatagramSocket?>(null) }
    var fileServerSocket by remember { mutableStateOf<ServerSocket?>(null) }
    var localMusicFile by remember { mutableStateOf<File?>(null) }

    val detectedBeepAt = remember { AtomicLong(0L) }
    val listeningActive = remember { AtomicLong(0L) } // 1 = слушаем

    fun addLog(msg: String) {
        log = "$msg\n$log"
        Log.d(TAG, msg)
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedUri = uri
            selectedFileName = uri.lastPathSegment ?: "файл"
            addLog("Выбран файл: $selectedFileName")
            fileReady = false
            localMusicFile = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            addLog("Разрешение на файлы получено")
            filePicker.launch("audio/*")
        } else addLog("Разрешение на файлы отклонено")
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) addLog("Микрофон разрешён")
        else addLog("Микрофон запрещён")
    }

    LaunchedEffect(Unit) {
        myIp = getLocalIpAddress() ?: "не найден"
    }

    fun stopEverything() {
        isRunning = false
        listeningActive.set(0)
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
        try { controlServerSocket?.close() } catch (_: Exception) {}
        controlServerSocket = null
        try { fileServerSocket?.close() } catch (_: Exception) {}
        fileServerSocket = null
        registrationListener?.let { try { nsdManager.unregisterService(it) } catch (_: Exception) {} }
        registrationListener = null
        discoveryListener?.let { try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discoveryListener = null
        connectedClients = emptyList()
        foundMasters = emptyList()
        fileReady = false
        offsetMasterToClient = null
        offsetClientToMaster = null
        detectedBeepAt.set(0L)
        status = "Остановлено"
    }

    fun playLocalFile(file: File, startDelayMs: Long = 0) {
        scope.launch(Dispatchers.IO) {
            try {
                val mp = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    prepare()
                }
                if (startDelayMs > 0) delay(startDelayMs)
                withContext(Dispatchers.Main) {
                    try { mediaPlayer?.release() } catch (_: Exception) {}
                    mediaPlayer = mp
                    mp.start()
                    status = "Играет..."
                    addLog("Воспроизведение началось")
                }
            } catch (e: Exception) {
                addLog("Ошибка воспроизведения: ${e.toString()}")
            }
        }
    }

    // Громкий системный бип
    fun playBeep() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            am.isSpeakerphoneOn = true
            // поднимаем громкость alarm/music на время
            try {
                val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            } catch (_: Exception) {}
        } catch (_: Exception) {}

        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            // длинный заметный тон
            tg.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 700)
            Thread.sleep(750)
            tg.stopTone()
            tg.release()
            addLog("Бип ToneGenerator проигран")
        } catch (e: Exception) {
            addLog("Ошибка бипа: ${e.toString()}")
        }
    }

    fun listenForBeep(timeoutMs: Long = 7000): Long? {
        val sampleRate = 44100
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            addLog("Ошибка буфера микрофона")
            return null
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            addLog("Микрофон не открылся")
            return null
        }

        val buffer = ShortArray(minBuf)
        recorder.startRecording()
        val start = System.currentTimeMillis()
        var detected: Long? = null
        var maxSeen = 0
        val threshold = 500 // очень низкий
        var lastLog = 0L
        var hotCount = 0

        addLog("Слушаю... (${timeoutMs}мс)")

        while (System.currentTimeMillis() - start < timeoutMs) {
            val read = recorder.read(buffer, 0, buffer.size)
            if (read > 0) {
                var maxAmp = 0
                var sum = 0L
                for (i in 0 until read) {
                    val a = abs(buffer[i].toInt())
                    if (a > maxAmp) maxAmp = a
                    sum += a
                }
                if (maxAmp > maxSeen) maxSeen = maxAmp

                val now = System.currentTimeMillis()
                if (now - lastLog > 500) {
                    addLog("lvl max=$maxAmp avg=${sum / read}")
                    lastLog = now
                }

                if (maxAmp > threshold) {
                    hotCount++
                    // нужно 2 подряд "громких" буфера, чтобы отсечь щелчки
                    if (hotCount >= 2) {
                        detected = now
                        break
                    }
                } else {
                    hotCount = 0
                }
            }
        }

        try { recorder.stop() } catch (_: Exception) {}
        try { recorder.release() } catch (_: Exception) {}

        addLog("Итог микрофона max=$maxSeen threshold=$threshold detected=${detected != null}")
        return detected
    }

    fun applyAveragedOffset(o1: Long?, o2: Long?) {
        if (o1 == null && o2 == null) return
        val avg = when {
            o1 != null && o2 != null -> (o1 + o2) / 2
            o1 != null -> o1
            else -> o2!!
        }
        measuredOffsetMs = avg
        delayMs = avg.toFloat().coerceIn(-800f, 800f)
        addLog("Итоговый offset: $avg мс")
        status = "Калибровка: $avg мс"
    }

    // ===================== МАСТЕР =====================
    fun startMaster() {
        if (isRunning) return
        isRunning = true
        status = "Мастер запускается..."
        addLog("Запуск мастера")

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = CONTROL_PORT
        }
        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                addLog("NSD OK")
                status = "Мастер активен"
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                addLog("NSD ошибка: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = regListener
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)

        scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CONTROL_PORT))
                }
                controlServerSocket = socket
                addLog("UDP control $CONTROL_PORT")
                val buf = ByteArray(4096)

                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    val clientIp = packet.address.hostAddress ?: continue
                    val from = packet.address
                    val fromPort = packet.port

                    when {
                        msg.startsWith("HELLO") -> {
                            if (!connectedClients.contains(clientIp)) {
                                connectedClients = connectedClients + clientIp
                                addLog("Клиент: $clientIp")
                                withContext(Dispatchers.Main) {
                                    status = "Клиентов: ${connectedClients.size}"
                                }
                            }
                            socket.send(DatagramPacket("PONG".toByteArray(), 4, from, fromPort))
                        }
                        msg.startsWith("SYNC_PING") -> {
                            socket.send(DatagramPacket("SYNC_PONG".toByteArray(), 9, from, fromPort))
                        }
                        msg.startsWith("RESULT1:") -> {
                            val v = msg.substringAfter("RESULT1:").toLongOrNull()
                            offsetMasterToClient = v
                            addLog("M→C: $v мс")
                            withContext(Dispatchers.Main) {
                                applyAveragedOffset(offsetMasterToClient, offsetClientToMaster)
                            }
                        }
                        msg.startsWith("RESULT2:") -> {
                            val v = msg.substringAfter("RESULT2:").toLongOrNull()
                            offsetClientToMaster = v
                            addLog("C→M: $v мс")
                            withContext(Dispatchers.Main) {
                                applyAveragedOffset(offsetMasterToClient, offsetClientToMaster)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) addLog("Control error: ${e.toString()}")
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(FILE_PORT)
                fileServerSocket = server
                addLog("TCP file $FILE_PORT")
                while (isRunning) {
                    val client = server.accept()
                    val clientIp = client.inetAddress.hostAddress ?: "?"
                    addLog("TCP от $clientIp")
                    scope.launch(Dispatchers.IO) {
                        try {
                            val file = localMusicFile
                            if (file == null || !file.exists()) {
                                addLog("Файл не готов")
                                client.close()
                                return@launch
                            }
                            val out = DataOutputStream(client.getOutputStream())
                            out.writeLong(file.length())
                            out.flush()
                            FileInputStream(file).use { input ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    val r = input.read(buffer)
                                    if (r <= 0) break
                                    out.write(buffer, 0, r)
                                }
                                out.flush()
                            }
                            client.close()
                            addLog("Файл отправлен $clientIp")
                        } catch (e: Exception) {
                            addLog("Ошибка отправки: ${e.toString()}")
                            try { client.close() } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) addLog("File server: ${e.toString()}")
            }
        }
    }

    fun prepareMasterFile() {
        val uri = selectedUri ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val temp = File(context.cacheDir, "master_music.tmp")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                localMusicFile = temp
                fileReady = true
                addLog("Файл готов (${temp.length()} байт)")
                withContext(Dispatchers.Main) { status = "Файл готов" }
            } catch (e: Exception) {
                addLog("Ошибка подготовки: ${e.toString()}")
            }
        }
    }

    fun startBidirectionalCalibration() {
        if (connectedClients.isEmpty()) {
            addLog("Нет клиентов")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        offsetMasterToClient = null
        offsetClientToMaster = null
        detectedBeepAt.set(0L)

        scope.launch(Dispatchers.IO) {
            try {
                val clientIp = connectedClients.first()
                val addr = InetAddress.getByName(clientIp)
                val socket = DatagramSocket()

                addLog("=== Раунд 1: Мастер → Клиент ===")
                withContext(Dispatchers.Main) { status = "Калибровка 1/2" }

                // клиент открывает микрофон
                socket.send(DatagramPacket("CALIB_R1_START".toByteArray(), 13, addr, CONTROL_PORT))
                addLog("Ждём 2 сек, пока клиент откроет микрофон")
                delay(2000)

                val t1 = System.currentTimeMillis()
                // сначала метка, сразу бип
                val msg1 = "BEEP1:$t1".toByteArray()
                socket.send(DatagramPacket(msg1, msg1.size, addr, CONTROL_PORT))
                playBeep()
                addLog("Метка + бип мастера")

                delay(3500)

                addLog("=== Раунд 2: Клиент → Мастер ===")
                withContext(Dispatchers.Main) { status = "Калибровка 2/2" }

                val listenJob = scope.async(Dispatchers.IO) { listenForBeep(6000) }
                delay(600)
                socket.send(DatagramPacket("CALIB_R2_START".toByteArray(), 13, addr, CONTROL_PORT))
                addLog("Команда клиенту: бип")

                val detected = listenJob.await()
                addLog(if (detected != null) "Мастер услышал клиента" else "Мастер НЕ услышал клиента")

                socket.close()
            } catch (e: Exception) {
                addLog("Ошибка калибровки: ${e.toString()}")
            }
        }
    }

    fun sendPlayCommand() {
        scope.launch(Dispatchers.IO) {
            try {
                if (connectedClients.isEmpty() || localMusicFile == null) {
                    addLog("Нет клиентов или файла")
                    return@launch
                }
                val socket = DatagramSocket()
                socket.soTimeout = 1000
                var rtt = 40L
                try {
                    val addr = InetAddress.getByName(connectedClients.first())
                    val t1 = System.currentTimeMillis()
                    socket.send(DatagramPacket("SYNC_PING".toByteArray(), 9, addr, CONTROL_PORT))
                    socket.receive(DatagramPacket(ByteArray(64), 64))
                    rtt = System.currentTimeMillis() - t1
                    addLog("RTT ≈ $rtt мс")
                } catch (_: Exception) {}

                val playAt = System.currentTimeMillis() + 1000 + rtt / 2
                val msg = "PLAY:$playAt".toByteArray()
                for (ip in connectedClients) {
                    try {
                        socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(ip), CONTROL_PORT))
                    } catch (_: Exception) {}
                }
                socket.close()
                addLog("PLAY offset=${measuredOffsetMs}")
                val wait = playAt - System.currentTimeMillis() + delayMs.toLong()
                playLocalFile(localMusicFile!!, wait.coerceAtLeast(0))
            } catch (e: Exception) {
                addLog("Ошибка PLAY: ${e.toString()}")
            }
        }
    }

    // ===================== КЛИЕНТ =====================
    fun startDiscovery() {
        foundMasters = emptyList()
        status = "Ищу мастеров..."
        addLog("Поиск мастеров")
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val host = resolved.host?.hostAddress ?: return
                        if (foundMasters.none { it.host == host }) {
                            foundMasters = foundMasters + FoundMaster(resolved.serviceName, host)
                            status = "Найдено: ${foundMasters.size}"
                        }
                    }
                })
            }
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                status = "Ошибка поиска"
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun connectAndDownload(host: String) {
        if (host.isBlank() || isRunning) return
        isRunning = true
        status = "Подключаюсь..."
        addLog("Подключение к $host")

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        scope.launch(Dispatchers.IO) {
            try {
                val helloSocket = DatagramSocket()
                val addr = InetAddress.getByName(host)
                helloSocket.send(DatagramPacket("HELLO".toByteArray(), 5, addr, CONTROL_PORT))
                try {
                    helloSocket.soTimeout = 3000
                    val b = ByteArray(256)
                    helloSocket.receive(DatagramPacket(b, b.size))
                    addLog("← PONG")
                } catch (_: Exception) {}
                helloSocket.close()

                withContext(Dispatchers.Main) { status = "Скачиваю..." }
                val tcp = Socket()
                tcp.connect(InetSocketAddress(host, FILE_PORT), 8000)
                val input = DataInputStream(tcp.getInputStream())
                val fileSize = input.readLong()
                addLog("Размер: $fileSize")

                val outFile = File(context.cacheDir, "client_music.tmp")
                FileOutputStream(outFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var received = 0L
                    while (received < fileSize) {
                        val toRead = minOf(buffer.size.toLong(), fileSize - received).toInt()
                        val read = input.read(buffer, 0, toRead)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        received += read
                    }
                }
                tcp.close()
                localMusicFile = outFile
                fileReady = true
                addLog("Файл скачан")
                withContext(Dispatchers.Main) { status = "Готов" }

                val listen = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(CONTROL_PORT))
                }
                controlServerSocket = listen
                val buf = ByteArray(2048)

                while (isRunning) {
                    val packet = DatagramPacket(buf, buf.size)
                    listen.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    val from = packet.address
                    val fromPort = packet.port

                    when {
                        msg.startsWith("SYNC_PING") -> {
                            listen.send(DatagramPacket("SYNC_PONG".toByteArray(), 9, from, fromPort))
                        }

                        msg.startsWith("CALIB_R1_START") -> {
                            addLog("Раунд 1: микрофон ЗАРАНЕЕ")
                            withContext(Dispatchers.Main) { status = "Слушаю мастера..." }
                            detectedBeepAt.set(0L)
                            listeningActive.set(1)

                            scope.launch(Dispatchers.IO) {
                                val detected = listenForBeep(8000)
                                if (detected != null) {
                                    detectedBeepAt.set(detected)
                                    addLog("ЗВУК ПОЙМАН: $detected")
                                } else {
                                    addLog("За 8 сек не поймал")
                                }
                                listeningActive.set(0)
                            }
                        }

                        msg.startsWith("BEEP1:") -> {
                            val masterTime = msg.substringAfter("BEEP1:").toLongOrNull()
                            val packetAt = System.currentTimeMillis()
                            addLog("Метка BEEP1 ($masterTime)")

                            // ждём поимку до 4 секунд
                            var detected = detectedBeepAt.get()
                            repeat(40) {
                                if (detected != 0L) return@repeat
                                delay(100)
                                detected = detectedBeepAt.get()
                            }

                            if (detected != 0L && masterTime != null) {
                                val networkApprox = (packetAt - masterTime).coerceAtLeast(0)
                                val acoustic = detected - masterTime
                                val offset = acoustic - networkApprox / 2
                                offsetMasterToClient = offset
                                addLog("M→C offset ≈ $offset")
                                listen.send(DatagramPacket("RESULT1:$offset".toByteArray(), "RESULT1:$offset".length, from, fromPort))
                                withContext(Dispatchers.Main) {
                                    applyAveragedOffset(offsetMasterToClient, offsetClientToMaster)
                                }
                            } else {
                                addLog("Клиент НЕ услышал бип мастера")
                            }
                        }

                        msg.startsWith("CALIB_R2_START") -> {
                            addLog("Раунд 2: мой бип ОБЯЗАТЕЛЬНО")
                            withContext(Dispatchers.Main) { status = "Издаю бип..." }
                            delay(400)
                            val t2 = System.currentTimeMillis()
                            playBeep()
                            listen.send(DatagramPacket("BEEP2:$t2".toByteArray(), "BEEP2:$t2".length, from, fromPort))
                            addLog("Бип клиента отправлен")

                            if (offsetMasterToClient != null) {
                                offsetClientToMaster = offsetMasterToClient
                                val s = "RESULT2:${offsetClientToMaster}"
                                listen.send(DatagramPacket(s.toByteArray(), s.length, from, fromPort))
                                withContext(Dispatchers.Main) {
                                    applyAveragedOffset(offsetMasterToClient, offsetClientToMaster)
                                }
                            }
                        }

                        msg.startsWith("PLAY:") -> {
                            val playAt = msg.substringAfter("PLAY:").toLongOrNull() ?: continue
                            val wait = playAt - System.currentTimeMillis() + delayMs.toLong()
                            addLog("PLAY через $wait мс")
                            localMusicFile?.let { playLocalFile(it, wait.coerceAtLeast(0)) }
                        }
                    }
                }
            } catch (e: Exception) {
                addLog("Ошибка клиента: ${e.toString()}")
                withContext(Dispatchers.Main) {
                    status = "Ошибка: ${e.toString()}"
                    isRunning = false
                }
            }
        }
    }

    // ===================== UI =====================
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Синхронизация звука", fontSize = 22.sp, color = Color.Red)
        Spacer(Modifier.height(6.dp))
        Text("Мой IP: $myIp", fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))
        Text("Статус: $status", fontSize = 15.sp)
        Spacer(Modifier.height(4.dp))
        Text("Offset: ${measuredOffsetMs} мс | слайдер: ${delayMs.toInt()} мс", fontSize = 13.sp)
        Spacer(Modifier.height(16.dp))

        when (role) {
            "none" -> {
                Button(onClick = { role = "master"; startMaster() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Стать мастером")
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { role = "client"; startDiscovery() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Найти мастеров")
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } catch (_: Exception) {}
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Настройки Wi-Fi / Hotspot")
                }
            }

            "master" -> {
                Text("Режим: МАСТЕР", fontSize = 18.sp)
                Text("Клиентов: ${connectedClients.size}")
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    val permission = if (android.os.Build.VERSION.SDK_INT >= 33)
                        Manifest.permission.READ_MEDIA_AUDIO
                    else Manifest.permission.READ_EXTERNAL_STORAGE
                    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
                        filePicker.launch("audio/*")
                    else permissionLauncher.launch(permission)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (selectedFileName == null) "1. Выбрать песню" else "Песня: $selectedFileName")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { prepareMasterFile() }, enabled = selectedUri != null, modifier = Modifier.fillMaxWidth()) {
                    Text("2. Подготовить файл")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { startBidirectionalCalibration() }, enabled = connectedClients.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("3. Калибровать по звуку")
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { sendPlayCommand() }, enabled = fileReady && connectedClients.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("4. PLAY (всем)")
                }
                Spacer(Modifier.height(16.dp))
                Text("Ручная подстройка: ${delayMs.toInt()} мс")
                Slider(value = delayMs, onValueChange = { delayMs = it }, valueRange = -800f..800f)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { stopEverything(); role = "none" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Остановить")
                }
            }

            "client" -> {
                Text("Режим: КЛИЕНТ", fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                if (foundMasters.isEmpty()) Text("Мастера не найдены")
                else foundMasters.forEach { master ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable { connectAndDownload(master.host) }
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(master.name)
                            Text(master.host, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = manualIp, onValueChange = { manualIp = it },
                    label = { Text("IP вручную") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { connectAndDownload(manualIp.trim()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Подключиться и скачать")
                }
                Spacer(Modifier.height(16.dp))
                Text("Ручная подстройка: ${delayMs.toInt()} мс")
                Slider(value = delayMs, onValueChange = { delayMs = it }, valueRange = -800f..800f)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { stopEverything(); role = "none" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Назад")
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Лог:", fontSize = 13.sp)
        Text(log.ifEmpty { "пусто" }, fontSize = 11.sp)
    }
}

fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            for (addr in intf.inetAddresses) {
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                }
            }
        }
    } catch (_: Exception) {}
    return null
}