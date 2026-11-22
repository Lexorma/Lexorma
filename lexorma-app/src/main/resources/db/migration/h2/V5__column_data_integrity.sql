-- =====================================================================
-- V5 : 물리 컬럼 데이터 무결성 (M-7, 2026-11-22 — docs/completed/column-name-uniqueness-m7.md, M7-U01~U07 · M7-U09 통합)
-- 미배포 상태에서 V5(컬럼명 UNIQUE)·V6(필수값)·V7(유니코드 정규화)을 하나로 합친 최종본이다 (M7-U09, 15장). V0~V4 는 그대로다.
-- 최종 구조 : TB_COL.TBL_SN · COL_NM NOT NULL, CK_COL_01 (빈 이름 = 빈 문자열 또는 ASCII 공백류 U+0020·탭·LF·VT·FF·CR 만 — 서버 ColumnNameKey.isBlankName 과 같다),
--   생성 컬럼 COL_NM_CMPR_VL varchar(765) = 앞뒤 공백(U+0020) 제거 + 로캘 중립 Unicode 확장 대문자 (ß → SS, ﬁ → FI, σ·ς → Σ — 서버 ColumnNameKey.of 와 같은 뜻),
--   UNIQUE UX_COL_01 (TBL_SN, COL_NM_CMPR_VL). 실제 컬럼명(COL_NM)은 바꾸지 않는다. 생성 컬럼은 기술 컬럼이다 (애플리케이션은 쓰지도 매핑하지도 않는다).
--   H2 : H2 의 UPPER 는 String.toUpperCase() 라 JVM 기본 로캘을 따른다 (터키어 로캘이면 i → İ). 그래서 H2 자체의 org.h2.util.StringUtils.toUpperEnglish
--   (= toUpperCase(Locale.ENGLISH), 대소문자 변환은 Locale.ROOT 와 같다)를 별칭 FN_UPPER_ROOT 로 쓴다 — 애플리케이션 클래스가 아니라 H2 jar 안의 함수다.
-- 순서 : ① 별칭 (있으면 그대로) ② 사전 검사 하나로 필수값 위반 · 새 기준 중복 · 비교 값 765자 초과를 모두 세어 처음 50개 목록과 함께 표 변경 전에 멈춘다
--   (데이터를 지우거나 고치지 않는다. H2 는 DDL 이 바로 확정되므로 이때 남는 것은 ① 의 별칭뿐이다 — 정리 → repair → 다시 실행) ③ DDL ④ 예제 메타데이터.
-- 예제(프로젝트 0) : 도메인 값V765 · 용어 컬럼_명_비교_값 → TB_COL 컬럼 → 엔터티 속성 → 속성 매핑 → 제약조건 UX_COL_01 · CK_COL_01 → 업무 설명,
--   TB_COL.TBL_SN·COL_NM 필수. 예제 모델은 생성 컬럼을 표현하지 못한다 (예제에는 일반 컬럼으로 보이고 업무 설명에 생성 컬럼임을 적는다).
-- =====================================================================

-- ①
create alias if not exists FN_UPPER_ROOT deterministic for 'org.h2.util.StringUtils.toUpperEnglish';

-- ② 사전 검사 (필수값 · 새 기준 중복 · 길이) — 위반이 하나라도 있으면 유형별 건수와 처음 50개를 담은 형 변환 오류로 멈춘다
select cast('V5 컬럼 무결성 사전 검사 : 필수값 위반 ' || cast(max(x.REQ_CNT) as varchar) || '건 · 새 기준 중복 ' || cast(max(x.DUP_CNT) as varchar) || '개 그룹 · 비교 값 765자 초과 ' || cast(max(x.LEN_CNT) as varchar)
            || '건 (처음 50개) — ' || listagg(x.ITEM, ', ') within group (order by x.ORD, x.ITEM) as integer)
from (select y.ITEM, y.ORD, (select count(*) from TB_COL c where c.TBL_SN is null or c.COL_NM is null or trim(translate(c.COL_NM, chr(9) || chr(10) || chr(11) || chr(12) || chr(13), '     ')) = '') as REQ_CNT, (select count(*) from (select c.TBL_SN, FN_UPPER_ROOT(trim(c.COL_NM)) as NM_KEY from TB_COL c where c.TBL_SN is not null and c.COL_NM is not null group by c.TBL_SN, FN_UPPER_ROOT(trim(c.COL_NM)) having count(*) > 1) d) as DUP_CNT, (select count(*) from TB_COL c where c.COL_NM is not null and char_length(FN_UPPER_ROOT(trim(c.COL_NM))) > 765) as LEN_CNT from (
        select '필수값' as KIND, 1 as CNT, 1 as ORD, '[필수값] COL_SN=' || cast(c.COL_SN as varchar) || ' PRJ_SN=' || coalesce(cast(c.PRJ_SN as varchar), 'null') || ' TBL_SN=' || coalesce(cast(c.TBL_SN as varchar), 'null')
               || ' COL_NM=' || coalesce('[' || c.COL_NM || ']', 'null') as ITEM
        from TB_COL c where c.TBL_SN is null or c.COL_NM is null or trim(translate(c.COL_NM, chr(9) || chr(10) || chr(11) || chr(12) || chr(13), '     ')) = ''
        union all
        select '중복', count(*), 2, '[중복] TBL_SN=' || cast(g.TBL_SN as varchar) || ' 새 비교 값=' || g.NM_KEY || ' : '
               || listagg('COL_SN ' || cast(g.COL_SN as varchar) || ' [' || g.COL_NM || ']', ' · ') within group (order by g.COL_SN)
        from (select c.TBL_SN, c.COL_SN, c.COL_NM, FN_UPPER_ROOT(trim(c.COL_NM)) as NM_KEY from TB_COL c where c.TBL_SN is not null and c.COL_NM is not null) g
        group by g.TBL_SN, g.NM_KEY having count(*) > 1
        union all
        select '길이', 1, 3, '[길이] COL_SN=' || cast(c.COL_SN as varchar) || ' 비교 값 ' || cast(char_length(FN_UPPER_ROOT(trim(c.COL_NM))) as varchar) || '자'
        from TB_COL c where c.COL_NM is not null and char_length(FN_UPPER_ROOT(trim(c.COL_NM))) > 765) y
      order by y.ORD, y.ITEM fetch first 50 rows only) x
having count(*) > 0;

-- ③ DDL
alter table TB_COL alter column TBL_SN set not null;
alter table TB_COL alter column COL_NM set not null;
alter table TB_COL add constraint CK_COL_01 check (trim(translate(COL_NM, chr(9) || chr(10) || chr(11) || chr(12) || chr(13), '     ')) <> '');
alter table TB_COL add column COL_NM_CMPR_VL varchar(765) generated always as (FN_UPPER_ROOT(trim(COL_NM)));
alter table TB_COL add constraint UX_COL_01 unique (TBL_SN, COL_NM_CMPR_VL);
comment on column TB_COL.COL_NM_CMPR_VL is '컬럼 명 비교 값';

-- ④ 예제 메타데이터 (최종 상태 — 시퀀스는 테이블마다 예전 V5 → V6 → V7 과 같은 순서로 쓴다 : 제약조건 UX_COL_01 → CK_COL_01)
-- ---------- 예제 : 도메인 값V765 (비교 값 길이) ----------
insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, STRG_FRMT_CN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_DMN, 0, 1, '값', '값', '값V765', '비교·계산용으로 늘어날 수 있는 문자열 값. 원래 값(255자)이 대문자 변환으로 최대 3배까지 늘어나는 경우를 담는다.', 'VARCHAR', 765, '765자리 이내 문자', true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' where exists (select 1 from TB_PRJ where PRJ_SN = 0) and not exists (select 1 from TB_STD_DMN where PRJ_SN = 0 and DMN_NM = '값V765');
-- ---------- 예제 : 데이터 사전 (새 용어 1개 — 단어는 있다) ----------
insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, PBADMS_STD_CD_NM, JRSDC_INST_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM, 0, 1, '컬럼_명_비교_값', '컬럼 명 비교 값.', 'COL_NM_CMPR_VL', null, null, d.STD_DMN_SN, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_DMN d where d.PRJ_SN = 0 and d.DMN_NM = '값V765' and not exists (select 1 from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '컬럼_명_비교_값');
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM_WORD, t.STD_TRM_SN, w.STD_WORD_SN, 1, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_TRM t join TB_STD_WORD w on w.PRJ_SN = 0 and w.WORD_NM = '컬럼' where t.PRJ_SN = 0 and t.TRM_NM = '컬럼_명_비교_값' and not exists (select 1 from TB_STD_TRM_WORD x where x.STD_TRM_SN = t.STD_TRM_SN and x.SORT_ORD = 1);
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM_WORD, t.STD_TRM_SN, w.STD_WORD_SN, 2, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_TRM t join TB_STD_WORD w on w.PRJ_SN = 0 and w.WORD_NM = '명' where t.PRJ_SN = 0 and t.TRM_NM = '컬럼_명_비교_값' and not exists (select 1 from TB_STD_TRM_WORD x where x.STD_TRM_SN = t.STD_TRM_SN and x.SORT_ORD = 2);
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM_WORD, t.STD_TRM_SN, w.STD_WORD_SN, 3, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_TRM t join TB_STD_WORD w on w.PRJ_SN = 0 and w.WORD_NM = '비교' where t.PRJ_SN = 0 and t.TRM_NM = '컬럼_명_비교_값' and not exists (select 1 from TB_STD_TRM_WORD x where x.STD_TRM_SN = t.STD_TRM_SN and x.SORT_ORD = 3);
insert into TB_STD_TRM_WORD(STD_TRM_WORD_SN, STD_TRM_SN, STD_WORD_SN, SORT_ORD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_STD_TRM_WORD, t.STD_TRM_SN, w.STD_WORD_SN, 4, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_STD_TRM t join TB_STD_WORD w on w.PRJ_SN = 0 and w.WORD_NM = '값' where t.PRJ_SN = 0 and t.TRM_NM = '컬럼_명_비교_값' and not exists (select 1 from TB_STD_TRM_WORD x where x.STD_TRM_SN = t.STD_TRM_SN and x.SORT_ORD = 4);

-- ---------- 예제 : 물리 모델 TB_COL 에 새 컬럼 (TB_COL 의 마지막 순서 다음) ----------
insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, TBL_NM, COL_NM, COL_EXPLN, DATA_TYPE_NM, MAX_LEN, DATA_PRCSN, DCPT_LEN, NULL_ALWNC_YN, BSC_VL, SORT_ORD, MSTR_DATA_YN, RFRNC_TBL_NM, ESNTL_YN, SNSTV_INFO_YN, PK_YN, UNQ_YN, IDX_YN, ENCPT_YN, APRV_YN, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_COL, 0, t.TBL_SN, 'TB_COL', 'COL_NM_CMPR_VL', '컬럼 명 비교 값', 'VARCHAR', 765, null, null, true, null, (select coalesce(max(c.SORT_ORD), 0) + 1 from TB_COL c where c.TBL_SN = t.TBL_SN), false, null, false, false, false, false, false, false, true, (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '컬럼_명_비교_값'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_TBL t where t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' and not exists (select 1 from TB_COL c where c.TBL_SN = t.TBL_SN and c.COL_NM = 'COL_NM_CMPR_VL');
-- ---------- 예제 : 논리 모델 (TB_COL 에 매핑된 엔터티의 마지막 순서 다음 속성) ----------
insert into TB_LGCL_ATRB(LGCL_ATRB_SN, LGCL_ENTT_SN, ATRB_NM, ATRB_EXPLN, DATA_TYPE_NM, ESNTL_YN, SORT_ORD, STD_TRM_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_LGCL_ATRB, mp.LGCL_ENTT_SN, '컬럼 명 비교 값', null, null, false, (select coalesce(max(a.SORT_ORD), 0) + 1 from TB_LGCL_ATRB a where a.LGCL_ENTT_SN = mp.LGCL_ENTT_SN), (select STD_TRM_SN from TB_STD_TRM where PRJ_SN = 0 and TRM_NM = '컬럼_명_비교_값'), current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_ENTT_TBL_MPNG mp join TB_TBL t on t.TBL_SN = mp.TBL_SN and t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' where mp.PRJ_SN = 0 and not exists (select 1 from TB_LGCL_ATRB a where a.LGCL_ENTT_SN = mp.LGCL_ENTT_SN and a.ATRB_NM = '컬럼 명 비교 값');
-- ---------- 예제 : 논리 ↔ 물리 매핑 (새 속성 ↔ 새 컬럼) ----------
insert into TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_ATRB_COL_MPNG, mp.ENTT_TBL_MPNG_SN, a.LGCL_ATRB_SN, c.COL_SN, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_ENTT_TBL_MPNG mp join TB_TBL t on t.TBL_SN = mp.TBL_SN and t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' join TB_LGCL_ATRB a on a.LGCL_ENTT_SN = mp.LGCL_ENTT_SN and a.ATRB_NM = '컬럼 명 비교 값' join TB_COL c on c.TBL_SN = t.TBL_SN and c.COL_NM = 'COL_NM_CMPR_VL' where mp.PRJ_SN = 0 and not exists (select 1 from TB_ATRB_COL_MPNG x where x.LGCL_ATRB_SN = a.LGCL_ATRB_SN) and not exists (select 1 from TB_ATRB_COL_MPNG x where x.COL_SN = c.COL_SN);
-- ---------- 예제 : UNIQUE 제약조건 UX_COL_01 (TBL_SN, COL_NM_CMPR_VL) ----------
insert into TB_TBL_CNSTRT(CNSTRT_SN, PRJ_SN, TBL_SN, CNSTRT_NM, CNSTRT_TYPE_CD, INSP_EXPR_CN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_TBL_CNSTRT, 0, t.TBL_SN, 'UX_COL_01', 'UNIQUE', null, true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_TBL t where t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' and exists (select 1 from TB_COL c where c.TBL_SN = t.TBL_SN and c.COL_NM = 'TBL_SN') and exists (select 1 from TB_COL c where c.TBL_SN = t.TBL_SN and c.COL_NM = 'COL_NM_CMPR_VL') and not exists (select 1 from TB_TBL_CNSTRT k where k.TBL_SN = t.TBL_SN and upper(k.CNSTRT_NM) = 'UX_COL_01');
insert into TB_TBL_CNSTRT_COL(CNSTRT_COL_SN, CNSTRT_SN, COL_SN, COL_ORD) select next value for SQ_TBL_CNSTRT_COL, k.CNSTRT_SN, c.COL_SN, 1 from TB_TBL_CNSTRT k join TB_TBL t on t.TBL_SN = k.TBL_SN and t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' join TB_COL c on c.TBL_SN = t.TBL_SN and c.COL_NM = 'TBL_SN' where upper(k.CNSTRT_NM) = 'UX_COL_01' and not exists (select 1 from TB_TBL_CNSTRT_COL x where x.CNSTRT_SN = k.CNSTRT_SN and x.COL_ORD = 1);
insert into TB_TBL_CNSTRT_COL(CNSTRT_COL_SN, CNSTRT_SN, COL_SN, COL_ORD) select next value for SQ_TBL_CNSTRT_COL, k.CNSTRT_SN, c.COL_SN, 2 from TB_TBL_CNSTRT k join TB_TBL t on t.TBL_SN = k.TBL_SN and t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' join TB_COL c on c.TBL_SN = t.TBL_SN and c.COL_NM = 'COL_NM_CMPR_VL' where upper(k.CNSTRT_NM) = 'UX_COL_01' and not exists (select 1 from TB_TBL_CNSTRT_COL x where x.CNSTRT_SN = k.CNSTRT_SN and x.COL_ORD = 2);
-- ---------- 예제 : 업무 설명 ----------
update TB_COL set TASK_EXPLN = '같은 테이블 안 컬럼명 중복을 막는 비교 값. DB 가 컬럼명에서 앞뒤 공백을 지우고 로캘과 무관한 유니코드 대문자(ß → SS 처럼 글자가 늘 수 있다)로 바꿔 계산하는 생성 컬럼이며 (테이블 일련번호, 이 값) 이 UNIQUE 다. 직접 쓰지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'COL_NM_CMPR_VL' and TASK_EXPLN is null;
-- ---------- 예제 : TB_COL.TBL_SN · COL_NM = NULL 불가·필수, 논리 속성도 필수 ----------
update TB_COL set NULL_ALWNC_YN = false, ESNTL_YN = true, MDFCN_DT = current_timestamp where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM in ('TBL_SN', 'COL_NM');
update TB_LGCL_ATRB set ESNTL_YN = true, MDFCN_DT = current_timestamp where STD_TRM_SN in (select c.STD_TRM_SN from TB_COL c where c.PRJ_SN = 0 and c.TBL_NM = 'TB_COL' and c.COL_NM in ('TBL_SN', 'COL_NM'))
  and LGCL_ATRB_SN in (select m.LGCL_ATRB_SN from TB_ATRB_COL_MPNG m join TB_COL c on c.COL_SN = m.COL_SN where c.PRJ_SN = 0 and c.TBL_NM = 'TB_COL' and c.COL_NM in ('TBL_SN', 'COL_NM'));
-- ---------- 예제 : CHECK 제약조건 CK_COL_01 (COL_NM) ----------
insert into TB_TBL_CNSTRT(CNSTRT_SN, PRJ_SN, TBL_SN, CNSTRT_NM, CNSTRT_TYPE_CD, INSP_EXPR_CN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) select next value for SQ_TBL_CNSTRT, 0, t.TBL_SN, 'CK_COL_01', 'CHECK', '(trim(translate(col_nm, chr(9) || chr(10) || chr(11) || chr(12) || chr(13), ''     '')) <> '''')', true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM' from TB_TBL t where t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' and not exists (select 1 from TB_TBL_CNSTRT k where k.TBL_SN = t.TBL_SN and upper(k.CNSTRT_NM) = 'CK_COL_01');
insert into TB_TBL_CNSTRT_COL(CNSTRT_COL_SN, CNSTRT_SN, COL_SN, COL_ORD) select next value for SQ_TBL_CNSTRT_COL, k.CNSTRT_SN, c.COL_SN, 1 from TB_TBL_CNSTRT k join TB_TBL t on t.TBL_SN = k.TBL_SN and t.PRJ_SN = 0 and t.TBL_NM = 'TB_COL' join TB_COL c on c.TBL_SN = t.TBL_SN and c.COL_NM = 'COL_NM' where upper(k.CNSTRT_NM) = 'CK_COL_01' and not exists (select 1 from TB_TBL_CNSTRT_COL x where x.CNSTRT_SN = k.CNSTRT_SN and x.COL_ORD = 1);
