# Festix — Architecture Learning & Q&A Log

> 목적: 코드 리뷰하면서 던진 질문, 배운 개념, 해결하려던 문제, 그리고 나중에 스스로 의문을 제기해서 바뀐 설계 결정까지 기록. 특히 마지막 카테고리(직접 검증해서 바꾼 것)는 "이 아키텍처를 왜 이렇게 짰는지 스스로 검증했다"는 근거가 되므로 그 자체로 스펙이자 면접 자료가 될 수 있음.
> Claude Code가 자동으로 참조하는 문서 아님 (CLAUDE.md 참조 목록에 없음) — 순수 개인 학습 기록.

## 기록 형식
각 항목: 어떤 코드를 보다가 나온 질문인지 / 질문 / 답(이해한 내용) / 상태(이해완료 · 재검토 필요 · 검토 후 변경됨→반영) / 필요시 "설계 선택 지점"(정답이 있는 게 아니라 트레이드오프가 있어서 나중에 판단이 바뀔 수 있는 것) 별도 표시.

---

## 2026-08-23 — User 엔티티 리뷰 (Gemini와 Q&A)

**계기**: Claude Code가 만든 `User` 엔티티(Lombok 생성자, `@GeneratedValue`, `@OneToMany`, `@CreationTimestamp`) 코드를 리뷰하다가 궁금한 점이 생겨서 질문.

### Q1. `@NoArgsConstructor(access = AccessLevel.PROTECTED)`와 `@AllArgsConstructor(access = AccessLevel.PRIVATE)`는 각각 뭘 하는 어노테이션인가?
- **이해한 내용**: JPA는 DB에서 데이터를 꺼내 객체로 조립할 때 파라미터 없는 기본 생성자가 반드시 필요함. 그런데 외부에서 `new User()`처럼 필수값 없이 무분별하게 생성하면 나중에 에러로 이어질 수 있어서, `PROTECTED`로 접근을 제한해 JPA 내부 엔진만 이 생성자를 쓸 수 있게 막아둠. `AllArgsConstructor`는 `@Builder`가 내부적으로 쓰는 "모든 필드를 받는 생성자"인데, 개발자가 이걸 직접 `new User(1L, "id", "pw", ...)` 식으로 호출하면 파라미터 순서를 헷갈려 잘못된 값이 들어갈 위험이 있음. 그래서 `PRIVATE`으로 숨기고, 필드명이 명시되어 안전한 `User.builder().name(...).build()` 방식으로만 생성하도록 강제.
- **상태**: 이해 완료

### Q2. `@GeneratedValue(strategy = GenerationType.IDENTITY)`는 무엇인가?
- **이해한 내용**: PK 생성을 DB에 위임하는 전략. PostgreSQL의 `SERIAL`처럼, INSERT 시점에 DB가 순번을 자동으로 매김.
- **꼬리질문 — 유저가 탈퇴하면 그 ID는 나중에 재사용되나? (예: 1,2,3 중 2번 탈퇴 → 신규 가입자가 2번을 다시 받는지)**: 아니다. 관계형 DB의 auto-increment는 삭제된 번호를 다시 채우지 않고 항상 전진만 함 → 신규 가입자는 4번. 이유: 동시 가입 처리 중 빈 번호를 찾는 과정에서 성능 저하·버그 위험이 생기고, 과거 탈퇴자의 기록이 새 유저의 기록과 혼동되는 대참사를 막기 위함.
- **상태**: 이해 완료
- **추가로 열어둘 지점** (질문엔 없었지만 이 프로젝트 특성상 나중에 다시 볼 만한 것): `IDENTITY` 전략은 Hibernate가 INSERT 직후 즉시 생성된 ID 값을 알아야 해서 **JDBC batch insert를 못 씀** (한 건씩 INSERT됨). 이 프로젝트의 핵심 증명 포인트가 "트래픽 스파이크 대응"인데, 만약 나중에 대량 시드 데이터 삽입이나 배치 처리 성능이 이슈가 되면 `SEQUENCE` 전략(배치 가능)과 비교해볼 여지가 있음. 지금 스코프에선 `IDENTITY`로 충분하지만, 부하테스트 단계(로드맵 8단계)에서 한 번쯤 짚어볼 만한 포인트로 남겨둠.

### Q3. `@OneToMany`/`@ManyToOne` 방향 이해가 맞는지 (현재 클래스가 One, 연관 클래스가 Many?)
- **이해한 내용**: 맞음. `User`(One)가 여러 `Reservation`(Many)을 가짐 → `User`에 `@OneToMany`. 반대편 `Reservation`엔 `@ManyToOne`. `mappedBy = "user"`는 외래키 주도권이 `Reservation.user` 필드에 있다는 뜻.
- **꼬리질문 — `@OneToMany`를 적었으면 반대편에 `@ManyToOne`을 무조건 적어야 하나? 안 적으면 에러 나나?**: 무조건은 아님 (단방향 매핑도 가능). 하지만 지금처럼 `mappedBy = "user"`를 명시한 이상, `Reservation`에 `user`라는 이름의 필드(`@ManyToOne`)가 없으면 짝을 못 찾아 **실행 시 에러 발생**. `mappedBy` 없이 단방향으로 가는 것도 가능하지만, 1:N 관계에서 그렇게 하면 불필요한 중간 테이블이 생기거나 쿼리 성능이 떨어질 수 있어, 지금처럼 양방향 매핑(`Reservation`에 `@ManyToOne` 명시)이 실무 권장 방식.
- **상태**: 이해 완료
- **설계 선택 지점 (추후 재검토 가능)**: 지금은 양방향 매핑으로 확정했지만, 실제 구현하면서 "마이페이지 조회를 `Reservation` 기준 `WHERE user_id = ?`로 바로 하지, `User` 엔티티를 거쳐서 조회할 일이 거의 없다"는 게 확인되면, `User`의 `@OneToMany` 자체를 없애고 단방향(`Reservation → User`만)으로 단순화하는 걸 고려할 수 있음. 실제 구현 시점에 판단하기로.

### Q4. `@CreationTimestamp`/`@UpdateTimestamp`는 무엇인가?
- **이해한 내용**: Hibernate 전용 어노테이션. INSERT/UPDATE 시점에 각각 시간을 자동으로 채워줌. 매번 `setCreatedDate(LocalDateTime.now())` 직접 호출할 필요 없음.
- **상태**: 이해 완료

---

---

## 2026-08-23 — 엔티티 매핑 설계 리뷰 (Claude Code 설명)

**계기**: Claude Code가 엔티티 매핑 설계 결정을 설명하며 알려준 내용 중, 처음 접한 개념 두 가지를 정리.

### Q1. `FetchType.LAZY`가 N+1을 어떻게 회피하는가?
- **이해한 내용**: 정확히는 LAZY 자체가 N+1을 막아주는 게 아니라, LAZY가 N+1이 생길 수 있는 "조건"임. N+1을 진짜로 피하는 방법은 **LAZY를 기본값으로 깔아두고, 연관 데이터가 실제로 필요한 특정 쿼리에서만 fetch join으로 한 번에 가져오는 것**.
    - 예: `reservationRepository.findByUserId(userId)`로 예약 10건 조회 후, 각 예약마다 `r.getReservationItems()`(LAZY), 각 item마다 `item.getSeat()`(LAZY)를 순회하면 → 1(초기) + 10(items) + 20(seats) = 31개 쿼리 발생.
    - 해결: `JOIN FETCH r.reservationItems ri JOIN FETCH ri.seat` 형태로 fetch join하면 쿼리 1개로 끝남.
    - 반대로 처음부터 EAGER로 깔면, 연관 데이터가 필요 없는 단순 조회도 항상 JOIN이 붙어서 매번 무거워지고, 여러 EAGER 컬렉션이 겹치면 결과가 기하급수적으로 부풀어 오르는(Cartesian product) 문제도 생길 수 있음.
- **상태**: 이해 완료

### Q2. Footgun — `Seat`에 `ReservationItem`으로 가는 역방향 `@OneToMany`를 안 만든 이유
- **처음 접한 용어**: "footgun" — 겉보기엔 편리해 보이지만 직관적으로 쓰면 틀린 결과가 나오는 함정 API를 가리키는 개발자 은어.
- **이해한 내용**: 만약 `Seat`에 `@OneToMany(mappedBy = "seat") List<ReservationItem>`을 만들었다면, `seat.getReservationItems().get(0)` 같은 코드로 "지금 이 좌석을 누가 쥐고 있는지" 확인하고 싶어질 수 있음. 근데 `reservation_item`은 append-only 히스토리 로그로 설계했기 때문에(좌석 하나가 선점→만료→재선점을 반복하며 여러 row가 쌓임), 이 리스트엔 과거 시도 전부가 들어있고 `.get(0)`이 "현재 유효한 선점"이라는 보장이 전혀 없음.
    - "지금 이 좌석이 누구 것인지"는 오직 `seat.status`(AVAILABLE/HELD/SOLD)로만 판단해야 한다는 게 이미 정한 원칙 (schema-reference.md 참고). 편리해 보이는 getter가 있으면 나중에 이 원칙을 깜빡하고 잘못된 지름길을 쓸 위험이 생기므로, 아예 이 역방향 매핑 자체를 안 만들어서 위험한 경로를 원천 차단.
- **상태**: 이해 완료

---

---

## 2026-08-23 — orphanRemoval 관련 논의 (설계 확장)

**계기**: `orphanRemoval`이 뭔지 궁금해서 물어보다가, "결제 전 좌석 여러 개 중 하나만 빼고 싶을 때" 시나리오로 이어짐.

### Q. `orphanRemoval = true`가 정확히 뭘 하는 옵션인가? 우리 프로젝트에서 언제 쓰이는 애인가? 환불 처리 때 쓰이는 건가?
- **이해한 내용**: 부모(`reservation`)는 그대로 살아있는데, 자식 컬렉션에서 특정 항목만 `.remove()`로 빼면 그 항목이 DB에서도 자동으로 물리 삭제됨. (부모 전체가 삭제될 때 자식이 같이 삭제되는 `CascadeType.REMOVE`/`ON DELETE CASCADE`와는 별개 개념.)
- **환불 처리 때 쓰이는 게 아님**: 환불은 `seat`/`payment` 상태값만 UPDATE하고 `reservation_item`은 건드리지 않음 (히스토리 보존).
- **상태**: 이해 완료

### 꼬리질문 — "좌석 1개 이상 선택 후 결제 창까지 갔다가, 변심으로 결제 안 하고 그중 좌석 하나만 빼는 경우"가 orphanRemoval이 쓰이는 지점 아닌가?
- **시나리오 자체는 맞음** — 이건 지금까지 설계에 없던 새로운 흐름이고, 추가할 가치가 있음 (아래 결정 참고).
- **근데 `orphanRemoval`로 구현하는 건 틀림**: `reservation_item`은 "append-only 히스토리, 물리 삭제 금지"로 이미 설계해뒀는데, `orphanRemoval`은 정확히 그 반대(컬렉션에서 빠지면 실제로 DELETE)라서 원칙과 정면 충돌.
- **추가로 발견한 footgun**: 지금 당장 `.remove()`를 안 쓰더라도 `orphanRemoval = true`가 설정되어 있다는 것 자체가 위험 — 나중에 누가 실수로 `.remove()`를 호출하면 조용히 히스토리가 삭제됨. `Seat → ReservationItem` 역방향 매핑을 아예 안 만든 것과 같은 종류의 함정.
- **결정된 해결 방식**: `orphanRemoval = false`로 끄고, 좌석 제외는 TTL 만료/환불과 동일한 조건부 UPDATE(`WHERE status='HELD'`)로 좌석만 풀어주고 `reservation_item` row는 그대로 둠. "지금 유효한지"는 항상 `seat.status`로만 판단하므로 row가 남아있어도 무해함.
- **all-or-nothing 원칙과의 관계**: 충돌 아님 — all-or-nothing은 "최초 락 획득 시도"의 원자성 규칙이고, 이건 이미 성공적으로 선점한 좌석을 사용자가 나중에 스스로 줄이는 별개의 흐름.
- **상태**: 검토 후 설계에 반영 → `docs/festix-schema-reference.md`의 "Reservation Lifecycle" 섹션에 추가함.

---

## 향후 추가 규칙
- 새로운 엔티티/코드 리뷰하다가 나온 질문은 위와 같은 형식으로 이 문서 아래에 계속 추가.
- 나중에 "다시 생각해보니 이렇게 바꿔야겠다"는 판단이 서면, 원래 항목 아래에 `**변경됨 (날짜)**:` 항목을 추가하고, 실제로 스키마/코드가 바뀐 경우엔 `docs/festix-schema-reference.md`에도 반영.