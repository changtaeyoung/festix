-- ------------------------------------------------------------
-- payment.status: add CONFIRMING
-- ------------------------------------------------------------
-- 결제 확정을 2단계로 분리: PENDING -> CONFIRMING (즉시 커밋) -> COMPLETED.
-- 두 트랜잭션 사이의 간격은 향후 PG 응답 대기(모의) 단계를 끼워 넣기 위한 seam.
-- status는 네이티브 ENUM이 아니라 VARCHAR + CHECK라, 값 추가는 제약 재생성으로 처리.
ALTER TABLE payment DROP CONSTRAINT chk_payment_status;
ALTER TABLE payment ADD CONSTRAINT chk_payment_status
    CHECK (status IN ('PENDING', 'CONFIRMING', 'CANCELED', 'COMPLETED', 'REFUNDED'));
