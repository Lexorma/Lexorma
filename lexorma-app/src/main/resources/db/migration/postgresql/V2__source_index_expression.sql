-- AI-2b : 원본 인덱스의 표현식·정렬·부분 인덱스 조건 (PostgreSQL 수집)
-- 인덱스 구성 요소는 컬럼 또는 표현식 하나다 : 표현식 요소는 출처 컬럼 일련번호가 없다.
-- 이미 적용된 V0·V1 을 고치지 않고 새 버전으로 더한다 (기존 DB 의 체크섬을 바꾸지 않는다).

alter table TB_SRC_IDX add column CND_CN TEXT;
alter table TB_SRC_IDX_COL add column EXPR_CN TEXT;
alter table TB_SRC_IDX_COL add column SORT_CD varchar(20);
alter table TB_SRC_IDX_COL alter column SRC_COL_SN drop not null;

comment on column TB_SRC_IDX.CND_CN is '조건 내용';
comment on column TB_SRC_IDX_COL.EXPR_CN is '표현식 내용';
comment on column TB_SRC_IDX_COL.SORT_CD is '정렬 코드';

-- ---------- 예제 : 데이터 사전 (용어·용어 단어. 단어는 V1 에 있다) ----------
insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, PBADMS_STD_CD_NM, JRSDC_INST_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM'), 0, 1, '조건_내용', '부분 인덱스 조건식 원문.', 'CND_CN', null, null, (select STD_DMN_SN from TB_STD_DMN where PRJ_SN = 0 and DMN_NM = '내용T'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '조건_내용'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '조건'), 1, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '조건_내용'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '내용'), 2, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, PBADMS_STD_CD_NM, JRSDC_INST_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM'), 0, 1, '표현식_내용', '인덱스 구성 요소의 표현식 원문.', 'EXPR_CN', null, null, (select STD_DMN_SN from TB_STD_DMN where PRJ_SN = 0 and DMN_NM = '내용T'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '표현식_내용'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '표현식'), 1, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '표현식_내용'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '내용'), 2, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, PBADMS_STD_CD_NM, JRSDC_INST_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM'), 0, 1, '정렬_코드', '인덱스 구성 요소의 정렬 방식 (DESC, NULLS FIRST, INCLUDE 등). 기본(오름차순)은 비워 둔다.', 'SORT_CD', null, null, (select STD_DMN_SN from TB_STD_DMN where PRJ_SN = 0 and DMN_NM = '코드V20'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '정렬_코드'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '정렬'), 1, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '정렬_코드'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '코드'), 2, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');

-- ---------- 예제 : 물리 모델 (TB_COL) ----------
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX'), 'TB_SRC_IDX', 'CND_CN', '조건 내용', 'TEXT', null, null, null, true, null, 7, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '조건_내용'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL'), 'TB_SRC_IDX_COL', 'EXPR_CN', '표현식 내용', 'TEXT', null, null, null, true, null, 5, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '표현식_내용'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL'), 'TB_SRC_IDX_COL', 'SORT_CD', '정렬 코드', 'VARCHAR', 20, null, null, true, null, 6, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '정렬_코드'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
-- 표현식 요소는 출처 컬럼이 없다
update TB_COL set NULL_ALWNC_YN = true, ESNTL_YN = false where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and COL_NM = 'SRC_COL_SN';

-- ---------- 예제 : 논리 모델 (속성) ----------
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'LEXORMA') and ENTT_NM = '출처 인덱스'), '조건 내용', null, null, false, 7, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '조건_내용'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'LEXORMA') and ENTT_NM = '출처 인덱스 컬럼'), '표현식 내용', null, null, false, 5, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '표현식_내용'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'LEXORMA') and ENTT_NM = '출처 인덱스 컬럼'), '정렬 코드', null, null, false, 6, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '정렬_코드'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
update TB_LGCL_ATRB set ESNTL_YN = false where ATRB_NM = '출처 컬럼 일련번호'
  and LGCL_ENTT_SN = (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'LEXORMA') and ENTT_NM = '출처 인덱스 컬럼');

-- ---------- 예제 : 논리 ↔ 물리 매핑 (V1 5) 과 같은 규칙 : 새 속성만 이어진다) ----------
insert into TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)
select nextval('SQ_ATRB_COL_MPNG'), mp.ENTT_TBL_MPNG_SN, a.LGCL_ATRB_SN, c.COL_SN, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM'
from TB_ENTT_TBL_MPNG mp
join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = mp.LGCL_ENTT_SN
join TB_LGCL_MDL m on m.LGCL_MDL_SN = e.LGCL_MDL_SN and m.PRJ_SN = 0 and m.MDL_NM = 'LEXORMA'
join TB_TBL t on t.TBL_SN = mp.TBL_SN and t.TBL_KORN_NM = e.ENTT_NM
join TB_LGCL_ATRB a on a.LGCL_ENTT_SN = e.LGCL_ENTT_SN
join TB_COL c on c.TBL_SN = t.TBL_SN and c.STD_TRM_SN = a.STD_TRM_SN
where mp.PRJ_SN = 0
  and (select count(*) from TB_LGCL_ATRB a2 where a2.LGCL_ENTT_SN = e.LGCL_ENTT_SN and a2.STD_TRM_SN = a.STD_TRM_SN) = 1
  and (select count(*) from TB_COL c2 where c2.TBL_SN = t.TBL_SN and c2.STD_TRM_SN = a.STD_TRM_SN) = 1
  and not exists (select 1 from TB_ATRB_COL_MPNG x where x.LGCL_ATRB_SN = a.LGCL_ATRB_SN)
  and not exists (select 1 from TB_ATRB_COL_MPNG x where x.COL_SN = c.COL_SN);
