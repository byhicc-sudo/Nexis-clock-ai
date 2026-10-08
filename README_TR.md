# NEXİS CEO AI - Android ve Yesido iO50 LAB v0.1

## Amaç
Şahıs işletmesi NEXİS Dijital için telefondan kullanılabilen şirket yönetim asistanı ve Yesido iO50 BLE bağlantı araştırması.

## Mevcut işlevler
- Android: Bluetooth tarama, seçilen cihazın BLE adresine bağlanma, servis/karakteristik keşfi ve teşhis kayıtları.
- Android: Telefon mikrofonundan Türkçe konuşmayı metne çevirme ve sunucu yanıtını seslendirme.
- Sunucu: FastAPI ve OpenAI API ile Türkçe yönetim önerileri. İcra işlemleri için ayrı insan onayı gerekir.
- GitHub Actions: debug APK oluşturma ve indirilebilir artifact olarak saklama.

## Sınırlar
Bu prototip iO50 saatinde test edilmedi; BLE yazma, OTA, kadran yükleme ve saat mikrofonundan ses alma yok.
Sadece Bluetooth karakteristiklerinin görünmesi, saat ekranını kontrol etme yeteneğini ispat etmez.
FaceLink'in BLE bağlantısıyla çakışabilir. Gerekirse FaceLink bağlantısını kapatıp dene.
Canlı muhasebe, CRM ve bu ChatGPT konuşmasının geçmişi sisteme bağlı değildir.
GitHub Actions başarıyla çalışmadan APK hazır olduğu iddia edilmemelidir.

## Telefonda
TELEFONDAN_BASLAT.md dosyasını aç. Actions sekmesinden Android APK iş akışını çalıştır.

## Sunucu kurulumu
Server bağımsız HTTPS sunucusunda çalışmalıdır. Komut örneği:

    cd server
    python -m venv .venv
    . .venv/bin/activate
    pip install -r requirements.txt
    export OPENAI_API_KEY=<secret>
    export NEXIS_APP_TOKEN=<long-random-secret>
    uvicorn app:app --host 127.0.0.1 --port 8000

TLS reverse proxy gerekir. OpenAI API anahtarı telefona konmaz.
Test için: cd server && pip install -r requirements.txt pytest httpx && pytest -q

Kaynaklar: https://github.com/Jieli-Tech/Android-JL_Health ve https://github.com/Jieli-Tech/Android-JL_Bluetooth

## Güvenlik
Bu depo public. Şirket belgeleri, müşteri verileri, şifreler, erişim anahtarları ve gerçek tahsilat kayıtları yüklenmemeli.
Resmi işlemler, harcamalar, teklif/sözleşmeler ve müşteriye mesaj gönderimi otomatik değil; insan onayı zorunlu.
