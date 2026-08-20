INSERT INTO waha_templates (id, template_key, body, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'alert_notification',
    'PERINGATAN SPAREPART - {plantCode}

Mesin: {machineCode} ({machineName})
Grup: {machineGroup}
Sparepart: {sparepartName}

Konsumsi saat ini telah mencapai {thresholdPercent}% dari batas lifetime.
Jumlah produksi saat ini: {currentCount}

Waktu peringatan: {alertTime}

Segera lakukan pengecekan dan jadwalkan penggantian sparepart.',
    now(),
    now()
);
