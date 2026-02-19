ALTER TABLE loan_limit_batch_run
    CHANGE COLUMN requested_lender_count requested_bank_count INT NOT NULL;

RENAME TABLE lender_call_result TO bank_call_result;

ALTER TABLE bank_call_result
    CHANGE COLUMN lender_code bank_code VARCHAR(64) NOT NULL;

ALTER TABLE bank_call_result
    RENAME INDEX idx_lender_call_result_run_id_id TO idx_bank_call_result_run_id_id;

ALTER TABLE bank_call_result
    RENAME INDEX idx_lender_call_result_run_id_success TO idx_bank_call_result_run_id_success;

ALTER TABLE bank_call_result
    RENAME INDEX idx_lender_call_result_lender_code TO idx_bank_call_result_bank_code;

ALTER TABLE bank_call_result
    DROP FOREIGN KEY fk_lender_call_result_run_id;

ALTER TABLE bank_call_result
    ADD CONSTRAINT fk_bank_call_result_run_id
        FOREIGN KEY (run_id)
            REFERENCES loan_limit_batch_run (id)
            ON DELETE CASCADE;
