CREATE TABLE loan_limit_batch_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    request_id VARCHAR(36) NOT NULL,
    borrower_id VARCHAR(64) NOT NULL,
    requested_bank_count INT NOT NULL,
    success_count INT NOT NULL,
    failure_count INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_loan_limit_batch_run_request_id (request_id)
);

CREATE TABLE bank_call_result (
    id BIGINT NOT NULL AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    bank_code VARCHAR(64) NOT NULL,
    host VARCHAR(255) NOT NULL,
    url VARCHAR(255) NOT NULL,
    http_status INT NULL,
    success TINYINT(1) NOT NULL,
    response_code VARCHAR(32) NOT NULL,
    response_message VARCHAR(255) NOT NULL,
    approved_limit BIGINT NULL,
    latency_ms BIGINT NOT NULL,
    error_detail VARCHAR(500) NULL,
    request_payload TEXT NOT NULL,
    response_payload TEXT NOT NULL,
    requested_at DATETIME NOT NULL,
    responded_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_bank_call_result_run_id_id (run_id, id),
    KEY idx_bank_call_result_run_id_success (run_id, success),
    KEY idx_bank_call_result_bank_code (bank_code),
    CONSTRAINT fk_bank_call_result_run_id
        FOREIGN KEY (run_id)
            REFERENCES loan_limit_batch_run (id)
            ON DELETE CASCADE
);
