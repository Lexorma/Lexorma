-- V3 : 서브타입 그룹 변환 전략 CHECK 재정의 (M-7 상속 A·C 긴급 수정, 2026-11-23 — docs/completed/inheritance-strategy-ac-m7.md 9장)
-- 원인 : V2 의 CK_SBTYP_GROUP_CNVR_01 (CNVR_TYPE_CD in ('SINGLE_TABLE', 'TABLE_PER_CONCRETE')) 을 H2 2.4.240 은 상수 집합(ConditionInConstantSet)으로 만들고,
--   그 집합의 비교에 제약을 만든 세션(Flyway 마이그레이션 연결)을 쓴다. 그 연결이 닫히면(연결 풀 교체 등) 이후 모든 삽입이
--   'Check constraint invalid' (23514, 내부 90098 The database has been closed) 로 실패한다 — 테스트 실행 순서에 따라, 그리고 H2 파일 DB 운영에서는
--   V2 를 적용한 프로세스가 마이그레이션 연결을 교체한 뒤 다시 시작할 때까지 A·C 생성이 실패한다. OR 로 쓴 같은 조건도 H2 가 IN 으로 바꿔 같은 문제가 난다.
-- 수정 : 같은 이름의 제약을 CASE 식으로 다시 만든다 (세션에 묶인 상수 집합을 만들지 않는다). 뜻은 같다 — 두 값만 허용.
--   CNVR_TYPE_CD 는 NOT NULL 이라 NULL 은 CHECK 전에 거절된다 (IN 식의 NULL → 알 수 없음 통과 / CASE 식의 NULL → 거절 차이는 드러나지 않는다).
--   PostgreSQL 은 이 문제가 없지만 두 벤더의 제약 정의를 같게 둔다. CK_SBTYP_GROUP_CNVR_02 와 다른 제약·데이터·변환 기록은 바꾸지 않는다.
--   예제(프로젝트 0)의 제약조건 설명(INSP_EXPR_CN)은 V2 가 넣은 값 그대로일 때만 새 정의(PostgreSQL 표기)로 바꾼다 (사용자가 고친 예제는 두지 않는다).
-- V0·V1·V2 는 바꾸지 않는다.

alter table TB_SBTYP_GROUP_CNVR drop constraint CK_SBTYP_GROUP_CNVR_01;
alter table TB_SBTYP_GROUP_CNVR add constraint CK_SBTYP_GROUP_CNVR_01 check (case when CNVR_TYPE_CD = 'SINGLE_TABLE' then true when CNVR_TYPE_CD = 'TABLE_PER_CONCRETE' then true else false end);

update TB_TBL_CNSTRT set INSP_EXPR_CN = '(CASE WHEN ((cnvr_type_cd)::text = ''SINGLE_TABLE''::text) THEN true WHEN ((cnvr_type_cd)::text = ''TABLE_PER_CONCRETE''::text) THEN true ELSE false END)',
    MDFCN_DT = current_timestamp
where PRJ_SN = 0 and CNSTRT_NM = 'CK_SBTYP_GROUP_CNVR_01' and CNSTRT_TYPE_CD = 'CHECK'
  and INSP_EXPR_CN = '((cnvr_type_cd)::text = ANY ((ARRAY[''SINGLE_TABLE''::character varying, ''TABLE_PER_CONCRETE''::character varying])::text[]))'
  and TBL_SN in (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_SBTYP_GROUP_CNVR');
