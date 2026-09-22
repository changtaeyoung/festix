-- ------------------------------------------------------------
-- payment.confirming_at: PENDING -> CONFIRMING 전이 시각 기록
-- ------------------------------------------------------------
-- 2단계 확정에서 phase 1(beginConfirm)은 커밋되지만 phase 2가 실패하면
-- 결제 row가 CONFIRMING에 영구히 남을 수 있음. "얼마나 오래 묶여 있었는지"를
-- 알아야 안전망 배치가 이를 정리할 수 있으므로, CONFIRMING 진입 시각을 별도로 기록.
-- paid_at / refunded_at 와 동일한 "이벤트 발생 시점 값 박아두기" 패턴.
-- nullable, CHECK 제약 없음 — 별도 제약 재생성 불필요.
ALTER TABLE payment ADD COLUMN confirming_at TIMESTAMP;
