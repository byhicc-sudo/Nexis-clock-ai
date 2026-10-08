package com.nexis.ceo

/** All conclusions are derived from user-entered local records, never fictitious balances. */
object NexisBrain {
    fun daily(items: List<Item>): String {
        val now = today()
        val overdue = items.filter {
            it.due.isNotEmpty() && it.due < now && it.status !in listOf("Tamamlandı", "Tahsil edildi", "İptal")
        }
        val dueToday = items.filter {
            it.due == now && it.status !in listOf("Tamamlandı", "Tahsil edildi", "İptal")
        }
        val urgent = items.filter {
            it.priority == 3 && it.status !in listOf("Tamamlandı", "Tahsil edildi", "İptal")
        }
        val receivables = items.filter { it.kind == "Tahsilat" && it.status != "Tahsil edildi" }
        val quotes = items.filter { it.kind == "Teklif" && it.status == "Bekliyor" }
        val out = StringBuilder()
        out.append("NEXİS CEO AI — ${now}\nGÜNLÜK YÖNETİM BRİFİNGİ\n")
        out.append("Veri kaynağı: yalnızca bu telefona girdiğin kayıtlar.\n\n")
        var n = 1
        if (overdue.isNotEmpty()) {
            out.append("${n++}. GECİKEN İŞLER (${overdue.size}):\n")
            overdue.take(5).forEach {
                out.append("• ${it.title} — ${it.due} — ${it.kind}\n")
            }
            out.append("Adım: Kaydı kontrol et → yetkiliyi ara → gerçek bitiş tarihini doğrula → güncelle.\n\n")
        }
        if (dueToday.isNotEmpty()) {
            out.append("${n++}. BUGÜN SON GÜNÜ (${dueToday.size}):\n")
            dueToday.take(5).forEach { out.append("• ${it.title} — ${it.kind}\n") }
            out.append("Adım: Sırala → müşteri/ekip teyidi al → sonucu kayda geçir.\n\n")
        }
        if (receivables.isNotEmpty()) {
            out.append("${n++}. AÇIK TAHSİLAT (${receivables.size} kayıt):\n")
            out.append("Toplam kayıtlı alacak: ${money(receivables.sumOf { it.amount })}\n")
            out.append("Adım: Fatura ve vade doğrula → mutabakat → nazik tahsilat takibi.\n\n")
        }
        if (quotes.isNotEmpty()) {
            out.append("${n++}. BEKLEYEN TEKLİFLER (${quotes.size}):\n")
            quotes.take(5).forEach { out.append("• ${it.title} (${money(it.amount)})\n") }
            out.append("Adım: Müşteriyle durum teyidi → revizyon ihtiyacını öğren → CRM kaydını güncelle.\n\n")
        }
        if (urgent.isNotEmpty()) {
            out.append("${n++}. YÜKSEK ÖNCELİKLİ KAYITLAR (${urgent.size}):\n")
            urgent.take(5).forEach { out.append("• ${it.title}\n") }
            out.append("\n")
        }
        if (n == 1) out.append("Henüz üzerinde aksiyon üretilebilecek kayıt yok. İlk müşterini, teklifini ve iş emirlerini ekle.\n\n")
        out.append("HER GÜN KONTROL ET:\n")
        out.append("• Ödeme planı ve nakit akışı\n• Mevcut saha işleri ve malzeme ihtiyacı\n")
        out.append("• Açık teklif dönüşleri\n• Yeni müşteri görüşmeleri\n\n")
        out.append("NİHAİ KARAR: SENDE. Sistem müşteri mesajı veya ödeme göndermez.")
        return out.toString()
    }

    fun respond(question: String, items: List<Item>): String {
        val q = question.lowercase(java.util.Locale("tr", "TR"))
        if (q.contains("bugün") || q.contains("plan") || q.contains("yapmal") || q.contains("öncelik"))
            return daily(items)
        val kind = when {
            q.contains("tahsil") || q.contains("para") || q.contains("alacak") -> "Tahsilat"
            q.contains("teklif") -> "Teklif"
            q.contains("müşteri") || q.contains("firma") -> "Müşteri"
            q.contains("servis") || q.contains("iş emri") || q.contains("saha") -> "İş Emri"
            q.contains("görev") || q.contains("hatırlat") -> "Görev"
            else -> null
        }
        if (kind == null) return "Şirket verilerine dayalı bir işlem için 'bugünkü plan', 'açık tahsilatlar', 'bekleyen teklifler', 'iş emirleri' veya 'müşteri listesi' diyebilirsin. Detaylı yapay zekâ değerlendirmesi için Ayarlar bölümünde HTTPS sunucunu tanımlamalısın."
        val selected = items.filter { it.kind == kind }
        if (selected.isEmpty()) return "${kind} kategorisinde henüz kayıt yok. Önce ilgili sekmeden kayıt ekle."
        val buf = StringBuilder("${kind.uppercase(java.util.Locale("tr", "TR"))} — ${selected.size} kayıt\n\n")
        selected.take(12).forEach {
            buf.append("• ${it.title} | ${it.status}")
            if (it.amount > 0) buf.append(" | ${money(it.amount)}")
            if (it.due.isNotBlank()) buf.append(" | ${it.due}")
            buf.append("\n")
        }
        if (kind == "Tahsilat") buf.append("\nKayıtlı açık tutar: ${money(selected.filter { it.status != "Tahsil edildi" }.sumOf { it.amount })}\n")
        buf.append("\nÖneri: Her kaydın vadesini, kişisini ve gerçekleşme durumunu teyit ederek güncelle. İşlem yapmadan önce onay ver.")
        return buf.toString()
    }
}
