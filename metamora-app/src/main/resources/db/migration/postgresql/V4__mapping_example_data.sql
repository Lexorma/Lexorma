-- META-MORA 프로젝트(PRJ_SN 0) 예제의 논리 모델과 물리 모델을 매핑으로 잇는다 (M-7). 스키마는 바꾸지 않고 매핑 행만 넣는다.
--   1) 엔터티 ↔ 테이블 : 엔터티명 = 테이블 한글명 (V1·V3 이 물리 예제의 한글명으로 엔터티를 만들었다)
--   2) 속성 ↔ 컬럼     : 1) 로 이어진 엔터티·테이블 안에서 같은 표준 용어를 참조하는 속성과 컬럼
-- 예제(SYSTEM 이 넣은 데이터)에만 해당하는 규칙이다. 애플리케이션은 이름이나 용어가 같다고 자동으로 매핑하지 않는다.
-- 기존 DB 에서도 안전하도록 이미 매핑된 엔터티·테이블·속성·컬럼과, 짝이 하나로 정해지지 않는 것(같은 한글명의 테이블이 여럿 등)은 건너뛴다.
-- ID 는 시퀀스에서 받는다 (V1·V3 과 같다).

-- ---------- 1) 엔터티 ↔ 테이블 ----------

insert into TB_ENTT_TBL_MPNG(ENTT_TBL_MPNG_SN, PRJ_SN, LGCL_ENTT_SN, TBL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)
select nextval('SQ_ENTT_TBL_MPNG'), 0, e.LGCL_ENTT_SN, t.TBL_SN, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM'
from TB_LGCL_ENTT e
join TB_LGCL_MDL m on m.LGCL_MDL_SN = e.LGCL_MDL_SN and m.PRJ_SN = 0 and m.MDL_NM = 'META-MORA'
join TB_TBL t on t.PRJ_SN = 0 and t.TBL_KORN_NM = e.ENTT_NM
where e.PRJ_SN = 0
  and (select count(*) from TB_TBL t2 where t2.PRJ_SN = 0 and t2.TBL_KORN_NM = e.ENTT_NM) = 1
  and not exists (select 1 from TB_ENTT_TBL_MPNG x where x.LGCL_ENTT_SN = e.LGCL_ENTT_SN)
  and not exists (select 1 from TB_ENTT_TBL_MPNG x where x.TBL_SN = t.TBL_SN);

-- ---------- 2) 속성 ↔ 컬럼 ----------

insert into TB_ATRB_COL_MPNG(ATRB_COL_MPNG_SN, ENTT_TBL_MPNG_SN, LGCL_ATRB_SN, COL_SN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)
select nextval('SQ_ATRB_COL_MPNG'), mp.ENTT_TBL_MPNG_SN, a.LGCL_ATRB_SN, c.COL_SN, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM'
from TB_ENTT_TBL_MPNG mp
join TB_LGCL_ENTT e on e.LGCL_ENTT_SN = mp.LGCL_ENTT_SN
join TB_LGCL_MDL m on m.LGCL_MDL_SN = e.LGCL_MDL_SN and m.PRJ_SN = 0 and m.MDL_NM = 'META-MORA'
join TB_TBL t on t.TBL_SN = mp.TBL_SN and t.TBL_KORN_NM = e.ENTT_NM
join TB_LGCL_ATRB a on a.LGCL_ENTT_SN = e.LGCL_ENTT_SN
join TB_COL c on c.TBL_SN = t.TBL_SN and c.STD_TRM_SN = a.STD_TRM_SN
where mp.PRJ_SN = 0
  and (select count(*) from TB_LGCL_ATRB a2 where a2.LGCL_ENTT_SN = e.LGCL_ENTT_SN and a2.STD_TRM_SN = a.STD_TRM_SN) = 1
  and (select count(*) from TB_COL c2 where c2.TBL_SN = t.TBL_SN and c2.STD_TRM_SN = a.STD_TRM_SN) = 1
  and not exists (select 1 from TB_ATRB_COL_MPNG x where x.LGCL_ATRB_SN = a.LGCL_ATRB_SN)
  and not exists (select 1 from TB_ATRB_COL_MPNG x where x.COL_SN = c.COL_SN);
