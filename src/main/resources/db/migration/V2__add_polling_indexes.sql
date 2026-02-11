CREATE INDEX idx_lender_call_result_run_id_id
    ON lender_call_result (run_id, id);

CREATE INDEX idx_lender_call_result_run_id_success
    ON lender_call_result (run_id, success);
