# sevOS kurulumu

**Bu işlem projektördeki uygulamaları ve ayarları siler.** Önce orijinal sistem bölümleri bilgisayarına kopyalanır.

Kurucu fabrika ayarlı bir cihazda henüz baştan sona denenmedi. Başlamadan önce [kurtarma rehberini](RECOVERY.md) oku.

## 1. Bilgisayarı hazırla

`python3`, `adb`, `e2fsprogs` ve yaklaşık 3 GB boş yer gerekir.

```bash
# macOS
brew install android-platform-tools e2fsprogs

# Debian veya Ubuntu
sudo apt install adb e2fsprogs python3
```

Sonra bu repoyu indir ve klasöründe bir terminal aç.

## 2. Projektöre bağlan

Birini seç. İkisinde de aynı kurucu çalışır.

### Ağ üzerinden

Projektör ve bilgisayar aynı ağda olmalı. Projektörün IP adresini ağ ayarlarından bul, sonra:

```bash
adb connect 192.168.1.50:3268
adb -s 192.168.1.50:3268 shell su 0 id
```

Test edilen cihazda fabrika yazılımı root ADB'yi 3268 portunda açıyor. Senin cihazında port farklıysa onu yaz. İkinci komut `uid=0(root)` yazmalı.

Kurmak için:

```bash
python3 installer/install.py --device 192.168.1.50:3268
```

### USB kabloyla

Projektörün USB portunu bilgisayara USB-A - USB-A kabloyla bağla, sonra:

```bash
adb devices
```

Projektör listede görünüyorsa gösterilen seri numarasını kullan:

```bash
adb -s SERI_NO shell su 0 id
python3 installer/install.py --device SERI_NO
```

Listede görünmüyorsa cihazının USB portu Android açıkken host modundadır. Test edilen cihazda durum böyle ve kurulum ağ üzerinden yapılıyor. USB ile girilen Loader modu tek başına yetmez: test edilen cihazdaki üretici Loader'ı 32 MiB üstündeki bölümleri okuyamıyor, yani sistemi cihazdan kopyalayamıyor.

Kurucu yazarken kabloyu veya fişi çekme.

## 3. Kurucuyu çalıştır

Kurucu önce cihazı kontrol eder. Uyumlu değilse hiçbir şeyi değiştirmeden durur. Sorulunca `INSTALL` yaz, sonra sırayla:

1. `system`, `system_ext`, `product` ve `odm` bölümlerini (yaklaşık 1,5 GB) `sevos-work/stock/` klasörüne kopyalar ve doğrular.
2. Yeni imajları bilgisayarında oluşturur.
3. Sadece değişen blokları yazar (toplam birkaç MB) ve her bölümü doğrular.
4. `/data` bölümünü siler, çünkü üretici uygulamaları verilerini ve güncellemelerini orada tutuyor.
5. Yeniden başlatır.

**`sevos-work/stock/` klasörünü sakla.** Geri dönüş yolun o.

İlk açılış birkaç dakika sürer. Ana ekran gelince **Ayarlar**'dan Wi-Fi'ye bağlan.

## Seçenekler

| Seçenek | Etkisi |
|---|---|
| `--network-adb` | Kurulumdan sonra ağdaki root ADB'yi açık bırakır. **Ağındaki herkes cihazı kontrol edebilir.** Sonradan ağ üzerinden uygulama yüklemek istiyorsan gerekir. |
| `--no-wipe` | `/data` bölümünü ve üretici uygulamalarının kalıntılarını korur. |
| `--from-dir KLASOR` | `KLASOR/<bolum>.img` dosyalarından kurar, hiçbir cihaza dokunmaz. |
| `--work KLASOR` | Yedek ve derleme için başka klasör kullanır. |
| `--force` | Uyumluluk kontrollerini atlar. Sadece USB ile kurtarabiliyorsan kullan. |

## Sorun giderme

| Mesaj | Yapılacak |
|---|---|
| Projektör ağdayken `No route to host` | `adb kill-server`, sonra `adb start-server` çalıştır ve yeniden bağlan. |
| `no root over ADB` | Bu yazılım ADB üzerinden `su 0` izni vermiyor, kurucu çalışamaz. |
| `this does not look like a supported projector` | Listelenen parçalar yok, sevOS orada işlevlerini kaybeder. |
| `the copy of ... does not match the device` | Hiçbir şey değişmedi. Bağlantı sağlamken yeniden çalıştır. |
| `writing ... failed` | Cihazı yeniden başlatma, önce [kurtarma rehberine](RECOVERY.md) bak. |
| `sevOS is already installed` | Kurucu yalnızca fabrika yazılımında çalışır. |
