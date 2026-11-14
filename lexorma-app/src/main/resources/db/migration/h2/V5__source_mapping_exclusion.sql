-- M-4g : 원본 TABLE 매핑 예외 (docs/source-mapping-m4c.md 13장)
--  1) TB_SRC_TBL_MPNG.MPNG_EXCL_YN : 매핑 예외 여부. 예외 = 모델 테이블 없이(TBL_SN null) 남긴 매핑 행이다 — 원본 TABLE 당 행이 하나뿐이라
--     (UX_SRC_TBL_MPNG_01) 같은 원본이 매핑이면서 예외일 수 없다. 기존 행은 false (매핑) 로 남는다
--  2) 예제 (데이터 사전 단어·용어 → 물리 → 논리 → 매핑) : 프로젝트 LEXORMA 의 예제가 있을 때만 넣는다
-- 원본 메타데이터 수집과는 무관하다 (예외 TABLE 도 수집한다). 증분 마이그레이션 : 이미 적용된 V0~V4 는 고치지 않는다.

alter table TB_SRC_TBL_MPNG add column MPNG_EXCL_YN boolean default false not null;

comment on column TB_SRC_TBL_MPNG.MPNG_EXCL_YN is '매핑 예외 여부';

-- ---------- 예제 : 데이터 사전 (새 단어 예외, 용어·용어 단어). 같은 이름이 이미 있으면 넣지 않는다 ----------
insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_WORD, 0, 1, '예외', 'EXCL', 'Exclusion', '일반적인 규칙이나 대상에서 벗어나는 일.', false, null, true, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_PRJ p where p.PRJ_SN = 0 and not exists (select 1 from TB_STD_WORD where PRJ_SN = 0 and WORD_NM = '예외');
insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, PBADMS_STD_CD_NM, JRSDC_INST_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM, 0, 1, '매핑_예외_여부', '원본 TABLE 을 모델 TABLE 과 매핑하지 않기로 한 예외 대상인지 여부.', 'MPNG_EXCL_YN', null, null, d.STD_DMN_SN, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_DMN d where d.PRJ_SN = 0 and d.DMN_NM = '여부B' and not exists (select 1 from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '매핑_예외_여부');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM_WORD, t.STD_TRM_SN, w.STD_WORD_SN, 1, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_TRM t join TB_STD_WORD w on w.PRJ_SN = 0 and w.WORD_NM = '매핑' where t.PRJ_SN = 0 and t.TRM_NM = '매핑_예외_여부' and not exists (select 1 from TB_STD_TRM_WORD x where x.STD_TRM_SN = t.STD_TRM_SN and x.SORT_ORD = 1);
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM_WORD, t.STD_TRM_SN, w.STD_WORD_SN, 2, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_TRM t join TB_STD_WORD w on w.PRJ_SN = 0 and w.WORD_NM = '예외' where t.PRJ_SN = 0 and t.TRM_NM = '매핑_예외_여부' and not exists (select 1 from TB_STD_TRM_WORD x where x.STD_TRM_SN = t.STD_TRM_SN and x.SORT_ORD = 2);
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM_WORD, t.STD_TRM_SN, w.STD_WORD_SN, 3, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_TRM t join TB_STD_WORD w on w.PRJ_SN = 0 and w.WORD_NM = '여부' where t.PRJ_SN = 0 and t.TRM_NM = '매핑_예외_여부' and not exists (select 1 from TB_STD_TRM_WORD x where x.STD_TRM_SN = t.STD_TRM_SN and x.SORT_ORD = 3);

-- ---------- 예제 : 물리 모델. 컬럼 TB_SRC_TBL_MPNG.MPNG_EXCL_YN (같은 이름이 이미 있으면 넣지 않는다) ----------
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_COL, 0, t.TBL_SN, 'TB_SRC_TBL_MPNG', 'MPNG_EXCL_YN', '매핑 예외 여부', 'BOOLEAN', null, null, null, false, null, (select coalesce(max(c2.SORT_ORD), 0) + 1 from TB_COL c2 where c2.TBL_SN = t.TBL_SN), false, null, true, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '매핑_예외_여부'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_TBL t where t.PRJ_SN = 0 and t.TBL_NM = 'TB_SRC_TBL_MPNG' and not exists (select 1 from TB_COL c where c.TBL_SN = t.TBL_SN and c.COL_NM = 'MPNG_EXCL_YN');

-- ---------- 예제 : 논리 모델 (출처 테이블 매핑 엔터티의 매핑 예외 여부 속성). LEXORMA 논리 모델이 있을 때만 ----------
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_LGCL_ATRB, e.LGCL_ENTT_SN, '매핑 예외 여부', null, null, true, (select coalesce(max(a2.SORT_ORD), 0) + 1 from TB_LGCL_ATRB a2 where a2.LGCL_ENTT_SN = e.LGCL_ENTT_SN), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '매핑_예외_여부'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_LGCL_ENTT e where e.LGCL_MDL_SN = (select LGCL_MDL_SN from TB_LGCL_MDL where PRJ_SN = 0 and MDL_NM = 'LEXORMA') and e.ENTT_NM = '출처 테이블 매핑' and not exists (select 1 from TB_LGCL_ATRB a where a.LGCL_ENTT_SN = e.LGCL_ENTT_SN and a.ATRB_NM = '매핑 예외 여부');

-- ---------- 예제 : 논리 ↔ 물리 매핑 (V2~V4 와 같은 규칙 : 새 속성만 이어진다) ----------
insert into TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)
select next value for SQ_ATRB_COL_MPNG, mp.ENTT_TBL_MPNG_SN, a.LGCL_ATRB_SN, c.COL_SN, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM'
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
