CREATE TABLE dp_outbox_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    event_key VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    schema_version INT NOT NULL,
    payload_json JSON NOT NULL,
    processing_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(6) NULL,
    processing_started_at DATETIME(6) NULL,
    processed_at DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    last_error_message VARCHAR(500) NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_key (event_key),
    KEY idx_outbox_retry_scan (processing_status, next_retry_at, id),
    KEY idx_outbox_stale_scan (processing_status, processing_started_at, id),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_id, occurred_at, id),
    KEY idx_outbox_event_type (event_type, occurred_at, id),
    CONSTRAINT chk_outbox_status CHECK (
        processing_status IN ('PENDING', 'PROCESSING', 'RETRY_WAIT', 'PROCESSED', 'DEAD')
    ),
    CONSTRAINT chk_outbox_retry_count CHECK (retry_count >= 0),
    CONSTRAINT chk_outbox_version CHECK (version >= 0),
    CONSTRAINT chk_outbox_schema_version CHECK (schema_version > 0),
    CONSTRAINT chk_outbox_identity CHECK (aggregate_id > 0 AND CHAR_LENGTH(event_key) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE dp_notification
    DROP CHECK chk_notification_type,
    ADD CONSTRAINT chk_notification_type CHECK (
        notification_type IN (
            'TASK_DUE_SOON', 'TASK_OVERDUE', 'TASK_OVERDUE_ESCALATED',
            'TASK_REVIEW_TIMEOUT', 'PULL_REQUEST_REVIEW_TIMEOUT',
            'TASK_ASSIGNED', 'TASK_UNASSIGNED', 'TASK_SUBMITTED_FOR_REVIEW',
            'TASK_CHANGES_REQUESTED', 'TASK_COMPLETED', 'TASK_REOPENED'
        )
    );
