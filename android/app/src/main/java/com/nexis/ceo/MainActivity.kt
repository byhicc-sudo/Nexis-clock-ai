package com.nexis.ceo

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** NEXIS CEO AI: local-first office dashboard and read-only iO50 diagnostics. */
class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val ink = Color.rgb(233,243,250)
    private val soft = Color.rgb(159,184,201)
    private val bg = Color.rgb(11,21,35)
    private val panel = Color.rgb(24,41,59)
    private val edge = Color.rgb(44,69,85)
    private val accent = Color.rgb(30,211,173)
    private lateinit var db: NexisDb
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var statusLine: TextView
    private var page = "Özet"
    private var speech: TextToSpeech? = null
    private var heardTarget: EditText? = null
    private var watchAddress = "BB:79:64:F8:13:B4"
    private var watchConnected = false
    private var gatt: BluetoothGatt? = null
    private var scanActive = false
    private var watchLog = "Henüz saat bağlantısı test edilmedi."
    private var showConnectionLog: TextView? = null
    private var aiResult: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("nexis_private_settings", MODE_PRIVATE) }
    private val adapter by lazy { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    private val menus = listOf("Özet","Görev","Müşteri","Teklif","Tahsilat","İş Emri","Asistan","Saat","Ayarlar")
    private val voiceRequest = 810
    private val exportRequest = 811
    private val importRequest = 812

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        db = NexisDb(this)
        watchAddress = prefs.getString("watch_address", watchAddress) ?: watchAddress
        speech = TextToSpeech(this, this)
        render("Özet")
        askPermissions()
        notifications()
    }
    private fun askPermissions() {
        val requested = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                requested.add(Manifest.permission.BLUETOOTH_SCAN)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                requested.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requested.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requested.add(Manifest.permission.POST_NOTIFICATIONS)
        if (requested.isNotEmpty()) requestPermissions(requested.toTypedArray(), 600)
    }
    private fun bluetoothAllowed(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun shape(color: Int, radius: Float = 18f, stroke: Int = 0): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
            if (stroke != 0) setStroke(1, stroke)
        }
    }
    private fun dip(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun txt(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value; textSize = size; setTextColor(color)
            setPadding(dip(5),dip(5),dip(5),dip(5))
            if (bold) setTypeface(null,Typeface.BOLD)
            setTextIsSelectable(true)
        }
    private fun button(title: String, action: () -> Unit, secondary: Boolean = false): Button =
        Button(this).apply {
            text = title; textSize = 13f; isAllCaps = false
            setTextColor(if(secondary) ink else bg)
            background = shape(if(secondary) edge else accent, 13f)
            setOnClickListener { action() }
            minHeight = dip(43)
        }
    // Overload for Kotlin trailing-lambda UI callbacks.
    private fun button(title: String, secondary: Boolean = false, action: () -> Unit): Button =
        button(title, action, secondary)

    private fun section(title: String): LinearLayout {
        val c=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=shape(panel,18f,edge)
            setPadding(dip(14),dip(12),dip(14),dip(12))
            val lp=LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dip(12);layoutParams=lp
        }
        c.addView(txt(title,18f,ink,true))
        content.addView(c)
        return c
    }
    private fun addText(c: LinearLayout,s:String,color:Int=soft) { c.addView(txt(s,14f,color)) }
    private fun field(hint:String, default:String=""): EditText =
        EditText(this).apply {
            setText(default); this.hint=hint; textSize=15f
            setTextColor(ink); setHintTextColor(soft)
            backgroundTintList=android.content.res.ColorStateList.valueOf(accent)
            setSingleLine(false); minHeight=dip(43)
        }

    private fun render(target:String) {
        page=target
        val vertical=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(bg) }
        setContentView(vertical)
        val header=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dip(16),dip(14),dip(16),dip(10))
            background=shape(panel,0f)
        }
        header.addView(txt("NEXİS  /  CEO AI",23f,accent,true))
        statusLine=txt("Şirket yönetim merkezi  •  Kayıtlar yalnızca bu telefonda",12f,soft)
        header.addView(statusLine)
        vertical.addView(header)
        val strip=HorizontalScrollView(this).apply {isHorizontalScrollBarEnabled=false}
        val tabs=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;setPadding(dip(7),dip(6),dip(7),dip(6))}
        menus.forEach { menu ->
            val selected=menu==target
            val b=button(menu,{ render(menu) },!selected)
            val lp=LinearLayout.LayoutParams(-2,dip(44));lp.setMargins(dip(3),0,dip(3),0)
            tabs.addView(b,lp)
        }
        strip.addView(tabs)
        vertical.addView(strip)
        val scroll=ScrollView(this).apply {isFillViewport=true}
        content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dip(13),dip(12),dip(13),dip(34))
        }
        scroll.addView(content);vertical.addView(scroll)
        when(target){
            "Özet" -> dashboard()
            "Asistan" -> assistantPage()
            "Saat" -> watchPage()
            "Ayarlar" -> settingsPage()
            else -> listPage(target)
        }
    }
    private fun actionRow(vararg actions: Pair<String, () -> Unit>): LinearLayout =
        LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            actions.forEach { (label, fn) ->
                val b=button(label,fn,true)
                val lp=LinearLayout.LayoutParams(0,dip(44),1f);lp.setMargins(dip(2),0,dip(2),0)
                addView(b,lp)
            }
        }
    private fun dashboard(){
        val all=db.all()
        val open=all.filter { it.status !in listOf("Tamamlandı","Tahsil edildi","İptal") }
        val c=section("ŞİRKETİN BUGÜNKÜ DURUMU")
        addText(c,"Kayıtlı müşteriler: ${all.count{it.kind=="Müşteri"}}",ink)
        addText(c,"Açık görevler: ${open.count{it.kind=="Görev"}}",ink)
        addText(c,"Aktif iş emirleri: ${open.count{it.kind=="İş Emri"}}",ink)
        addText(c,"Bekleyen teklifler: ${open.count{it.kind=="Teklif"}}",ink)
        addText(c,"Açık tahsilatlar: ${money(db.balance())}",accent)
        addText(c,"Gecikmiş kayıt: ${db.overdue().size}",if(db.overdue().isEmpty())soft else Color.rgb(255,169,95))
        val shortcuts=section("HIZLI İŞLEMLER")
        shortcuts.addView(actionRow("Görev ekle" to { editItem("Görev") },"Müşteri ekle" to { editItem("Müşteri") }))
        shortcuts.addView(actionRow("Teklif ekle" to { editItem("Teklif") },"Tahsilat ekle" to { editItem("Tahsilat") }))
        val briefing=section("GÜNLÜK YÖNETİM PLANI")
        val plan=NexisBrain.daily(all)
        addText(briefing,plan,ink)
        briefing.addView(button("Bu planı telefonda hatırlat") {
            notifyLocal("NEXİS • Günlük yönetim planı",
                "Bugünkü iş listesi hazır. NEXİS CEO AI uygulamasını aç.")
        })
        val more=section("YÖNETİM KURALI")
        addText(more,"Sistem teklifler, tahsilatlar ve görevler için öneri üretir. Müşteriye mesaj, ödeme veya resmi işlem otomatik yapılmaz. Son karar sana aittir.")
    }
    private fun listPage(kind:String) {
        val entries=db.all(kind)
        val title=section("${kind.uppercase(Locale("tr","TR"))}  •  ${entries.size} KAYIT")
        title.addView(button("+ Yeni ${kind.lowercase(Locale("tr","TR"))} ekle"){editItem(kind)})
        if(entries.isEmpty()) {
            val empty=section("Henüz kayıt yok")
            addText(empty,"Gerçek şirket verilerini ekledikçe günlük brifing ve asistan bunlardan yararlanacak.")
            return
        }
        entries.forEach { it ->
            val c=section(it.title)
            addText(c,"Durum: ${it.status}"+if(it.priority==3) "   •   ACİL" else "",ink)
            if(it.customer.isNotBlank()) addText(c,"Müşteri: ${it.customer}")
            if(it.phone.isNotBlank()) addText(c,"Telefon: ${it.phone}")
            if(it.due.isNotBlank()) addText(c,"Hedef/vade: ${it.due}",
                if(it.due<today() && it.status !in listOf("Tamamlandı","Tahsil edildi")) Color.rgb(255,164,107) else soft)
            if(it.amount>0) addText(c,"Tutar: ${money(it.amount)}",accent)
            if(it.notes.isNotBlank()) addText(c,it.notes)
            c.addView(actionRow("Düzenle" to { editItem(kind,it) },
                "Durum" to { chooseStatus(it) },
                "Sil" to { confirmDelete(it) }))
        }
    }
    private fun statuses(kind:String) = when(kind){
        "Görev","İş Emri" -> arrayOf("Açık","Devam ediyor","Tamamlandı","İptal")
        "Teklif" -> arrayOf("Hazırlanıyor","Bekliyor","Kabul edildi","Reddedildi","İptal")
        "Tahsilat" -> arrayOf("Açık","Kısmi ödeme","Tahsil edildi","İptal")
        else -> arrayOf("Aktif","Potansiyel","Pasif")
    }
    private fun chooseStatus(it:Item) {
        val options=statuses(it.kind)
        AlertDialog.Builder(this).setTitle("Durumu değiştir: ${it.title}")
            .setItems(options) { _,which -> db.setStatus(it.id,options[which]);render(page) }
            .setNegativeButton("Vazgeç",null).show()
    }
    private fun confirmDelete(it:Item) {
        AlertDialog.Builder(this).setTitle("Kaydı sil?")
            .setMessage("${it.title}\nSilinen kayıt geri getirilemez. Önce yedek alabilirsin.")
            .setPositiveButton("Sil"){_,_->db.remove(it.id);render(page)}
            .setNegativeButton("Vazgeç",null).show()
    }
    private fun editItem(kind:String, old:Item?=null) {
        val form=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dip(18),dip(6),dip(18),dip(6))
        }
        val title=field(if(kind=="Müşteri")"Firma / Müşteri adı" else "İş / kayıt başlığı",old?.title?:"")
        val customer=field("İlgili firma / müşteri",old?.customer?:"")
        val phone=field("Telefon / iletişim",old?.phone?:"")
        val notes=field("Açıklama / yapılacak adımlar",old?.notes?:"")
        val amount=field("Tutar (TL) — yalnızca sayı",if(old?.amount!=null && old.amount>0)old.amount.toString() else "")
        amount.inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        val due=button(old?.due?.takeIf{it.isNotEmpty()} ?: "Vade / hedef tarih seç", {})
        var dueValue=old?.due?:""
        due.setOnClickListener {
            val calendar=java.util.Calendar.getInstance()
            if(dueValue.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
                val arr=dueValue.split("-")
                calendar.set(arr[0].toInt(),arr[1].toInt()-1,arr[2].toInt())
            }
            DatePickerDialog(this,{_,y,m,d->
                dueValue=String.format(Locale.ROOT,"%04d-%02d-%02d",y,m+1,d)
                due.text=dueValue
            },calendar.get(java.util.Calendar.YEAR),calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }
        val priority=Spinner(this)
        val priorityNames=arrayOf("Düşük","Normal","Yüksek / Acil")
        priority.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,priorityNames)
        priority.setSelection((old?.priority ?: 2).coerceIn(1,3)-1)
        val status=Spinner(this)
        val opts=statuses(kind)
        status.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,opts)
        val initial=old?.status ?: (if(kind=="Müşteri")"Aktif" else if(kind=="Teklif")"Bekliyor" else "Açık")
        status.setSelection(opts.indexOf(initial).coerceAtLeast(0))
        form.addView(title)
        if(kind!="Müşteri")form.addView(customer)
        form.addView(phone)
        if(kind=="Teklif" || kind=="Tahsilat")form.addView(amount)
        if(kind!="Müşteri") { form.addView(due);form.addView(txt("Öncelik",12f,soft));form.addView(priority) }
        form.addView(txt("Durum",12f,soft));form.addView(status)
        form.addView(notes)
        val scroll=ScrollView(this).apply{addView(form)}
        val dialog=AlertDialog.Builder(this)
            .setTitle(if(old==null)"Yeni ${kind}" else "${kind} düzenle")
            .setView(scroll)
            .setNegativeButton("Vazgeç",null)
            .setPositiveButton("Kaydet",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text=title.text.toString().trim()
                if(text.isEmpty()){title.error="Başlık gerekli";return@setOnClickListener}
                val parsed=amount.text.toString().trim().replace(",",".").toDoubleOrNull() ?: 0.0
                if(parsed<0){amount.error="Negatif tutar girilemez";return@setOnClickListener}
                db.save(Item(
                    id=old?.id?:0L,kind=kind,title=text,
                    customer=if(kind=="Müşteri")"" else customer.text.toString().trim(),
                    phone=phone.text.toString().trim(), notes=notes.text.toString().trim(),
                    amount=parsed,due=dueValue,
                    status=opts[status.selectedItemPosition],
                    priority=priority.selectedItemPosition+1,
                    created=old?.created?:today()
                ))
                dialog.dismiss();render(page)
            }
        }
        dialog.show()
    }
    private fun assistantPage() {
        val help=section("NEXİS YÖNETİM ASİSTANI")
        addText(help,"İnternet olmadan kayıtlarına göre günlük plan, teklif ve tahsilat kontrolü üretir. İsteğe bağlı bulut yapay zekâ için HTTPS sunucu bağlanabilir.")
        val question=field("Örnek: Bugün ne yapmalıyım?")
        question.minLines=2
        help.addView(question)
        help.addView(button("🎙 Türkçe konuşarak sor") { listen(question) })
        help.addView(button("Şirket kayıtlarını analiz et") {
            val q=question.text.toString().trim().ifBlank{"Bugün ne yapmalıyım?"}
            if(q.lowercase(Locale("tr","TR")).startsWith("görev ekle ")) {
                val task=q.substringAfter("görev ekle ").trim()
                if(task.isNotEmpty()) {
                    db.save(Item(kind="Görev",title=task,status="Açık"))
                    presentAnswer("Görev kaydedildi: ${task}. 'Görev' sekmesinden öncelik ve tarih ekleyebilirsin.")
                } else presentAnswer("Görev başlığı belirtilmedi.")
            } else presentAnswer(NexisBrain.respond(q,db.all()))
        })
        help.addView(button("Bulut yapay zekâya danış (HTTPS)", {
            remoteAsk(question.text.toString().trim())
        },true))
        val result=section("ASİSTANIN YANITI")
        aiResult=txt("Sorunu yaz veya konuş. Yanıt burada görünecek.",14f,ink)
        result.addView(aiResult)
        result.addView(button("Yanıtı sesli oku",{say(aiResult?.text?.toString()?:"")},true))
        val q=section("ÖRNEK KOMUTLAR")
        addText(q,"• Bugün ne yapmalıyım?\n• Açık tahsilatları göster\n• Bekleyen teklifleri listele\n• İş emirleri\n• Görev ekle HSC Çelik ile görüş")
        addText(q,"Bu sürüm, gerçek verilerini kullanmadan şirketin mali durumunu bildiğini iddia etmez.")
    }
    private fun presentAnswer(answer:String){
        aiResult?.text=answer
        say(answer.take(1800))
    }
    private fun listen(target:EditText) {
        heardTarget=target
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE,"tr-TR")
            putExtra(RecognizerIntent.EXTRA_PROMPT,"NEXİS CEO AI dinliyor")
        }
        try { @Suppress("DEPRECATION") startActivityForResult(i,voiceRequest) }
        catch (_:Exception){Toast.makeText(this,"Telefonda ses tanıma kullanılamıyor",Toast.LENGTH_LONG).show()}
    }
    private fun say(value:String) {
        if(value.isNotBlank()) speech?.speak(value,TextToSpeech.QUEUE_FLUSH,null,"nexis")
    }
    private fun remoteAsk(q:String) {
        if(q.isBlank()) {presentAnswer("Önce sorunu yaz.");return}
        val address=prefs.getString("server_url","")!!.trimEnd('/')
        val token=prefs.getString("server_token","") ?: ""
        if(!address.startsWith("https://") || token.isBlank()){
            presentAnswer("Bulut yapay zekâ hazır değil. Ayarlar'da HTTPS sunucu URL'si ve uygulama erişim anahtarı gerekiyor. Çevrimdışı analiz şu anda kullanılabilir.")
            return
        }
        aiResult?.text="Sunucudan yanıt bekleniyor..."
        Thread {
            var conn:HttpURLConnection?=null
            try{
                conn=URL("${address}/chat").openConnection() as HttpURLConnection
                conn.requestMethod="POST";conn.doOutput=true
                conn.connectTimeout=12000;conn.readTimeout=45000
                conn.setRequestProperty("Content-Type","application/json")
                conn.setRequestProperty("Authorization","Bearer ${token}")
                val bytes=JSONObject().put("message",q).toString().toByteArray(Charsets.UTF_8)
                conn.outputStream.use{it.write(bytes)}
                val code=conn.responseCode
                if(code !in 200..299) throw Exception("Sunucu HTTP ${code}")
                val answer=JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).optString("answer")
                runOnUiThread {presentAnswer(answer)}
            }catch(e:Exception){
                runOnUiThread{presentAnswer("Bağlantı sağlanamadı: ${e.message}. Çevrimdışı asistan kullanılabilir.")}
            }finally{conn?.disconnect()}
        }.start()
    }
    private fun watchPage(){
        val box=section("YESIDO iO50 — SAAT BAĞLANTISI")
        addText(box,"FaceLink kaydındaki BLE adresi: ${watchAddress}")
        addText(box,if(watchConnected)"BLE bağlı — salt okunur servis tanılama" else "BLE bağlantısı henüz kurulmadı",if(watchConnected)accent else soft)
        box.addView(button("Saat BLE taraması başlat / durdur"){ scan() })
        box.addView(button("Kayıtlı iO50'ye bağlan") { connect() })
        box.addView(button("Bağlantıyı kes",{ disconnectWatch() },true))
        val diag=section("BAĞLANTI / SERVİS GÜNLÜĞÜ")
        showConnectionLog=txt(watchLog,12f,ink);diag.addView(showConnectionLog)
        val use=section("SAAT İÇİN PLANLANAN İŞLEVLER")
        addText(use,"✓ BLE tarama ve servis keşfi\n✓ Telefon üzerindeki NEXİS görev/hatırlatma ekranları\n✓ Telefon üzerinden sesli yapay zekâ\n\nHenüz DOĞRULANMADI: FaceLink'siz kadran yükleme, saatte NEXİS arayüzü, saate doğrudan bildirim, saat mikrofonundan komut. Bu işlevler için üretici protokolü/SDK uyumluluk testi şart.")
        use.addView(button("Telefonda test bildirimi göster") {
            notifyLocal("NEXİS test bildirimi","Şirket yönetim bildirimi telefonda oluşturuldu; saatte görünmesi garanti değildir.")
        })
        addText(use,"Güvenlik: Cihaza bilinmeyen HEX komut veya firmware GÖNDERİLMEZ.")
    }
    @SuppressLint("MissingPermission")
    private fun scan(){
        if(!bluetoothAllowed()){askPermissions();return}
        val scanner=adapter?.bluetoothLeScanner ?: run{watchMsg("Bluetooth açılmalı");return}
        if(scanActive){scanner.stopScan(scanCallback);scanActive=false;watchMsg("Tarama durduruldu");return}
        scanActive=true;watchMsg("BLE taraması başladı...")
        scanner.startScan(scanCallback)
        handler.postDelayed({
            if(scanActive){scanner.stopScan(scanCallback);scanActive=false;watchMsg("Tarama tamamlandı")}
        },12000)
    }
    private val scanCallback=object:ScanCallback(){
        @SuppressLint("MissingPermission")
        override fun onScanResult(type:Int,res:ScanResult){
            if(!bluetoothAllowed())return
            val name=res.device.name ?: res.scanRecord?.deviceName ?: ""
            if(name.contains("YESIDO",true)||name.contains("IO50",true)){
                val addr=res.device.address
                watchAddress=addr
                prefs.edit().putString("watch_address",addr).apply()
                watchMsg("Bulundu: ${name} (${addr}) RSSI: ${res.rssi}")
            }
        }
        override fun onScanFailed(errorCode:Int){watchMsg("Tarama hata kodu ${errorCode}")}
    }
    private fun watchMsg(msg:String){
        watchLog="${msg}\n${watchLog}".take(5000)
        runOnUiThread {showConnectionLog?.text=watchLog}
    }
    @SuppressLint("MissingPermission")
    private fun connect(){
        if(!bluetoothAllowed()){askPermissions();return}
        val address=watchAddress.uppercase(Locale.ROOT)
        if(!BluetoothAdapter.checkBluetoothAddress(address)){watchMsg("Geçersiz MAC");return}
        try{
            if(scanActive){adapter?.bluetoothLeScanner?.stopScan(scanCallback);scanActive=false}
            gatt?.close()
            gatt=adapter?.getRemoteDevice(address)?.connectGatt(this,false,gattCallback,BluetoothDevice.TRANSPORT_LE)
            watchMsg("Bağlanılıyor: ${address}")
        }catch(e:Exception){watchMsg("Bağlantı hatası: ${e.message}")}
    }
    private val gattCallback=object:BluetoothGattCallback(){
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,state:Int){
            watchConnected=status==BluetoothGatt.GATT_SUCCESS && state==BluetoothProfile.STATE_CONNECTED
            if(watchConnected){
                watchMsg("Bluetooth bağlantısı kuruldu. Servisler inceleniyor...")
                if(bluetoothAllowed())g.discoverServices()
            }else watchMsg("Saat bağlantısı kesildi. Kod: ${status}")
        }
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int){
            if(status!=BluetoothGatt.GATT_SUCCESS){watchMsg("Servis keşfi hatası ${status}");return}
            watchMsg("Bulunan servisler: ${g.services.size}")
            g.services.forEach {svc ->
                watchMsg("SERVİS ${svc.uuid}")
                svc.characteristics.forEach {ch -> watchMsg("  Karakteristik ${ch.uuid} / ${ch.properties}") }
            }
            watchMsg("Salt okunur servis keşfi bitti. Komut gönderilmedi.")
        }
    }
    @SuppressLint("MissingPermission")
    private fun disconnectWatch(){
        if(bluetoothAllowed())gatt?.disconnect()
        gatt?.close();gatt=null;watchConnected=false
        watchMsg("Bağlantı kapatıldı.")
    }
    private fun notifications(){
        if(Build.VERSION.SDK_INT>=26){
            val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel("nexis_management","NEXİS İş Bildirimleri",NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    private fun notifyLocal(title:String,msg:String){
        if(Build.VERSION.SDK_INT>=33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            askPermissions();Toast.makeText(this,"Bildirim iznini ver ve tekrar dene",Toast.LENGTH_LONG).show();return
        }
        val i=Intent(this,MainActivity::class.java)
        val pi=PendingIntent.getActivity(this,1,i,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n=Notification.Builder(this,"nexis_management")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(msg).setAutoCancel(true).setContentIntent(pi).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(101,n)
        Toast.makeText(this,"Telefon bildirimi oluşturuldu",Toast.LENGTH_SHORT).show()
    }
    private fun settingsPage(){
        val settings=section("GÜVENLİ SUNUCU BAĞLANTISI")
        addText(settings,"Bulut asistanı isteğe bağlıdır. HTTPS sunucusu kurulana kadar çevrimdışı yönetim asistanı çalışır.")
        val url=field("https://api.sirketim.com",prefs.getString("server_url","")?:"")
        val key=field("Uygulama erişim anahtarı",prefs.getString("server_token","")?:"")
        key.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        settings.addView(url);settings.addView(key)
        settings.addView(button("Bağlantı ayarlarını kaydet"){
            val u=url.text.toString().trim()
            if(u.isNotEmpty() && !u.startsWith("https://")){
                Toast.makeText(this,"Yalnızca HTTPS desteklenir",Toast.LENGTH_LONG).show()
            }else{
                prefs.edit().putString("server_url",u).putString("server_token",key.text.toString().trim()).apply()
                Toast.makeText(this,"Ayarlar telefonda saklandı",Toast.LENGTH_SHORT).show()
            }
        })
        val backup=section("VERİ YEDEKLEME")
        addText(backup,"Müşteri, görev, teklif ve tahsilat kayıtların telefonda yerel SQLite veritabanında saklanır. GitHub'a otomatik aktarılmaz.")
        backup.addView(button("JSON yedeğini telefona kaydet"){ exportData() })
        backup.addView(button("JSON yedeğinden geri yükle",{importData()},true))
        val security=section("GÜVENLİK VE SINIRLAR")
        addText(security,"• Müşterilere otomatik mesaj GÖNDERMEZ.\n• Ödeme, sözleşme veya resmi işlem YAPMAZ.\n• iO50 firmware'ini değiştirmez.\n• Kullanıcı onayı olmadan kayıt silmez.\n• Sunucu API anahtarını telefona girme; yalnızca uygulama token'ı kullan.\n• Telefon kaybolursa dışa aktarılmamış yerel kayıtlar kaybedilebilir.")
    }
    private fun exportData(){
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
            putExtra(Intent.EXTRA_TITLE,"NEXIS-CEO-AI-${today()}.json")
        }
        @Suppress("DEPRECATION") startActivityForResult(intent,exportRequest)
    }
    private fun importData(){
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="application/json"
        }
        @Suppress("DEPRECATION") startActivityForResult(intent,importRequest)
    }
    @Deprecated("Activity Result callback for speech and backup file picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK)return
        when(requestCode){
            voiceRequest -> {
                val words=data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if(!words.isNullOrEmpty()) heardTarget?.setText(words.first())
            }
            exportRequest -> {
                val uri=data?.data?:return
                try{
                    val arr=JSONArray()
                    db.all().forEach{
                        arr.put(JSONObject().apply{
                            put("kind",it.kind);put("title",it.title);put("notes",it.notes)
                            put("customer",it.customer);put("phone",it.phone);put("amount",it.amount)
                            put("due",it.due);put("status",it.status);put("priority",it.priority)
                            put("created",it.created)
                        })
                    }
                    contentResolver.openOutputStream(uri)?.use{it.write(arr.toString(2).toByteArray(Charsets.UTF_8))}
                    Toast.makeText(this,"Yedek kaydedildi: ${arr.length()} kayıt",Toast.LENGTH_LONG).show()
                }catch(e:Exception){Toast.makeText(this,"Yedekleme hatası: ${e.message}",Toast.LENGTH_LONG).show()}
            }
            importRequest -> {
                val uri=data?.data?:return
                AlertDialog.Builder(this).setTitle("Yedeği içe aktar?")
                    .setMessage("Dosyadaki kayıtlar var olanlara EKLENECEK. Aynı kayıtlar iki kez eklenebilir.")
                    .setPositiveButton("Ekle"){_,_->importBackup(uri)}
                    .setNegativeButton("Vazgeç",null).show()
            }
        }
    }
    private fun importBackup(uri:Uri){
        try {
            val json=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}
                ?: throw Exception("Yedek okunamadı")
            if(json.length>3_000_000) throw Exception("Yedek çok büyük")
            val arr=JSONArray(json)
            if(arr.length()>5000)throw Exception("En fazla 5000 kayıt")
            val allowed=setOf("Müşteri","Görev","Teklif","Tahsilat","İş Emri")
            var n=0
            for(i in 0 until arr.length()){
                val o=arr.getJSONObject(i)
                val kind=o.optString("kind")
                if(kind !in allowed)continue
                val title=o.optString("title").take(500)
                if(title.isBlank())continue
                db.save(Item(
                    kind=kind,title=title,notes=o.optString("notes").take(5000),
                    customer=o.optString("customer").take(500),
                    phone=o.optString("phone").take(100),
                    amount=o.optDouble("amount",0.0).coerceAtLeast(0.0),
                    due=o.optString("due").take(10),
                    status=o.optString("status","Açık").take(50),
                    priority=o.optInt("priority",2).coerceIn(1,3),
                    created=o.optString("created",today()).take(10)
                ));n++
            }
            Toast.makeText(this,"${n} kayıt içe aktarıldı",Toast.LENGTH_LONG).show()
            render("Özet")
        }catch(e:Exception){Toast.makeText(this,"İçe aktarma hatası: ${e.message}",Toast.LENGTH_LONG).show()}
    }
    override fun onInit(status:Int){
        if(status==TextToSpeech.SUCCESS) speech?.language=Locale("tr","TR")
    }
    @SuppressLint("MissingPermission")
    override fun onDestroy(){
        handler.removeCallbacksAndMessages(null)
        if(scanActive && bluetoothAllowed())adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        gatt?.close();speech?.stop();speech?.shutdown()
        super.onDestroy()
    }
}
