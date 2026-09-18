-- status='FAILED' 에 두 가지가 섞여 있어 구분이 안 됐다.
--   1. 은행을 다 호출했는데 성공이 0     -> 부하 신호   (fail_reason NULL)
--   2. fan-out 자체가 예외로 중단        -> 코드·설정 문제 (fail_reason 있음)
-- 측정 표에서 2번이 1번처럼 보이면 버그가 천장으로 읽힌다.
-- markRunFailed 가 이미 reason 을 받고 있었는데 로그로만 흘리고 있었다.
ALTER TABLE loan_limit_batch_run
    ADD COLUMN fail_reason VARCHAR(500) NULL;
