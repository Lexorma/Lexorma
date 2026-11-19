-- 테스트 전용 (application-test.yml 의 spring.flyway.locations) : 예전 V1 의 기본 관리자 계정(admin/admin, 2026-11-19 운영 초기 데이터에서 제거)을 넣는다.
-- 통합 테스트는 이 계정으로 로그인한다 (초기 비밀번호 → 로그인 전 비밀번호 변경 → 로그인). 최초 관리자 계정 만들기는 이 콜백 없이 띄운
-- InitialSetupHttpTest 가 확인한다. Flyway 는 migrate 할 때마다 이 콜백을 실행하므로 이미 있으면 넣지 않는다 (H2·PostgreSQL 공통 SQL).
insert into TB_ACNT(ACNT_SN, PSWD, USER_ID, USER_NM, PSWD_CHG_YN, RGTR_ID, MDFR_ID, ACTVTN_YN)
select 0, '{bcrypt}$2a$10$GN1YfMyJLcWhDuslP6P/UuqRwIfJk2VF5tl9mXsRjLJ18ivQfIAoW', 'admin', 'Admin', false, 'SYSTEM', 'SYSTEM', true
where not exists (select 1 from TB_ACNT where ACNT_SN = 0 or USER_ID = 'admin');
insert into TB_ACNT_ROLE(ACNT_SN, ROLE_SN)
select 0, 0 where exists (select 1 from TB_ACNT where ACNT_SN = 0) and not exists (select 1 from TB_ACNT_ROLE where ACNT_SN = 0);
update TB_PRJ set MNGR_ACNT_SN = 0 where PRJ_SN = 0 and MNGR_ACNT_SN is null and exists (select 1 from TB_ACNT where ACNT_SN = 0);
insert into TB_PRJ_MBR(ACNT_SN, PRJ_MBR_SN, PRJ_SN, RGTR_ID, MDFR_ID, ACTVTN_YN)
select 0, 0, 0, 'SYSTEM', 'SYSTEM', true
where exists (select 1 from TB_ACNT where ACNT_SN = 0) and not exists (select 1 from TB_PRJ_MBR where PRJ_MBR_SN = 0 or (ACNT_SN = 0 and PRJ_SN = 0));
insert into TB_PST_ACNT(PST_SN, ACNT_SN)
select 1, 0 where exists (select 1 from TB_ACNT where ACNT_SN = 0) and exists (select 1 from TB_PST where PST_SN = 1)
  and not exists (select 1 from TB_PST_ACNT where PST_SN = 1 and ACNT_SN = 0);
