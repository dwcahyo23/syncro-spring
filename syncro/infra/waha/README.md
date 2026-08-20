# WAHA - WhatsApp HTTP API

WAHA (WhatsApp HTTP API) adalah service yang menyediakan REST API untuk WhatsApp, digunakan Syncro untuk mengirim notifikasi dan alert via WhatsApp.

## Konfigurasi

Variabel environment yang dibutuhkan di `.env` (root project):

```env
WAHA_HOST=localhost
WAHA_PORT=3000
WAHA_API_KEY=your-api-key-here
WAHA_DASHBOARD_USERNAME=admin
WAHA_DASHBOARD_PASSWORD=your-dashboard-password
WHATSAPP_SWAGGER_USERNAME=admin
WHATSAPP_SWAGGER_PASSWORD=your-swagger-password
```

## Akses Dashboard & Swagger

Setelah container berjalan:

| UI | URL | Credentials |
|---|---|---|
| Dashboard | `http://localhost:3000/dashboard` | `WAHA_DASHBOARD_USERNAME` / `WAHA_DASHBOARD_PASSWORD` |
| Swagger UI | `http://localhost:3000/` | `WHATSAPP_SWAGGER_USERNAME` / `WHATSAPP_SWAGGER_PASSWORD` |

## Setup Session WhatsApp via Dashboard

Ini adalah cara termudah untuk menghubungkan nomor WhatsApp ke WAHA.

### Langkah 1 — Buka Dashboard

Akses `http://localhost:3000/dashboard` dan login.

### Langkah 2 — Buat Session Baru

1. Klik tombol **"+"** atau **"New Session"**
2. Isi nama session, gunakan `default` untuk session utama Syncro
3. Klik **"Start"**

### Langkah 3 — Scan QR Code

1. Setelah session dibuat, klik session tersebut
2. Akan muncul QR code
3. Buka WhatsApp di HP → **Linked Devices** → **Link a Device**
4. Scan QR code yang ditampilkan
5. Tunggu hingga status berubah menjadi **WORKING**

### Langkah 4 — Verifikasi Session

Status session harus `WORKING` sebelum bisa digunakan. Session tersimpan di Docker volume `waha_sessions` sehingga tidak perlu scan ulang setelah container restart.

## Menggunakan API Key

Semua request ke WAHA API harus menyertakan header:

```
X-Api-Key: <nilai WAHA_API_KEY dari .env>
```

### Contoh: Cek Status Session

```bash
curl http://localhost:3000/api/sessions/default \
  -H "X-Api-Key: Syncro@Waha#2026!DevKey"
```

Response saat session aktif:
```json
{
  "name": "default",
  "status": "WORKING"
}
```

### Contoh: Kirim Pesan Teks

```bash
curl -X POST http://localhost:3000/api/sendText \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: Syncro@Waha#2026!DevKey" \
  -d '{
    "session": "default",
    "chatId": "628123456789@c.us",
    "text": "Notifikasi dari Syncro: sensor aktif"
  }'
```

Format `chatId`: nomor WhatsApp lengkap dengan kode negara tanpa `+`, diikuti `@c.us`.
Contoh: nomor `+62 812-3456-789` → `628123456789@c.us`

## Integrasi dengan Syncro Backend

Backend Syncro mengakses WAHA menggunakan konfigurasi berikut di `application.properties` / `application.yml`:

```yaml
syncro:
  waha:
    base-url: http://${WAHA_HOST}:${WAHA_PORT}
    api-key: ${WAHA_API_KEY}
    session: default
```

Pastikan `WAHA_HOST`, `WAHA_PORT`, dan `WAHA_API_KEY` sudah tersedia di environment saat backend dijalankan.

## Troubleshooting

**Session tidak muncul di dashboard**
- Pastikan container berjalan: `docker compose ps waha`
- Cek log: `docker compose logs waha --tail=50`

**QR code expired**
- QR code berlaku ~60 detik. Klik "Refresh" atau restart session di dashboard.

**Pesan gagal terkirim (status 400/401)**
- Pastikan header `X-Api-Key` benar
- Pastikan session berstatus `WORKING`
- Verifikasi format `chatId` sudah benar (`@c.us` untuk personal, `@g.us` untuk grup)

**Session disconnect setelah restart**
- Session tersimpan di volume `waha_sessions`. Jika volume dihapus, perlu scan QR ulang.
- Untuk backup session: `docker run --rm -v syncro-spring_waha_sessions:/data -v $(pwd):/backup alpine tar czf /backup/waha-sessions-backup.tar.gz /data`
