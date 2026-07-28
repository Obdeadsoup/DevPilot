ALTER TABLE dp_github_delivery
    ADD KEY idx_github_delivery_processing_scan (processing_status, processing_started_at);
