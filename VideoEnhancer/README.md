# Video Enhancer (Android)

Aplikasi satu tujuan: pilih video, tekan **Proses video**, hasilnya masuk Galeri.
Tanpa batas durasi, resolusi, maupun ukuran file.

## Cara mendapatkan APK (tanpa Play Store)

### Cara 1 — GitHub (paling mudah, tanpa install apa pun)
1. Buat repository baru di GitHub, upload seluruh isi folder ini (termasuk `.github`).
2. Buka tab **Actions** → **Build APK** → **Run workflow**. Tunggu ±5 menit.
3. Unduh `VideoEnhancer-apk` dari hasil run itu (file `VideoEnhancer.apk`).
4. Untuk link unduhan permanen: buat tag, mis. `v1.0`. APK otomatis muncul di halaman **Releases**
   dan link-nya bisa dibagikan lewat web.

### Cara 2 — Android Studio
Buka folder ini → tunggu sync → **Build > Build APK(s)** (pilih varian `release`).
Atau lewat terminal: `gradle :app:assembleRelease` (butuh JDK 17 + Android SDK 35).
Hasil: `app/build/outputs/apk/release/app-release.apk`.

## Install di HP
Buka APK dari browser/file manager → izinkan **Install dari sumber ini** → Install.
Versi baru bisa menimpa versi lama (keystore tetap sama: `app/enhancer.jks`).
Ganti password/keystore sebelum APK dibagikan ke publik.

## Cara pakai
- Ketuk kotak besar untuk memilih video, atau **Bagikan → Video Enhancer** dari Galeri,
  atau seret video dari aplikasi lain (mode layar terpisah/tablet).
- Pilih fitur (semua aktif secara default), tekan **Proses video**.
- Layar boleh dikunci. Hasil tersimpan di `Movies/VideoEnhancer`.

## Cara kerja
1. **Analisis otomatis** — 12 frame sampel diukur: kecerahan (median) dan tingkat noise,
   lalu kekuatan tiap efek ditentukan untuk video itu.
2. **Pipeline GPU per frame** (`EnhanceEffect.kt`):
   denoise bilateral adaptif (lebih kuat di area gelap) → denoise temporal antar-frame →
   sharpen dengan coring dan batas halo → night view (kurva Zero-DCE) → saturasi.
3. Decode dan encode memakai codec hardware (Media3 Transformer). Output HEVC (H.264 jika
   HEVC tidak tersedia), bitrate ±1,2× video asli. Audio tidak diubah.
4. Berjalan sebagai foreground service, jadi video berjam-jam aman di latar belakang.

## Catatan
- Butuh Android 10+ dan ruang kosong ±2× ukuran hasil (berkas sementara + salinan ke Galeri).
- Kecepatan tergantung HP. Resolusi sangat tinggi (mis. 8K) bisa diturunkan otomatis jika
  encoder HP tidak mendukungnya.
- Video HDR dikonversi ke SDR.
