# ShopeeUnpin

Modul LSPosed: matiin SSL pinning di aplikasi Shopee
(`com.shopee.id`, `com.shopee.lite.id`) biar traffic HTTPS kebaca plaintext
di HTTPToolkit / Reqable / mitmproxy.

## Lapisan bypass

| # | Target | Efek |
|---|--------|------|
| 1 | `TrustManagerImpl.checkTrusted{,Recursive}` (Conscrypt) | Chain validation off, termasuk custom TrustManager yang delegasi ke Conscrypt |
| 2 | `OkHostnameVerifier.verify` (OkHttp) | Hostname check selalu true |
| 3 | `CertificatePinner.check` (OkHttp v3 + v2) | SPKI pin off |
| 4 | `SSLContext.init` | TrustManager custom milik app diganti yang naive |
| 5 | `WebViewClient.onReceivedSslError` | `handler.proceed()` |

Semua hook best-effort: class/method yang nggak ada di ROM/app version
tertentu di-skip + log, tidak crash.

## Install

1. Install APK, aktifkan modul di LSPosed, centang scope
   `com.shopee.lite.id` (dan/atau `com.shopee.id`).
2. Reboot / soft-reboot.
3. Cek log LSPosed: harus ada `ShopeeUnpin: hooking ...`.
4. Capture: traffic Shopee harus plaintext.

Catatan: injeksi LSPosed ke Shopee Lite di beberapa device flaky
(daemon skip tanpa log). Kalau modul tidak ke-load di Lite, coba di
`com.shopee.id` atau pakai scope yang sama persis.

## Build

```sh
./gradlew assembleDebug   # APK: app/build/outputs/apk/debug/
```

Tanpa Android SDK lokal: push ke branch → GitHub Actions build otomatis,
APK ada di Artifacts. Tag `v*` → APK nempel di GitHub Release.

## Verifikasi hook aktif (opsional, root + Frida)

Attach ke proses Shopee, `Module.enumerateExports("libssl.so")` harus ada,
dan handshake TLS baru harus lolos MITM. Atau cukup: traffic login/API
kelihatan plaintext di HTTPToolkit.
