package com.nexis.ceo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** NEXIS CEO AI research app. No firmware flashing, vendor writes, or automatic actions. */
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var log: TextView
    private lateinit var mac: EditText
    private lateinit var server: EditText
    private lateinit var token: EditText
    private lateinit var question: EditText
    private lateinit var answer: TextView
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var speech: TextToSpeech? = null
    private val found = mutableSetOf<String>()
    private val adapter by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val permissionId = 42
    private val speechId = 51

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speech = TextToSpeech(this, this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        root.addView(label("NEXİS CEO AI  |  LAB v0.1", 23f))
        root.addView(label("Güvenli Bluetooth keşfi. Firmware yazma ve otomatik işlem YOK.", 14f))
        root.addView(label("1. Akıllı saat", 19f))
        mac = field("Saatin BLE MAC adresi")
        root.addView(mac)
        root.addView(button("Bluetooth tara") { scan() })
        root.addView(button("Saate bağlan ve servisleri keşfet") { connect() })
        root.addView(button("Bağlantıyı kes") { gatt?.disconnect() })
        log = label("Tarama bekleniyor.", 12f)
        log.setTextIsSelectable(true)
        root.addView(log)
        root.addView(label("2. Yapay zekâ asistanı", 19f))
        root.addView(label("HTTPS sunucusu gereklidir. OpenAI API anahtarını telefona girmeyin.", 13f))
        server = field("https://sunucu-adresiniz", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        token = field("Uygulama erişim anahtarı", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        question = field("Bugün NEXİS için hangi işleri yapmalıyım?")
        root.addView(server)
        root.addView(token)
        root.addView(question)
        root.addView(button("Sesle soru gir") { listen() })
        root.addView(button("Yapay zekâya sor") { ask() })
        answer = label("Yapay zekâ yanıtı burada gösterilecek.", 15f)
        answer.setTextIsSelectable(true)
        root.addView(answer)
        permissions()
    }

    private fun label(s: String, size: Float) = TextView(this).apply {
        text = s
        textSize = size
        setPadding(0, 10, 0, 10)
    }

    private fun field(hintValue: String, kind: Int = InputType.TYPE_CLASS_TEXT) =
        EditText(this).apply { hint = hintValue; inputType = kind; minHeight = 110 }

    private fun button(s: String, action: () -> Unit) =
        Button(this).apply { text = s; setOnClickListener { action() } }

    private fun logLine(s: String) = runOnUiThread { log.append("\n" + s) }

    private fun required(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun allowed() = required().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissions(): Boolean {
        if (allowed()) return true
        requestPermissions(required(), permissionId)
        return false
    }

    @SuppressLint("MissingPermission")
    private fun scan() {
        if (!permissions()) return
        val ble = adapter?.bluetoothLeScanner ?: run {
            logLine("Bluetooth kapalı veya BLE kullanılamıyor.")
            return
        }
        if (scanning) {
            ble.stopScan(scanCallback)
            scanning = false
            logLine("Tarama durdu.")
            return
        }
        found.clear()
        scanning = true
        logLine("Tarama başladı. Yesido IO50 adını kontrol edin.")
        ble.startScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!allowed()) return
            val device = result.device
            val address = device.address
            if (found.add(address)) {
                val name = device.name ?: result.scanRecord?.deviceName ?: "(isimsiz)"
                logLine("Bulundu: " + name + " | " + address + " RSSI=" + result.rssi)
                if (name.equals("Yesido IO50", ignoreCase = true)) {
                    runOnUiThread { mac.setText(address) }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            logLine("Tarama hatası: " + errorCode)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect() {
        if (!permissions()) return
        val address = mac.text.toString().trim().uppercase(Locale.ROOT)
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            logLine("Geçerli saat MAC adresi girin.")
            return
        }
        try {
            if (scanning) scan()
            gatt?.close()
            logLine("Bağlanıyor: " + address)
            gatt = adapter?.getRemoteDevice(address)
                ?.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            logLine("Bağlantı hatası: " + e.message)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                logLine("Bağlandı. Servis keşfi başlıyor.")
                if (allowed()) g.discoverServices()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                logLine("Bluetooth bağlantısı kesildi: " + status)
            } else {
                logLine("Bluetooth durumu: " + state + " / " + status)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                logLine("Servis hatası: " + status)
                return
            }
            logLine("Bulunan servis sayısı: " + g.services.size)
            for (service in g.services) {
                logLine("Servis: " + service.uuid)
                for (c in service.characteristics) {
                    val flags = c.properties
                    val modes = mutableListOf<String>()
                    if (flags and BluetoothGattCharacteristic.PROPERTY_READ != 0) modes.add("READ")
                    if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) modes.add("WRITE")
                    if (flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) modes.add("NOTIFY")
                    if (flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) modes.add("INDICATE")
                    logLine("  " + c.uuid + " " + modes.joinToString(","))
                }
            }
            logLine("Keşif tamamlandı. Cihaza komut veya firmware GÖNDERİLMEDİ.")
        }
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, speechId)
        } catch (_: Exception) {
            answer.text = "Telefonda ses tanıma bulunamadı, yazılı soru kullanın."
        }
    }

    @Deprecated("System speech activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == speechId && resultCode == RESULT_OK) {
            val words = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!words.isNullOrEmpty()) question.setText(words.first())
        }
    }

    private fun ask() {
        val host = server.text.toString().trim().trimEnd('/')
        val key = token.text.toString().trim()
        val message = question.text.toString().trim()
        if (!host.startsWith("https://") || key.isEmpty() || message.isEmpty()) {
            answer.text = "HTTPS sunucu adresi, erişim anahtarı ve soru gereklidir."
            return
        }
        answer.text = "Yanıt hazırlanıyor..."
        Thread {
            var conn: HttpURLConnection? = null
            try {
                conn = URL(host + "/chat").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Authorization", "Bearer " + key)
                val body = JSONObject().put("message", message).toString()
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val responseText = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    .bufferedReader().use { it.readText() }
                val response = if (code in 200..299)
                    JSONObject(responseText).optString("answer", "Yanıt alınamadı")
                else "Sunucu hatası: HTTP " + code
                runOnUiThread {
                    answer.text = response
                    if (code in 200..299) speech?.speak(
                        response, TextToSpeech.QUEUE_FLUSH, null, "nexis-answer"
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { answer.text = "Bağlantı hatası: " + e.message }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) speech?.language = Locale("tr", "TR")
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        if (allowed() && scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.disconnect()
        gatt?.close()
        speech?.stop()
        speech?.shutdown()
        super.onDestroy()
    }
}
