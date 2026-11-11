-- META-MORA 프로젝트(PRJ_SN 0) 예제에 M-7 매핑 테이블 2개(TB_ENTT_TBL_MPNG, TB_ATRB_COL_MPNG)를 넣고, MetaMora 자체 테이블·컬럼 전체에 COMMENT 를 단다.
--   1) 데이터 사전 : 단어 `매핑`(MPNG), 용어 2개와 용어-단어 연결   2) 물리 모델 예제 : 테이블 2 · 컬럼 16 · FK 관계 5
--   3) 논리 모델 예제 : 엔터티 2 · 속성 16 · 주 식별자 2 · 관계 5 (V1 과 같은 규칙)   4) COMMENT : 테이블 = 한글명, 컬럼 = 한글 용어 (예제 물리 모델의 한글명·컬럼 설명과 같다)
-- ID 는 시퀀스에서 받고 부모는 이름으로 찾는다 (V1 과 같다). 논리·물리 예제를 매핑 테이블로 잇지는 않는다 — 매핑은 명시적으로 등록한 것만 둔다.
-- 테이블·컬럼을 추가하는 마이그레이션은 같은 파일에서 COMMENT 와 이 예제(물리·논리)를 함께 넣는다 (V0SeedMigrationTest 가 실제 스키마와 비교한다).

-- ---------- 1) 데이터 사전 ----------

insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_WORD'), 0, 1, '매핑', 'MPNG', 'Mapping', '서로 다른 두 대상을 짝지어 연결한 것.', false, null, true, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, PBADMS_STD_CD_NM, JRSDC_INST_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM'), 0, 1, '엔터티_테이블_매핑_일련번호', '엔터티 테이블 매핑 일련번호.', 'ENTT_TBL_MPNG_SN', null, null, (select STD_DMN_SN from TB_STD_DMN where PRJ_SN = 0 and DMN_NM = '일련번호L'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '엔터티'), 1, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '테이블'), 2, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '매핑'), 3, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '일련번호'), 4, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, PBADMS_STD_CD_NM, JRSDC_INST_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM'), 0, 1, '속성_컬럼_매핑_일련번호', '속성 컬럼 매핑 일련번호.', 'ATRB_COL_MPNG_SN', null, null, (select STD_DMN_SN from TB_STD_DMN where PRJ_SN = 0 and DMN_NM = '일련번호L'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '속성_컬럼_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '속성'), 1, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '속성_컬럼_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '컬럼'), 2, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '속성_컬럼_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '매핑'), 3, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_STD_TRM_WORD'), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '속성_컬럼_매핑_일련번호'), (select STD_WORD_SN from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '일련번호'), 4, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');

-- ---------- 2) 물리 모델 예제 ----------

-- 엔터티 테이블 매핑 (TB_ENTT_TBL_MPNG)
insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, TBL_KORN_NM, TBL_EXPLN, MSTR_TBL_YN, SORT_ORD, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_TBL'), 0, (select SCHM_SN from TB_SCHM where PRJ_SN = 0 and BSC_YN = true), 'TB_ENTT_TBL_MPNG', '엔터티 테이블 매핑', 'MetaMora 엔터티 테이블 매핑 정보', false, 36, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'ENTT_TBL_MPNG_SN', '엔터티 테이블 매핑 일련번호', 'BIGINT', null, null, null, false, null, 1, false, null, true, false, true, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'PRJ_SN', '프로젝트 일련번호', 'BIGINT', null, null, null, false, null, 2, false, null, true, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '프로젝트_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'LGCL_ENTT_SN', '논리 엔터티 일련번호', 'BIGINT', null, null, null, false, null, 3, false, 'TB_LGCL_ENTT', true, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '논리_엔터티_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'TBL_SN', '테이블 일련번호', 'BIGINT', null, null, null, false, null, 4, false, 'TB_TBL', true, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '테이블_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'REG_DT', '등록 일시', 'TIMESTAMP', null, null, null, true, null, 5, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'MDFCN_DT', '수정 일시', 'TIMESTAMP', null, null, null, true, null, 6, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'RGTR_ID', '등록자 아이디', 'VARCHAR', 255, null, null, true, null, 7, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'TB_ENTT_TBL_MPNG', 'MDFR_ID', '수정자 아이디', 'VARCHAR', 255, null, null, true, null, 8, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, DRVN_COL_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_TBL_REL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT'), 'FK_ENTT_TBL_MPNG_01', 'FK', 'REF_COLUMN', (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG') and COL_NM = 'LGCL_ENTT_SN'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values(nextval('SQ_TBL_REL_COL'), (select REL_SN from TB_TBL_REL where PRJ_SN = 0 and REL_NM = 'FK_ENTT_TBL_MPNG_01' and ORGNL_TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG')), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG') and COL_NM = 'LGCL_ENTT_SN'), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT') and COL_NM = 'LGCL_ENTT_SN'), 1);
insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, DRVN_COL_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_TBL_REL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_TBL'), 'FK_ENTT_TBL_MPNG_02', 'FK', 'REF_COLUMN', (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG') and COL_NM = 'TBL_SN'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values(nextval('SQ_TBL_REL_COL'), (select REL_SN from TB_TBL_REL where PRJ_SN = 0 and REL_NM = 'FK_ENTT_TBL_MPNG_02' and ORGNL_TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG')), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG') and COL_NM = 'TBL_SN'), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_TBL') and COL_NM = 'TBL_SN'), 1);

-- 속성 컬럼 매핑 (TB_ATRB_COL_MPNG)
insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, TBL_KORN_NM, TBL_EXPLN, MSTR_TBL_YN, SORT_ORD, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_TBL'), 0, (select SCHM_SN from TB_SCHM where PRJ_SN = 0 and BSC_YN = true), 'TB_ATRB_COL_MPNG', '속성 컬럼 매핑', 'MetaMora 속성 컬럼 매핑 정보', false, 37, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'ATRB_COL_MPNG_SN', '속성 컬럼 매핑 일련번호', 'BIGINT', null, null, null, false, null, 1, false, null, true, false, true, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '속성_컬럼_매핑_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'ENTT_TBL_MPNG_SN', '엔터티 테이블 매핑 일련번호', 'BIGINT', null, null, null, false, null, 2, false, 'TB_ENTT_TBL_MPNG', true, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'LGCL_ATRB_SN', '논리 속성 일련번호', 'BIGINT', null, null, null, false, null, 3, false, 'TB_LGCL_ATRB', true, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '논리_속성_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'COL_SN', '컬럼 일련번호', 'BIGINT', null, null, null, false, null, 4, false, 'TB_COL', true, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '컬럼_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'REG_DT', '등록 일시', 'TIMESTAMP', null, null, null, true, null, 5, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'MDFCN_DT', '수정 일시', 'TIMESTAMP', null, null, null, true, null, 6, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'RGTR_ID', '등록자 아이디', 'VARCHAR', 255, null, null, true, null, 7, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_COL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), 'TB_ATRB_COL_MPNG', 'MDFR_ID', '수정자 아이디', 'VARCHAR', 255, null, null, true, null, 8, false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, DRVN_COL_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_TBL_REL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG'), 'FK_ATRB_COL_MPNG_01', 'FK', 'REF_COLUMN', (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG') and COL_NM = 'ENTT_TBL_MPNG_SN'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values(nextval('SQ_TBL_REL_COL'), (select REL_SN from TB_TBL_REL where PRJ_SN = 0 and REL_NM = 'FK_ATRB_COL_MPNG_01' and ORGNL_TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG')), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG') and COL_NM = 'ENTT_TBL_MPNG_SN'), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG') and COL_NM = 'ENTT_TBL_MPNG_SN'), 1);
insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, DRVN_COL_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_TBL_REL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB'), 'FK_ATRB_COL_MPNG_02', 'FK', 'REF_COLUMN', (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG') and COL_NM = 'LGCL_ATRB_SN'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values(nextval('SQ_TBL_REL_COL'), (select REL_SN from TB_TBL_REL where PRJ_SN = 0 and REL_NM = 'FK_ATRB_COL_MPNG_02' and ORGNL_TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG')), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG') and COL_NM = 'LGCL_ATRB_SN'), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB') and COL_NM = 'LGCL_ATRB_SN'), 1);
insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_NM, REL_TYPE_CD, REL_SRC_CD, DRVN_COL_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_TBL_REL'), 0, (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG'), (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_COL'), 'FK_ATRB_COL_MPNG_03', 'FK', 'REF_COLUMN', (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG') and COL_NM = 'COL_SN'), true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values(nextval('SQ_TBL_REL_COL'), (select REL_SN from TB_TBL_REL where PRJ_SN = 0 and REL_NM = 'FK_ATRB_COL_MPNG_03' and ORGNL_TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG')), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG') and COL_NM = 'COL_SN'), (select COL_SN from TB_COL where TBL_SN = (select TBL_SN from TB_TBL where PRJ_SN = 0 and TBL_NM = 'TB_COL') and COL_NM = 'COL_SN'), 1);

-- ---------- 3) 논리 모델 예제 ----------

-- 엔터티 테이블 매핑 (TB_ENTT_TBL_MPNG)
insert into TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, ENTT_EXPLN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ENTT'), 0, (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'), '엔터티 테이블 매핑', 'MetaMora 엔터티 테이블 매핑 정보', true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '엔터티 테이블 매핑 일련번호', null, null, true, 1, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '프로젝트 일련번호', null, null, true, 2, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '프로젝트_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '논리 엔터티 일련번호', null, null, true, 3, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '논리_엔터티_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '테이블 일련번호', null, null, true, 4, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '테이블_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '등록 일시', null, null, false, 5, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '수정 일시', null, null, false, 6, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '등록자 아이디', null, null, false, 7, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '수정자 아이디', null, null, false, 8, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_IDNTFR(LGCL_IDNTFR_SN, LGCL_ENTT_SN, IDNTFR_NM, BSC_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_IDNTFR'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '엔터티 테이블 매핑 식별자', true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values(nextval('SQ_LGCL_IDNTFR_ATRB'), (select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑') and BSC_YN = true), (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑') and ATRB_NM = '엔터티 테이블 매핑 일련번호'), 1);

-- 속성 컬럼 매핑 (TB_ATRB_COL_MPNG)
insert into TB_LGCL_ENTT(LGCL_ENTT_SN, PRJ_SN, LGCL_MDL_SN, ENTT_NM, ENTT_EXPLN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ENTT'), 0, (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'), '속성 컬럼 매핑', 'MetaMora 속성 컬럼 매핑 정보', true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '속성 컬럼 매핑 일련번호', null, null, true, 1, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '속성_컬럼_매핑_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '엔터티 테이블 매핑 일련번호', null, null, true, 2, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '엔터티_테이블_매핑_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '논리 속성 일련번호', null, null, true, 3, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '논리_속성_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '컬럼 일련번호', null, null, true, 4, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '컬럼_일련번호'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '등록 일시', null, null, false, 5, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '수정 일시', null, null, false, 6, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정_일시'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '등록자 아이디', null, null, false, 7, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '등록자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_ATRB'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '수정자 아이디', null, null, false, 8, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '수정자_아이디'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_IDNTFR(LGCL_IDNTFR_SN, LGCL_ENTT_SN, IDNTFR_NM, BSC_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_IDNTFR'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '속성 컬럼 매핑 식별자', true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_IDNTFR_ATRB(LGCL_IDNTFR_ATRB_SN, LGCL_IDNTFR_SN, LGCL_ATRB_SN, SORT_ORD) values(nextval('SQ_LGCL_IDNTFR_ATRB'), (select LGCL_IDNTFR_SN from TB_LGCL_IDNTFR where LGCL_ENTT_SN = (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑') and BSC_YN = true), (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑') and ATRB_NM = '속성 컬럼 매핑 일련번호'), 1);

insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, REL_EXPLN, IDNTF_YN, ORGNL_MIN_CNT, ORGNL_MAX_CNT, TRGT_MIN_CNT, TRGT_MAX_CNT, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_REL'), 0, (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '논리 엔터티'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '논리 엔터티', '엔터티 테이블 매핑의 논리 엔터티 일련번호 로 논리 엔터티 을(를) 참조', false, 1, 1, 0, null, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, REL_EXPLN, IDNTF_YN, ORGNL_MIN_CNT, ORGNL_MAX_CNT, TRGT_MIN_CNT, TRGT_MAX_CNT, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_REL'), 0, (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '테이블'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), '테이블', '엔터티 테이블 매핑의 테이블 일련번호 로 테이블 을(를) 참조', false, 1, 1, 0, null, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, REL_EXPLN, IDNTF_YN, ORGNL_MIN_CNT, ORGNL_MAX_CNT, TRGT_MIN_CNT, TRGT_MAX_CNT, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_REL'), 0, (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '엔터티 테이블 매핑'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '엔터티 테이블 매핑', '속성 컬럼 매핑의 엔터티 테이블 매핑 일련번호 로 엔터티 테이블 매핑 을(를) 참조', false, 1, 1, 0, null, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, REL_EXPLN, IDNTF_YN, ORGNL_MIN_CNT, ORGNL_MAX_CNT, TRGT_MIN_CNT, TRGT_MAX_CNT, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_REL'), 0, (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '논리 속성'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '논리 속성', '속성 컬럼 매핑의 논리 속성 일련번호 로 논리 속성 을(를) 참조', false, 1, 1, 0, null, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');
insert into TB_LGCL_REL(LGCL_REL_SN, PRJ_SN, LGCL_MDL_SN, ORGNL_ENTT_SN, TRGT_ENTT_SN, REL_NM, REL_EXPLN, IDNTF_YN, ORGNL_MIN_CNT, ORGNL_MAX_CNT, TRGT_MIN_CNT, TRGT_MAX_CNT, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values(nextval('SQ_LGCL_REL'), 0, (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '컬럼'), (select LGCL_ENTT_SN from TB_LGCL_ENTT where LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'META-MORA') and ENTT_NM = '속성 컬럼 매핑'), '컬럼', '속성 컬럼 매핑의 컬럼 일련번호 로 컬럼 을(를) 참조', false, 1, 1, 0, null, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM');

-- ---------- 4) COMMENT (테이블 = 한글명, 컬럼 = 한글 용어) ----------

comment on table TB_ACNT is '계정';
comment on column TB_ACNT.ACNT_SN is '계정 일련번호';
comment on column TB_ACNT.USER_ID is '사용자 아이디';
comment on column TB_ACNT.PSWD is '비밀번호';
comment on column TB_ACNT.USER_NM is '사용자 명';
comment on column TB_ACNT.ACTVTN_YN is '활성 여부';
comment on column TB_ACNT.PSWD_CHG_YN is '비밀번호 변경 여부';
comment on column TB_ACNT.REG_DT is '등록 일시';
comment on column TB_ACNT.MDFCN_DT is '수정 일시';
comment on column TB_ACNT.RGTR_ID is '등록자 아이디';
comment on column TB_ACNT.MDFR_ID is '수정자 아이디';

comment on table TB_ROLE is '역할';
comment on column TB_ROLE.ROLE_SN is '역할 일련번호';
comment on column TB_ROLE.ROLE_NM is '역할 명';
comment on column TB_ROLE.ROLE_EXPLN is '역할 설명';
comment on column TB_ROLE.EXPR_YN is '표현식 여부';
comment on column TB_ROLE.REG_DT is '등록 일시';
comment on column TB_ROLE.MDFCN_DT is '수정 일시';
comment on column TB_ROLE.RGTR_ID is '등록자 아이디';
comment on column TB_ROLE.MDFR_ID is '수정자 아이디';

comment on table TB_ACNT_ROLE is '계정 역할';
comment on column TB_ACNT_ROLE.ACNT_SN is '계정 일련번호';
comment on column TB_ACNT_ROLE.ROLE_SN is '역할 일련번호';

comment on table TB_PRJ is '프로젝트';
comment on column TB_PRJ.PRJ_SN is '프로젝트 일련번호';
comment on column TB_PRJ.PRJ_NM is '프로젝트 명';
comment on column TB_PRJ.MNGR_ACNT_SN is '관리자 계정 일련번호';
comment on column TB_PRJ.ACTVTN_YN is '활성 여부';
comment on column TB_PRJ.AUTO_APRV_YN is '자동 승인 여부';
comment on column TB_PRJ.DIC_ABBR_USE_YN is '사전 약어 사용 여부';
comment on column TB_PRJ.TBL_PFX_NM is '테이블 접두어 명';
comment on column TB_PRJ.DB_TYPE_CD is 'DB 유형 코드';
comment on column TB_PRJ.REG_DT is '등록 일시';
comment on column TB_PRJ.MDFCN_DT is '수정 일시';
comment on column TB_PRJ.RGTR_ID is '등록자 아이디';
comment on column TB_PRJ.MDFR_ID is '수정자 아이디';

comment on table TB_PRJ_MBR is '프로젝트 회원';
comment on column TB_PRJ_MBR.PRJ_MBR_SN is '프로젝트 회원 일련번호';
comment on column TB_PRJ_MBR.ACNT_SN is '계정 일련번호';
comment on column TB_PRJ_MBR.PRJ_SN is '프로젝트 일련번호';
comment on column TB_PRJ_MBR.ACTVTN_YN is '활성 여부';
comment on column TB_PRJ_MBR.REG_DT is '등록 일시';
comment on column TB_PRJ_MBR.MDFCN_DT is '수정 일시';
comment on column TB_PRJ_MBR.RGTR_ID is '등록자 아이디';
comment on column TB_PRJ_MBR.MDFR_ID is '수정자 아이디';

comment on table TB_LOG is '로그';
comment on column TB_LOG.LOG_SN is '로그 일련번호';
comment on column TB_LOG.DMND_URL_ADDR is '요청 URL 주소';
comment on column TB_LOG.DMND_MTHD_NM is '요청 방법 명';
comment on column TB_LOG.CNTN_IP_ADDR is '접속 IP 주소';
comment on column TB_LOG.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LOG.USER_ID is '사용자 아이디';
comment on column TB_LOG.ROLE_NM is '역할 명';
comment on column TB_LOG.RSPNS_STTS_CD is '응답 상태 코드';
comment on column TB_LOG.DMND_CN is '요청 내용';
comment on column TB_LOG.RSPNS_CN is '응답 내용';
comment on column TB_LOG.ERR_CN is '오류 내용';
comment on column TB_LOG.USER_AGNT_CN is '사용자 에이전트 내용';
comment on column TB_LOG.LOG_DT is '로그 일시';

comment on table TB_PST_CTGRY is '게시물 범주';
comment on column TB_PST_CTGRY.CTGRY_CD is '범주 코드';
comment on column TB_PST_CTGRY.CTGRY_NM is '범주 명';
comment on column TB_PST_CTGRY.SORT_ORD is '정렬 순서';
comment on column TB_PST_CTGRY.USE_YN is '사용 여부';
comment on column TB_PST_CTGRY.REG_DT is '등록 일시';
comment on column TB_PST_CTGRY.MDFCN_DT is '수정 일시';
comment on column TB_PST_CTGRY.RGTR_ID is '등록자 아이디';
comment on column TB_PST_CTGRY.MDFR_ID is '수정자 아이디';

comment on table TB_PST is '게시물';
comment on column TB_PST.PST_SN is '게시물 일련번호';
comment on column TB_PST.PRJ_SN is '프로젝트 일련번호';
comment on column TB_PST.CTGRY_CD is '범주 코드';
comment on column TB_PST.WRTR_ID is '작성자 아이디';
comment on column TB_PST.PST_TTL is '게시물 제목';
comment on column TB_PST.PST_CN is '게시물 내용';
comment on column TB_PST.DEL_YN is '삭제 여부';
comment on column TB_PST.PST_MDFCN_DT is '게시물 수정 일시';
comment on column TB_PST.PRJ_SN_LIST is '프로젝트 일련번호 목록';
comment on column TB_PST.REG_DT is '등록 일시';
comment on column TB_PST.MDFCN_DT is '수정 일시';
comment on column TB_PST.RGTR_ID is '등록자 아이디';
comment on column TB_PST.MDFR_ID is '수정자 아이디';

comment on table TB_PST_ACNT is '게시물 계정';
comment on column TB_PST_ACNT.PST_SN is '게시물 일련번호';
comment on column TB_PST_ACNT.ACNT_SN is '계정 일련번호';

comment on table TB_ATCH_FILE is '첨부 파일';
comment on column TB_ATCH_FILE.FILE_SN is '파일 일련번호';
comment on column TB_ATCH_FILE.PST_SN is '게시물 일련번호';
comment on column TB_ATCH_FILE.FILE_NM is '파일 명';
comment on column TB_ATCH_FILE.ORGNL_FILE_NM is '원본 파일 명';
comment on column TB_ATCH_FILE.FILE_EXTN_NM is '파일 확장자 명';
comment on column TB_ATCH_FILE.FILE_PATH_NM is '파일 경로 명';
comment on column TB_ATCH_FILE.REG_DT is '등록 일시';
comment on column TB_ATCH_FILE.MDFCN_DT is '수정 일시';
comment on column TB_ATCH_FILE.RGTR_ID is '등록자 아이디';
comment on column TB_ATCH_FILE.MDFR_ID is '수정자 아이디';

comment on table TB_STD_DMN is '표준 도메인';
comment on column TB_STD_DMN.STD_DMN_SN is '표준 도메인 일련번호';
comment on column TB_STD_DMN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_STD_DMN.ENCT_CYCL is '제정 차수';
comment on column TB_STD_DMN.DMN_GROUP_NM is '도메인 그룹 명';
comment on column TB_STD_DMN.DMN_CLSF_NM is '도메인 분류 명';
comment on column TB_STD_DMN.DMN_NM is '도메인 명';
comment on column TB_STD_DMN.DMN_EXPLN is '도메인 설명';
comment on column TB_STD_DMN.DATA_TYPE_NM is '자료 유형 명';
comment on column TB_STD_DMN.DATA_LEN is '자료 길이';
comment on column TB_STD_DMN.DATA_DCPT_LEN is '자료 소수점 길이';
comment on column TB_STD_DMN.STRG_FRMT_CN is '저장 형식 내용';
comment on column TB_STD_DMN.INDCT_FRMT_CN is '표시 형식 내용';
comment on column TB_STD_DMN.UNIT_NM is '단위 명';
comment on column TB_STD_DMN.ALWNC_VL_CN is '허용 값 내용';
comment on column TB_STD_DMN.APRV_YN is '승인 여부';
comment on column TB_STD_DMN.REG_DT is '등록 일시';
comment on column TB_STD_DMN.MDFCN_DT is '수정 일시';
comment on column TB_STD_DMN.RGTR_ID is '등록자 아이디';
comment on column TB_STD_DMN.MDFR_ID is '수정자 아이디';

comment on table TB_STD_WORD is '표준 단어';
comment on column TB_STD_WORD.STD_WORD_SN is '표준 단어 일련번호';
comment on column TB_STD_WORD.PRJ_SN is '프로젝트 일련번호';
comment on column TB_STD_WORD.ENCT_CYCL is '제정 차수';
comment on column TB_STD_WORD.WORD_NM is '단어 명';
comment on column TB_STD_WORD.WORD_ENG_ABBR_NM is '단어 영문 약어 명';
comment on column TB_STD_WORD.WORD_ENG_NM is '단어 영문 명';
comment on column TB_STD_WORD.WORD_EXPLN is '단어 설명';
comment on column TB_STD_WORD.FRMT_WORD_YN is '형식 단어 여부';
comment on column TB_STD_WORD.DMN_CLSF_NM is '도메인 분류 명';
comment on column TB_STD_WORD.ABBR_USE_YN is '약어 사용 여부';
comment on column TB_STD_WORD.APRV_YN is '승인 여부';
comment on column TB_STD_WORD.REG_DT is '등록 일시';
comment on column TB_STD_WORD.MDFCN_DT is '수정 일시';
comment on column TB_STD_WORD.RGTR_ID is '등록자 아이디';
comment on column TB_STD_WORD.MDFR_ID is '수정자 아이디';

comment on table TB_STD_WORD_SYNM is '표준 단어 이음동의어';
comment on column TB_STD_WORD_SYNM.STD_WORD_SN is '표준 단어 일련번호';
comment on column TB_STD_WORD_SYNM.SYNM_NM is '이음동의어 명';

comment on table TB_STD_WORD_PRHBT is '표준 단어 금칙';
comment on column TB_STD_WORD_PRHBT.STD_WORD_SN is '표준 단어 일련번호';
comment on column TB_STD_WORD_PRHBT.PRHBT_WORD_NM is '금칙 단어 명';

comment on table TB_STD_TRM is '표준 용어';
comment on column TB_STD_TRM.STD_TRM_SN is '표준 용어 일련번호';
comment on column TB_STD_TRM.PRJ_SN is '프로젝트 일련번호';
comment on column TB_STD_TRM.ENCT_CYCL is '제정 차수';
comment on column TB_STD_TRM.TRM_NM is '용어 명';
comment on column TB_STD_TRM.TRM_EXPLN is '용어 설명';
comment on column TB_STD_TRM.TRM_ENG_ABBR_NM is '용어 영문 약어 명';
comment on column TB_STD_TRM.PBADMS_STD_CD_NM is '행정 표준 코드 명';
comment on column TB_STD_TRM.JRSDC_INST_NM is '소관 기관 명';
comment on column TB_STD_TRM.STD_DMN_SN is '표준 도메인 일련번호';
comment on column TB_STD_TRM.APRV_YN is '승인 여부';
comment on column TB_STD_TRM.REG_DT is '등록 일시';
comment on column TB_STD_TRM.MDFCN_DT is '수정 일시';
comment on column TB_STD_TRM.RGTR_ID is '등록자 아이디';
comment on column TB_STD_TRM.MDFR_ID is '수정자 아이디';

comment on table TB_STD_TRM_SYNM is '표준 용어 이음동의어';
comment on column TB_STD_TRM_SYNM.STD_TRM_SN is '표준 용어 일련번호';
comment on column TB_STD_TRM_SYNM.SYNM_NM is '이음동의어 명';

comment on table TB_STD_TRM_WORD is '표준 용어 단어';
comment on column TB_STD_TRM_WORD.STD_TRM_WORD_SN is '표준 용어 단어 일련번호';
comment on column TB_STD_TRM_WORD.STD_TRM_SN is '표준 용어 일련번호';
comment on column TB_STD_TRM_WORD.STD_WORD_SN is '표준 단어 일련번호';
comment on column TB_STD_TRM_WORD.SORT_ORD is '정렬 순서';
comment on column TB_STD_TRM_WORD.REG_DT is '등록 일시';
comment on column TB_STD_TRM_WORD.MDFCN_DT is '수정 일시';
comment on column TB_STD_TRM_WORD.RGTR_ID is '등록자 아이디';
comment on column TB_STD_TRM_WORD.MDFR_ID is '수정자 아이디';

comment on table TB_SCHM is '스키마';
comment on column TB_SCHM.SCHM_SN is '스키마 일련번호';
comment on column TB_SCHM.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SCHM.SCHM_NM is '스키마 명';
comment on column TB_SCHM.SCHM_EXPLN is '스키마 설명';
comment on column TB_SCHM.BSC_YN is '기본 여부';
comment on column TB_SCHM.REG_DT is '등록 일시';
comment on column TB_SCHM.MDFCN_DT is '수정 일시';
comment on column TB_SCHM.RGTR_ID is '등록자 아이디';
comment on column TB_SCHM.MDFR_ID is '수정자 아이디';

comment on table TB_TBL is '테이블';
comment on column TB_TBL.TBL_SN is '테이블 일련번호';
comment on column TB_TBL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_TBL.SCHM_SN is '스키마 일련번호';
comment on column TB_TBL.TBL_NM is '테이블 명';
comment on column TB_TBL.TBL_KORN_NM is '테이블 한글 명';
comment on column TB_TBL.TBL_EXPLN is '테이블 설명';
comment on column TB_TBL.MSTR_TBL_YN is '마스터 테이블 여부';
comment on column TB_TBL.SORT_ORD is '정렬 순서';
comment on column TB_TBL.APRV_YN is '승인 여부';
comment on column TB_TBL.REG_DT is '등록 일시';
comment on column TB_TBL.MDFCN_DT is '수정 일시';
comment on column TB_TBL.RGTR_ID is '등록자 아이디';
comment on column TB_TBL.MDFR_ID is '수정자 아이디';

comment on table TB_COL is '컬럼';
comment on column TB_COL.COL_SN is '컬럼 일련번호';
comment on column TB_COL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_COL.TBL_SN is '테이블 일련번호';
comment on column TB_COL.TBL_NM is '테이블 명';
comment on column TB_COL.COL_NM is '컬럼 명';
comment on column TB_COL.COL_EXPLN is '컬럼 설명';
comment on column TB_COL.DATA_TYPE_NM is '자료 유형 명';
comment on column TB_COL.MAX_LEN is '최대 길이';
comment on column TB_COL.DATA_PRCSN is '자료 정밀도';
comment on column TB_COL.DCPT_LEN is '소수점 길이';
comment on column TB_COL.NULL_ALWNC_YN is 'NULL 허용 여부';
comment on column TB_COL.BSC_VL is '기본 값';
comment on column TB_COL.SORT_ORD is '정렬 순서';
comment on column TB_COL.MSTR_DATA_YN is '마스터 자료 여부';
comment on column TB_COL.RFRNC_TBL_NM is '참조 테이블 명';
comment on column TB_COL.ESNTL_YN is '필수 여부';
comment on column TB_COL.SNSTV_INFO_YN is '민감 정보 여부';
comment on column TB_COL.PK_YN is '기본키 여부';
comment on column TB_COL.UNQ_YN is '고유 여부';
comment on column TB_COL.IDX_YN is '인덱스 여부';
comment on column TB_COL.ENCPT_YN is '암호화 여부';
comment on column TB_COL.EXMPL_CN is '예시 내용';
comment on column TB_COL.APRV_YN is '승인 여부';
comment on column TB_COL.STD_TRM_SN is '표준 용어 일련번호';
comment on column TB_COL.REG_DT is '등록 일시';
comment on column TB_COL.MDFCN_DT is '수정 일시';
comment on column TB_COL.RGTR_ID is '등록자 아이디';
comment on column TB_COL.MDFR_ID is '수정자 아이디';

comment on table TB_CMN_COL is '공통 컬럼';
comment on column TB_CMN_COL.CMN_COL_SN is '공통 컬럼 일련번호';
comment on column TB_CMN_COL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_CMN_COL.STD_TRM_SN is '표준 용어 일련번호';
comment on column TB_CMN_COL.SORT_ORD is '정렬 순서';
comment on column TB_CMN_COL.NULL_ALWNC_YN is 'NULL 허용 여부';
comment on column TB_CMN_COL.IDX_YN is '인덱스 여부';
comment on column TB_CMN_COL.BSC_VL is '기본 값';
comment on column TB_CMN_COL.REG_DT is '등록 일시';
comment on column TB_CMN_COL.MDFCN_DT is '수정 일시';
comment on column TB_CMN_COL.RGTR_ID is '등록자 아이디';
comment on column TB_CMN_COL.MDFR_ID is '수정자 아이디';

comment on table TB_TBL_REL is '테이블 관계';
comment on column TB_TBL_REL.REL_SN is '관계 일련번호';
comment on column TB_TBL_REL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_TBL_REL.ORGNL_TBL_SN is '원본 테이블 일련번호';
comment on column TB_TBL_REL.TRGT_TBL_SN is '대상 테이블 일련번호';
comment on column TB_TBL_REL.REL_NM is '관계 명';
comment on column TB_TBL_REL.REL_TYPE_CD is '관계 유형 코드';
comment on column TB_TBL_REL.REL_SRC_CD is '관계 출처 코드';
comment on column TB_TBL_REL.DRVN_COL_SN is '파생 컬럼 일련번호';
comment on column TB_TBL_REL.APRV_YN is '승인 여부';
comment on column TB_TBL_REL.REG_DT is '등록 일시';
comment on column TB_TBL_REL.MDFCN_DT is '수정 일시';
comment on column TB_TBL_REL.RGTR_ID is '등록자 아이디';
comment on column TB_TBL_REL.MDFR_ID is '수정자 아이디';

comment on table TB_TBL_REL_COL is '테이블 관계 컬럼';
comment on column TB_TBL_REL_COL.REL_COL_SN is '관계 컬럼 일련번호';
comment on column TB_TBL_REL_COL.REL_SN is '관계 일련번호';
comment on column TB_TBL_REL_COL.ORGNL_COL_SN is '원본 컬럼 일련번호';
comment on column TB_TBL_REL_COL.TRGT_COL_SN is '대상 컬럼 일련번호';
comment on column TB_TBL_REL_COL.COL_ORD is '컬럼 순서';

comment on table TB_TBL_IDX is '테이블 인덱스';
comment on column TB_TBL_IDX.IDX_SN is '인덱스 일련번호';
comment on column TB_TBL_IDX.PRJ_SN is '프로젝트 일련번호';
comment on column TB_TBL_IDX.TBL_SN is '테이블 일련번호';
comment on column TB_TBL_IDX.IDX_NM is '인덱스 명';
comment on column TB_TBL_IDX.IDX_TYPE_NM is '인덱스 유형 명';
comment on column TB_TBL_IDX.UNQ_YN is '고유 여부';
comment on column TB_TBL_IDX.APRV_YN is '승인 여부';
comment on column TB_TBL_IDX.REG_DT is '등록 일시';
comment on column TB_TBL_IDX.MDFCN_DT is '수정 일시';
comment on column TB_TBL_IDX.RGTR_ID is '등록자 아이디';
comment on column TB_TBL_IDX.MDFR_ID is '수정자 아이디';

comment on table TB_TBL_IDX_COL is '테이블 인덱스 컬럼';
comment on column TB_TBL_IDX_COL.IDX_COL_SN is '인덱스 컬럼 일련번호';
comment on column TB_TBL_IDX_COL.IDX_SN is '인덱스 일련번호';
comment on column TB_TBL_IDX_COL.COL_SN is '컬럼 일련번호';
comment on column TB_TBL_IDX_COL.COL_ORD is '컬럼 순서';

comment on table TB_TBL_CNSTRT is '테이블 제약조건';
comment on column TB_TBL_CNSTRT.CNSTRT_SN is '제약조건 일련번호';
comment on column TB_TBL_CNSTRT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_TBL_CNSTRT.TBL_SN is '테이블 일련번호';
comment on column TB_TBL_CNSTRT.CNSTRT_NM is '제약조건 명';
comment on column TB_TBL_CNSTRT.CNSTRT_TYPE_CD is '제약조건 유형 코드';
comment on column TB_TBL_CNSTRT.INSP_EXPR_CN is '검사 표현식 내용';
comment on column TB_TBL_CNSTRT.APRV_YN is '승인 여부';
comment on column TB_TBL_CNSTRT.REG_DT is '등록 일시';
comment on column TB_TBL_CNSTRT.MDFCN_DT is '수정 일시';
comment on column TB_TBL_CNSTRT.RGTR_ID is '등록자 아이디';
comment on column TB_TBL_CNSTRT.MDFR_ID is '수정자 아이디';

comment on table TB_TBL_CNSTRT_COL is '테이블 제약조건 컬럼';
comment on column TB_TBL_CNSTRT_COL.CNSTRT_COL_SN is '제약조건 컬럼 일련번호';
comment on column TB_TBL_CNSTRT_COL.CNSTRT_SN is '제약조건 일련번호';
comment on column TB_TBL_CNSTRT_COL.COL_SN is '컬럼 일련번호';
comment on column TB_TBL_CNSTRT_COL.COL_ORD is '컬럼 순서';

comment on table TB_ERD_LYT is 'ERD 레이아웃';
comment on column TB_ERD_LYT.ERD_LYT_SN is 'ERD 레이아웃 일련번호';
comment on column TB_ERD_LYT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_ERD_LYT.ACNT_SN is '계정 일련번호';
comment on column TB_ERD_LYT.NODE_PSTN_CN is '노드 위치 내용';
comment on column TB_ERD_LYT.SCRN_X_CRDNT is '화면 X 좌표';
comment on column TB_ERD_LYT.SCRN_Y_CRDNT is '화면 Y 좌표';
comment on column TB_ERD_LYT.SCRN_MGNF is '화면 배율';
comment on column TB_ERD_LYT.VER_NO is '버전 번호';
comment on column TB_ERD_LYT.REG_DT is '등록 일시';
comment on column TB_ERD_LYT.MDFCN_DT is '수정 일시';
comment on column TB_ERD_LYT.RGTR_ID is '등록자 아이디';
comment on column TB_ERD_LYT.MDFR_ID is '수정자 아이디';

comment on table TB_LGCL_MDL is '논리 모델';
comment on column TB_LGCL_MDL.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_MDL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_MDL.MDL_NM is '모델 명';
comment on column TB_LGCL_MDL.MDL_EXPLN is '모델 설명';
comment on column TB_LGCL_MDL.REG_DT is '등록 일시';
comment on column TB_LGCL_MDL.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_MDL.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_MDL.MDFR_ID is '수정자 아이디';

comment on table TB_LGCL_ENTT is '논리 엔터티';
comment on column TB_LGCL_ENTT.LGCL_ENTT_SN is '논리 엔터티 일련번호';
comment on column TB_LGCL_ENTT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_ENTT.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_ENTT.ENTT_NM is '엔터티 명';
comment on column TB_LGCL_ENTT.ENTT_EXPLN is '엔터티 설명';
comment on column TB_LGCL_ENTT.APRV_YN is '승인 여부';
comment on column TB_LGCL_ENTT.REG_DT is '등록 일시';
comment on column TB_LGCL_ENTT.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_ENTT.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_ENTT.MDFR_ID is '수정자 아이디';

comment on table TB_LGCL_ATRB is '논리 속성';
comment on column TB_LGCL_ATRB.LGCL_ATRB_SN is '논리 속성 일련번호';
comment on column TB_LGCL_ATRB.LGCL_ENTT_SN is '논리 엔터티 일련번호';
comment on column TB_LGCL_ATRB.ATRB_NM is '속성 명';
comment on column TB_LGCL_ATRB.ATRB_EXPLN is '속성 설명';
comment on column TB_LGCL_ATRB.DATA_TYPE_NM is '자료 유형 명';
comment on column TB_LGCL_ATRB.ESNTL_YN is '필수 여부';
comment on column TB_LGCL_ATRB.SORT_ORD is '정렬 순서';
comment on column TB_LGCL_ATRB.STD_TRM_SN is '표준 용어 일련번호';
comment on column TB_LGCL_ATRB.REG_DT is '등록 일시';
comment on column TB_LGCL_ATRB.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_ATRB.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_ATRB.MDFR_ID is '수정자 아이디';

comment on table TB_LGCL_IDNTFR is '논리 식별자';
comment on column TB_LGCL_IDNTFR.LGCL_IDNTFR_SN is '논리 식별자 일련번호';
comment on column TB_LGCL_IDNTFR.LGCL_ENTT_SN is '논리 엔터티 일련번호';
comment on column TB_LGCL_IDNTFR.IDNTFR_NM is '식별자 명';
comment on column TB_LGCL_IDNTFR.BSC_YN is '기본 여부';
comment on column TB_LGCL_IDNTFR.REG_DT is '등록 일시';
comment on column TB_LGCL_IDNTFR.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_IDNTFR.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_IDNTFR.MDFR_ID is '수정자 아이디';

comment on table TB_LGCL_IDNTFR_ATRB is '논리 식별자 속성';
comment on column TB_LGCL_IDNTFR_ATRB.LGCL_IDNTFR_ATRB_SN is '논리 식별자 속성 일련번호';
comment on column TB_LGCL_IDNTFR_ATRB.LGCL_IDNTFR_SN is '논리 식별자 일련번호';
comment on column TB_LGCL_IDNTFR_ATRB.LGCL_ATRB_SN is '논리 속성 일련번호';
comment on column TB_LGCL_IDNTFR_ATRB.SORT_ORD is '정렬 순서';

comment on table TB_LGCL_REL is '논리 관계';
comment on column TB_LGCL_REL.LGCL_REL_SN is '논리 관계 일련번호';
comment on column TB_LGCL_REL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_REL.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_REL.ORGNL_ENTT_SN is '원본 엔터티 일련번호';
comment on column TB_LGCL_REL.TRGT_ENTT_SN is '대상 엔터티 일련번호';
comment on column TB_LGCL_REL.REL_NM is '관계 명';
comment on column TB_LGCL_REL.REL_EXPLN is '관계 설명';
comment on column TB_LGCL_REL.IDNTF_YN is '식별 여부';
comment on column TB_LGCL_REL.ORGNL_MIN_CNT is '원본 최소 수';
comment on column TB_LGCL_REL.ORGNL_MAX_CNT is '원본 최대 수';
comment on column TB_LGCL_REL.TRGT_MIN_CNT is '대상 최소 수';
comment on column TB_LGCL_REL.TRGT_MAX_CNT is '대상 최대 수';
comment on column TB_LGCL_REL.APRV_YN is '승인 여부';
comment on column TB_LGCL_REL.REG_DT is '등록 일시';
comment on column TB_LGCL_REL.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_REL.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_REL.MDFR_ID is '수정자 아이디';

comment on table TB_LGCL_ERD_LYT is '논리 ERD 레이아웃';
comment on column TB_LGCL_ERD_LYT.LGCL_ERD_LYT_SN is '논리 ERD 레이아웃 일련번호';
comment on column TB_LGCL_ERD_LYT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_ERD_LYT.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_ERD_LYT.ACNT_SN is '계정 일련번호';
comment on column TB_LGCL_ERD_LYT.NODE_PSTN_CN is '노드 위치 내용';
comment on column TB_LGCL_ERD_LYT.SCRN_X_CRDNT is '화면 X 좌표';
comment on column TB_LGCL_ERD_LYT.SCRN_Y_CRDNT is '화면 Y 좌표';
comment on column TB_LGCL_ERD_LYT.SCRN_MGNF is '화면 배율';
comment on column TB_LGCL_ERD_LYT.VER_NO is '버전 번호';
comment on column TB_LGCL_ERD_LYT.REG_DT is '등록 일시';
comment on column TB_LGCL_ERD_LYT.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_ERD_LYT.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_ERD_LYT.MDFR_ID is '수정자 아이디';

comment on table TB_ENTT_TBL_MPNG is '엔터티 테이블 매핑';
comment on column TB_ENTT_TBL_MPNG.ENTT_TBL_MPNG_SN is '엔터티 테이블 매핑 일련번호';
comment on column TB_ENTT_TBL_MPNG.PRJ_SN is '프로젝트 일련번호';
comment on column TB_ENTT_TBL_MPNG.LGCL_ENTT_SN is '논리 엔터티 일련번호';
comment on column TB_ENTT_TBL_MPNG.TBL_SN is '테이블 일련번호';
comment on column TB_ENTT_TBL_MPNG.REG_DT is '등록 일시';
comment on column TB_ENTT_TBL_MPNG.MDFCN_DT is '수정 일시';
comment on column TB_ENTT_TBL_MPNG.RGTR_ID is '등록자 아이디';
comment on column TB_ENTT_TBL_MPNG.MDFR_ID is '수정자 아이디';

comment on table TB_ATRB_COL_MPNG is '속성 컬럼 매핑';
comment on column TB_ATRB_COL_MPNG.ATRB_COL_MPNG_SN is '속성 컬럼 매핑 일련번호';
comment on column TB_ATRB_COL_MPNG.ENTT_TBL_MPNG_SN is '엔터티 테이블 매핑 일련번호';
comment on column TB_ATRB_COL_MPNG.LGCL_ATRB_SN is '논리 속성 일련번호';
comment on column TB_ATRB_COL_MPNG.COL_SN is '컬럼 일련번호';
comment on column TB_ATRB_COL_MPNG.REG_DT is '등록 일시';
comment on column TB_ATRB_COL_MPNG.MDFCN_DT is '수정 일시';
comment on column TB_ATRB_COL_MPNG.RGTR_ID is '등록자 아이디';
comment on column TB_ATRB_COL_MPNG.MDFR_ID is '수정자 아이디';
