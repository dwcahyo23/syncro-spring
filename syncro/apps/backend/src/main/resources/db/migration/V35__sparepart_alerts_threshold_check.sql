ALTER TABLE sparepart_alerts
    ADD CONSTRAINT chk_sparepart_alerts_threshold_percentage
    CHECK (threshold_percentage BETWEEN 0 AND 100);