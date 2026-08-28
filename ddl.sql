-- ============================================================
-- Festix DDL (PostgreSQL 16)
-- 판단 지점은 각 CREATE TABLE 위/옆에 "-- [판단]" 주석으로 표시
-- ============================================================

-- ------------------------------------------------------------
-- users
-- [판단] 테이블명을 "user"가 아닌 "users"로 지음.
--        "user"는 PostgreSQL 예약어라서 그대로 쓰면 매번 큰따옴표로
--        감싸야 하고(SELECT * FROM "user"), MyBatis 매퍼 XML에서도
--        계속 실수 유발 지점이 됨. 관습적으로도 복수형 테이블명을
--        많이 씀.
-- ------------------------------------------------------------
CREATE TABLE users (
                       id             BIGSERIAL PRIMARY KEY,
                       custom_id      VARCHAR(50)  NOT NULL,   -- 로그인용 ID
                       password       VARCHAR(255) NOT NULL,   -- BCrypt 해시 저장 (평문 절대 금지)
                       name           VARCHAR(100) NOT NULL,
                       phone          VARCHAR(20)  NOT NULL,
                       created_date   TIMESTAMP    NOT NULL DEFAULT now(),
                       updated_date   TIMESTAMP    NOT NULL DEFAULT now(),

                       CONSTRAINT uq_users_custom_id UNIQUE (custom_id)
    -- [판단] custom_id에 UNIQUE 제약 추가. 로그인 ID 중복 가입을
    --        애플리케이션 레벨(SELECT로 먼저 확인)에만 맡기면,
    --        동시에 같은 ID로 가입 요청이 몰릴 때(레이스 컨디션)
    --        중복이 뚫릴 수 있음. DB 제약이 최후 방어선.
);

-- ------------------------------------------------------------
-- festival
-- ------------------------------------------------------------
CREATE TABLE festival (
                          id           BIGSERIAL PRIMARY KEY,
                          name         VARCHAR(200) NOT NULL,
                          event_date   DATE         NOT NULL,
                          location     VARCHAR(300) NOT NULL
);

-- ------------------------------------------------------------
-- seat
-- [판단] status를 Postgres 네이티브 ENUM 타입이 아니라
--        VARCHAR + CHECK 제약으로 구현.
--        네이티브 ENUM은 값 하나 추가/삭제할 때마다
--        ALTER TYPE이 필요하고 트랜잭션 제약이 까다로움.
--        VARCHAR + CHECK이 운영 중 변경에 더 유연함
--        (이 프로젝트 규모에선 성능 차이도 무시 가능한 수준).
-- ------------------------------------------------------------
CREATE TABLE seat (
                      id           BIGSERIAL PRIMARY KEY,
                      festival_id  BIGINT       NOT NULL REFERENCES festival(id),
                      price        DECIMAL(10,2) NOT NULL,
    -- [판단] DECIMAL(10,2): 정수부 8자리(최대 9,999만원대)면
    --        페스티벌 좌석 가격으로 충분히 넉넉함. FLOAT/DOUBLE은
    --        금액 계산에 부동소수점 오차가 생길 수 있어 배제.
                      status       VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',

                      CONSTRAINT chk_seat_status CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD'))
);

-- [판단] 좌석 그리드 조회는 항상 "특정 festival의 좌석들"을 status와
--        함께 필터링하므로, 이 조합에 복합 인덱스를 걸어둠.
CREATE INDEX idx_seat_festival_status ON seat (festival_id, status);

-- ------------------------------------------------------------
-- reservation (예약 시도, 묶음 단위)
-- ------------------------------------------------------------
CREATE TABLE reservation (
                             id             BIGSERIAL PRIMARY KEY,
                             user_id        BIGINT    NOT NULL REFERENCES users(id),
                             created_date   TIMESTAMP NOT NULL DEFAULT now(),
                             end_ttl        TIMESTAMP NOT NULL,
                             is_extended    BOOLEAN   NOT NULL DEFAULT false
);

-- [판단] 마이페이지에서 "내 예약 목록"을 조회할 때 user_id로 필터링하므로 인덱스.
CREATE INDEX idx_reservation_user ON reservation (user_id);

-- [판단] 안전망 배치가 "WHERE end_ttl < NOW()"로 스캔하므로 인덱스.
--        다만 이 배치는 1분 주기라 인덱스 없어도 치명적이진 않음 —
--        데이터 양이 커지기 전까지는 없어도 되지만, 미리 걸어둠.
CREATE INDEX idx_reservation_end_ttl ON reservation (end_ttl);

-- ------------------------------------------------------------
-- reservation_item (개별 좌석 단위, reservation의 자식)
-- [판단] seat_id에 UNIQUE 제약을 걸지 않음.
--        같은 좌석이 시간이 지나면서 여러 번 선점→만료→재선점될 수
--        있어서, reservation_item 테이블 전체로 보면 같은 seat_id가
--        여러 row에 반복해서 나타나는 게 정상. "지금 이 순간 유효한
--        선점인지"는 seat.status(HELD/SOLD)로 판단하지, 이 테이블의
--        유니크 제약으로 판단하지 않음 — 애초에 동시성 제어는
--        seat 테이블에 거는 비관적 락(SELECT FOR UPDATE)이 담당.
-- ------------------------------------------------------------
CREATE TABLE reservation_item (
                                  id               BIGSERIAL PRIMARY KEY,
                                  reservation_id   BIGINT NOT NULL REFERENCES reservation(id) ON DELETE CASCADE,
    -- [판단] ON DELETE CASCADE: reservation(부모)이 삭제되면
    --        reservation_item(자식)도 같이 삭제. 다만 이 프로젝트는
    --        예약을 물리적으로 DELETE하는 경우가 없을 걸로 설계됨
    --        (만료/취소는 전부 상태값 UPDATE) — 그래도 혹시 모를
    --        관리자 강제 삭제 등 예외 상황을 대비한 안전장치로 걸어둠.
                                  seat_id          BIGINT NOT NULL REFERENCES seat(id)
    -- seat는 CASCADE 안 함: 좌석 자체를 지울 일은 거의 없고,
    -- 지운다면 그건 신중한 별도 절차여야 함 (기본 RESTRICT 유지).
);

CREATE INDEX idx_reservation_item_reservation ON reservation_item (reservation_id);
CREATE INDEX idx_reservation_item_seat ON reservation_item (seat_id);

-- ------------------------------------------------------------
-- payment
-- ------------------------------------------------------------
CREATE TABLE payment (
                         id               BIGSERIAL PRIMARY KEY,
                         reservation_id   BIGINT NOT NULL REFERENCES reservation(id),
                         amount           DECIMAL(12,2) NOT NULL,
    -- [판단] DECIMAL(12,2): seat.price보다 자릿수를 넉넉히 잡음.
    --        여러 좌석 합산 금액이라 단일 좌석 가격보다 커질 수 있어서.
                         status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                         cancel_reason    VARCHAR(500),
    -- [판단] nullable로 둠. PENDING/COMPLETED/REFUNDED 상태에서는 값이 없고,
    --        CANCELED일 때만 채워짐 — "EXPIRED_BY_EVENT" 등
    --        트리거 구분 문자열이 여기 들어감.
                         paid_at          TIMESTAMP,
                         refunded_at      TIMESTAMP,
    -- [판단] REFUNDED 전용 시각 컬럼을 별도로 둠. CANCELED(결제 미완료 취소)와
    --        REFUNDED(결제 완료 후 환불)는 발생 시점과 의미가 다른 이벤트라
    --        같은 컬럼에 욱여넣지 않음. 부분 환불은 스코프에서 제외 —
    --        예약(reservation) 단위 all-or-nothing으로 전량 환불만 지원.

    -- [판단] CONFIRMING: 결제 확정을 2단계로 나눈 중간 상태.
    --        PENDING -> CONFIRMING(즉시 커밋) -> COMPLETED. 두 트랜잭션 사이 간격은
    --        향후 PG 응답 대기(모의) 단계용 seam. 만료/안전망 취소는 PENDING만 대상.
                         CONSTRAINT chk_payment_status CHECK (status IN ('PENDING', 'CONFIRMING', 'CANCELED', 'COMPLETED', 'REFUNDED'))
);

CREATE INDEX idx_payment_reservation ON payment (reservation_id);