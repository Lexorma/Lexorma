-- =====================================================================
-- Lexorma 스키마 (V0) : 테이블·시퀀스·인덱스·외래 키·COMMENT. 데이터는 넣지 않는다 (초기 데이터는 V1__seed_data.sql).
--  * 명명 : 공통표준(static/excel/dataPortal.xlsx) 영문약어, 테이블은 TB_ 접두어 (docs/db-naming-standard.md)
--  * 테이블 : JPA 엔티티 기준 + 로그인 세션(SPRING_SESSION, SPRING_SESSION_ATTRIBUTES : spring-session-jdbc 고정 스키마)
--  * COMMENT : 테이블 = 한글명, 컬럼 = 한글 용어 (V1 의 예제 물리 모델과 같다)
-- PostgreSQL / H2 양쪽에 같이 반영한다 (세션 속성 컬럼 타입만 다르다).
-- 2026-11-13 : 예전 V2~V6 의 스키마 변경(원본 인덱스 표현식, 인덱스 관리 M-14, VIEW 관리 M-15)을 이 파일에 합쳤다.
-- 2026-11-15 : Flyway 초기화 — 그 뒤의 V2~V10 (원본 매핑 M-4c, 가져오기 M-4e, SEC-1, 매핑 예외 M-4g, FK 규칙·IDENTITY, AI-3, 기본 AI Provider, AI-5)을 다시 이 파일에 합쳤다.
--   추가된 컬럼은 해당 create table 에 넣었고(예전 ALTER 와 같은 컬럼 순서), 새 테이블·시퀀스·인덱스·FK·COMMENT 는 맨 끝의 '예전 Vn' 구간에 그 순서대로 있다.
--   이전 V0~V10 으로 만든 DB 는 이어서 쓸 수 없다 (체크섬·버전이 다르다) — 새로 만든다.
-- 2026-11-15 (두 번째) : 그 뒤의 증분 V2~V5 를 다시 V0·V1 로 합쳤다. 스키마 변경은 V2(AI-5 품질 평가 테이블)뿐이라 맨 끝 'AI-5 품질 평가' 구간에 있다.
--   V2 이상이 적용된 DB 는 이어서 쓸 수 없다 (체크섬·버전이 다르다) — 새로 만든다.
-- 2026-11-18 (세 번째) : 그 뒤의 증분 V2~V7 (AI 설명 평가 판정 이력 DEC-27, 프로젝트 AI 설정 DEC-18, 출처 컬럼 매핑 DEC-06, 출처 VIEW 정의 DEC-16,
--   내장 MCP DEC-31, 논리 관계 속성 짝 M-7) 을 다시 이 파일에 합쳤다. 모두 새 테이블이라 맨 끝 '예전 증분 Vn' 구간에 그 순서대로 있다.
--   V2 이상이 적용된 DB 는 이어서 쓸 수 없다 — 새로 만든다.
-- 2026-11-22 (네 번째, 사용자 결정) : 그 뒤의 증분 V2~V5 (논리 주제 영역 M-6, 슈퍼타입·서브타입 M-6, 상속 PK 컬럼 매핑 M-7, 컬럼 데이터 무결성 M7-U09) 를
--   다시 이 파일에 합쳤다. 맨 끝 '예전 증분 Vn (… 네 번째 합침)' 구간에 그 순서대로 있다 (V5 의 TB_COL 필수값·CK_COL_01·생성 컬럼·UX_COL_01 은 ALTER 그대로,
--   예전 V5 의 사전 검사는 새 설치에서 할 일이 없어 뺐다). V2~V5 가 적용된 DB 는 이어서 쓸 수 없다 — 새로 만든다. 다음 스키마 변경은 V2 다.
--   TABLE·VIEW 이름은 스키마 안에서 하나다 (UX_TBL_01·UX_VIEW_01 = SCHM_SN + 이름, 같은 스키마의 TABLE·VIEW 교차 중복은 서비스가 스키마 이름 공간 잠금 안에서 막는다).
-- =====================================================================

-- ---------- 시퀀스 ----------
-- SQ_STD_*·SQ_TBL·SQ_COL·SQ_TBL_REL·SQ_TBL_REL_COL 은 예제 데이터(1~)와 겹치지 않도록 1001 부터 시작한다.
-- 엑셀 업로드로 대량 저장하는 엔티티는 JDBC 배치 INSERT 를 위해 IDENTITY 대신 SEQUENCE(pooled, 50개씩 할당) 를 쓴다.

create sequence SQ_ACNT start with 1 increment by 50;
create sequence SQ_ROLE start with 1 increment by 50;
create sequence SQ_PRJ start with 1 increment by 50;
create sequence SQ_PRJ_MBR start with 1 increment by 50;
create sequence SQ_PST start with 51 increment by 50;
create sequence SQ_ATCH_FILE start with 1 increment by 50;
create sequence SQ_STD_WORD start with 1001 increment by 50;
create sequence SQ_STD_TRM start with 1001 increment by 50;
create sequence SQ_STD_TRM_WORD start with 1001 increment by 50;
create sequence SQ_STD_DMN start with 1001 increment by 50;
create sequence SQ_SCHM start with 1 increment by 50;
create sequence SQ_TBL start with 1001 increment by 50;
create sequence SQ_COL start with 1001 increment by 50;
create sequence SQ_CMN_COL start with 1 increment by 50;
create sequence SQ_TBL_REL start with 1001 increment by 50;
create sequence SQ_TBL_REL_COL start with 1001 increment by 50;
create sequence SQ_TBL_IDX start with 1 increment by 50;
create sequence SQ_TBL_IDX_COL start with 1 increment by 50;
create sequence SQ_TBL_CNSTRT start with 1 increment by 50;
create sequence SQ_TBL_CNSTRT_COL start with 1 increment by 50;
create sequence SQ_IDX_CHG_DMND start with 1 increment by 50;
create sequence SQ_VIEW start with 1 increment by 50;
create sequence SQ_VIEW_COL start with 1 increment by 50;
create sequence SQ_VIEW_RFRNC start with 1 increment by 50;
create sequence SQ_VIEW_CHG_DMND start with 1 increment by 50;
create sequence SQ_ERD_LYT start with 1 increment by 50;
create sequence SQ_LGCL_MDL start with 1 increment by 50;
create sequence SQ_LGCL_ENTT start with 1 increment by 50;
create sequence SQ_LGCL_ATRB start with 1 increment by 50;
create sequence SQ_LGCL_IDNTFR start with 1 increment by 50;
create sequence SQ_LGCL_IDNTFR_ATRB start with 1 increment by 50;
create sequence SQ_LGCL_REL start with 1 increment by 50;
create sequence SQ_LGCL_ERD_LYT start with 1 increment by 50;

-- ---------- 계정·권한·프로젝트 ----------

-- 계정
create table TB_ACNT (
    ACNT_SN bigint not null,
    USER_ID varchar(255),
    PSWD varchar(255),
    USER_NM varchar(255),
    ACTVTN_YN boolean,
    -- 비밀번호 변경 여부 : false 면 초기 비밀번호 (로그인 전에 변경해야 한다)
    PSWD_CHG_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_ACNT primary key (ACNT_SN)
);

-- 역할
create table TB_ROLE (
    ROLE_SN bigint not null,
    ROLE_NM varchar(255),
    ROLE_EXPLN varchar(255),
    EXPR_YN varchar(255),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_ROLE primary key (ROLE_SN)
);

-- 계정 역할
create table TB_ACNT_ROLE (
    ACNT_SN bigint not null,
    ROLE_SN bigint not null,
    constraint PK_ACNT_ROLE primary key (ACNT_SN, ROLE_SN)
);

-- 프로젝트
create table TB_PRJ (
    PRJ_SN bigint not null,
    PRJ_NM varchar(255),
    MNGR_ACNT_SN bigint,
    ACTVTN_YN boolean,
    AUTO_APRV_YN boolean,
    -- 데이터 사전 업로드 시 엑셀의 영문약어명 사용 여부 (false 면 용어 영문명을 단어 영문명으로 만든다)
    DIC_ABBR_USE_YN boolean,
    -- 표준 단어 기준 테이블명 생성 시 앞에 붙일 접두어 (예: TB_)
    TBL_PFX_NM varchar(20),
    -- 대상 DB (POSTGRESQL / ORACLE / MYSQL / MARIADB) : 테이블 관리에서 DDL 을 이 DB 기준으로 생성한다
    DB_TYPE_CD varchar(20) default 'POSTGRESQL' not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_PRJ primary key (PRJ_SN)
);

-- 프로젝트 회원
create table TB_PRJ_MBR (
    PRJ_MBR_SN bigint not null,
    ACNT_SN bigint,
    PRJ_SN bigint,
    ACTVTN_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_PRJ_MBR primary key (PRJ_MBR_SN)
);

-- 요청 로그
create table TB_LOG (
    LOG_SN bigint generated by default as identity,
    DMND_URL_ADDR varchar(255) not null,
    DMND_MTHD_NM varchar(255) not null,
    CNTN_IP_ADDR varchar(255) not null,
    PRJ_SN bigint,
    USER_ID varchar(255),
    ROLE_NM varchar(255),
    RSPNS_STTS_CD integer,
    DMND_CN TEXT,
    RSPNS_CN TEXT,
    ERR_CN TEXT,
    USER_AGNT_CN varchar(255),
    LOG_DT timestamp(6) not null,
    constraint PK_LOG primary key (LOG_SN)
);

-- ---------- 게시물 ----------

-- 게시물 범주
create table TB_PST_CTGRY (
    CTGRY_CD varchar(6) not null,
    CTGRY_NM varchar(30) not null,
    SORT_ORD integer not null,
    USE_YN char(1) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_PST_CTGRY primary key (CTGRY_CD)
);

-- 게시물
create table TB_PST (
    PST_SN bigint not null,
    PRJ_SN bigint,
    CTGRY_CD varchar(6),
    WRTR_ID varchar(16) not null,
    PST_TTL varchar(100) not null,
    PST_CN TEXT,
    DEL_YN char(1) not null,
    PST_MDFCN_DT timestamp(6),
    PRJ_SN_LIST bigint array,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_PST primary key (PST_SN)
);

-- 게시물 계정
create table TB_PST_ACNT (
    PST_SN bigint not null,
    ACNT_SN bigint not null,
    constraint PK_PST_ACNT primary key (PST_SN, ACNT_SN)
);

-- 첨부 파일
create table TB_ATCH_FILE (
    FILE_SN bigint not null,
    PST_SN bigint,
    FILE_NM varchar(200) not null,
    ORGNL_FILE_NM varchar(200) not null,
    FILE_EXTN_NM varchar(15) not null,
    FILE_PATH_NM varchar(300) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_ATCH_FILE primary key (FILE_SN)
);

-- ---------- 데이터 사전 (표준 단어·용어·도메인) ----------

-- 표준 도메인
create table TB_STD_DMN (
    STD_DMN_SN bigint not null,
    PRJ_SN bigint,
    ENCT_CYCL integer not null,
    DMN_GROUP_NM varchar(255),
    DMN_CLSF_NM varchar(255),
    DMN_NM varchar(255),
    DMN_EXPLN varchar(1000),
    DATA_TYPE_NM varchar(255),
    DATA_LEN integer,
    DATA_DCPT_LEN integer,
    STRG_FRMT_CN varchar(255),
    INDCT_FRMT_CN varchar(255),
    UNIT_NM varchar(255),
    ALWNC_VL_CN varchar(2000),
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_STD_DMN primary key (STD_DMN_SN)
);

-- 표준 단어
create table TB_STD_WORD (
    STD_WORD_SN bigint not null,
    PRJ_SN bigint,
    ENCT_CYCL integer not null,
    WORD_NM varchar(255),
    WORD_ENG_ABBR_NM varchar(255),
    WORD_ENG_NM varchar(255),
    WORD_EXPLN varchar(1000),
    FRMT_WORD_YN boolean,
    DMN_CLSF_NM varchar(255),
    ABBR_USE_YN boolean,
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_STD_WORD primary key (STD_WORD_SN)
);

-- 표준 단어 이음동의어
create table TB_STD_WORD_SYNM (
    STD_WORD_SN bigint not null,
    SYNM_NM varchar(255)
);

-- 표준 단어 금칙어
create table TB_STD_WORD_PRHBT (
    STD_WORD_SN bigint not null,
    PRHBT_WORD_NM varchar(255)
);

-- 표준 용어
create table TB_STD_TRM (
    STD_TRM_SN bigint not null,
    PRJ_SN bigint,
    ENCT_CYCL integer not null,
    TRM_NM varchar(255),
    TRM_EXPLN varchar(1000),
    TRM_ENG_ABBR_NM varchar(255),
    PBADMS_STD_CD_NM varchar(255),
    JRSDC_INST_NM varchar(255),
    STD_DMN_SN bigint,
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_STD_TRM primary key (STD_TRM_SN)
);

-- 표준 용어 이음동의어
create table TB_STD_TRM_SYNM (
    STD_TRM_SN bigint not null,
    SYNM_NM varchar(255)
);

-- 표준 용어를 이루는 단어 (SORT_ORD : 사용자_명 → 사용자(1), 명(2))
create table TB_STD_TRM_WORD (
    STD_TRM_WORD_SN bigint not null,
    STD_TRM_SN bigint,
    STD_WORD_SN bigint,
    SORT_ORD integer not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_STD_TRM_WORD primary key (STD_TRM_WORD_SN)
);

-- ---------- 물리 모델 (스키마 → 테이블 → 컬럼, 관계·인덱스·제약조건) ----------

-- 스키마 : 프로젝트마다 기본 스키마 1개 (DDL 에 스키마명을 붙이지 않는다). 스키마명은 프로젝트 안에서 중복 불가.
-- 테이블·VIEW 의 이름 공간 단위이기도 하다 (SCHM_SN 은 프로젝트 전체에서 유일한 내부 ID)
create table TB_SCHM (
    SCHM_SN bigint not null,
    PRJ_SN bigint not null,
    SCHM_NM varchar(255) not null,
    SCHM_EXPLN varchar(255),
    BSC_YN boolean not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SCHM primary key (SCHM_SN)
);

-- 테이블 : 내부 ID(TBL_SN)로 식별하고, 이름(TBL_NM)은 스키마 안에서 하나다 (UX_TBL_01). 다른 스키마에는 같은 이름이 있을 수 있다
create table TB_TBL (
    TBL_SN bigint not null,
    PRJ_SN bigint not null,
    SCHM_SN bigint not null,
    TBL_NM varchar(255) not null,
    -- 테이블 한글명 (예: 사용_내역_관리) : 테이블명은 이 한글명을 표준 단어 영문약어로 변환해 만든다
    TBL_KORN_NM varchar(255),
    TBL_EXPLN varchar(255),
    MSTR_TBL_YN boolean,
    SORT_ORD integer,
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    SRC_ANTN_CN TEXT,
    TASK_EXPLN TEXT,
    constraint PK_TBL primary key (TBL_SN)
);

-- 컬럼 (RFRNC_TBL_NM : 단일 FK 약식 입력. 확정 가능한 것만 관계(REF_COLUMN)가 된다)
create table TB_COL (
    COL_SN bigint not null,
    PRJ_SN bigint,
    TBL_SN bigint,
    TBL_NM varchar(255),
    COL_NM varchar(255),
    COL_EXPLN varchar(255),
    DATA_TYPE_NM varchar(255),
    MAX_LEN integer,
    DATA_PRCSN numeric(38,2),
    DCPT_LEN integer,
    NULL_ALWNC_YN boolean,
    BSC_VL varchar(255),
    SORT_ORD integer,
    MSTR_DATA_YN boolean,
    RFRNC_TBL_NM varchar(255),
    ESNTL_YN boolean,
    SNSTV_INFO_YN boolean,
    PK_YN boolean,
    UNQ_YN boolean,
    IDX_YN boolean,
    ENCPT_YN boolean,
    EXMPL_CN varchar(255),
    APRV_YN boolean,
    STD_TRM_SN bigint,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    SRC_ANTN_CN TEXT,
    AUTO_CRT_MTHD_CD varchar(20),
    TASK_EXPLN TEXT,
    constraint PK_COL primary key (COL_SN)
);

-- 프로젝트 공통 컬럼 세트 : 거의 모든 테이블에 들어가는 컬럼(등록일시, 등록자, 수정일시, 수정자, 사용여부 등)을
-- 표준 용어로 정해 두고, 컬럼 일괄 편집에서 한 번에 추가한다. (용어가 지워질 수 있으므로 STD_TRM_SN 은 FK 를 걸지 않는다)
create table TB_CMN_COL (
    CMN_COL_SN bigint not null,
    PRJ_SN bigint not null,
    STD_TRM_SN bigint not null,
    SORT_ORD integer,
    NULL_ALWNC_YN boolean,
    IDX_YN boolean,
    BSC_VL varchar(255),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_CMN_COL primary key (CMN_COL_SN)
);

-- 테이블 관계 (원본 → 대상, 컬럼 쌍은 TB_TBL_REL_COL). REL_TYPE_CD : FK / LOGICAL,
-- REL_SRC_CD : USER / REF_COLUMN / IMPORTED / AI_APPROVED, DRVN_COL_SN : REF_COLUMN 관계를 만든 컬럼 (컬럼당 1개)
create table TB_TBL_REL (
    REL_SN bigint not null,
    PRJ_SN bigint not null,
    ORGNL_TBL_SN bigint not null,
    TRGT_TBL_SN bigint not null,
    REL_NM varchar(255),
    REL_TYPE_CD varchar(20) not null,
    REL_SRC_CD varchar(20) not null,
    DRVN_COL_SN bigint,
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    DEL_RULE_CD varchar(20),
    MDFCN_RULE_CD varchar(20),
    constraint PK_TBL_REL primary key (REL_SN)
);

-- 테이블 관계 컬럼 쌍 (복합 FK 는 COL_ORD 순서)
create table TB_TBL_REL_COL (
    REL_COL_SN bigint not null,
    REL_SN bigint not null,
    ORGNL_COL_SN bigint not null,
    TRGT_COL_SN bigint not null,
    COL_ORD integer not null,
    constraint PK_TBL_REL_COL primary key (REL_COL_SN)
);

-- 테이블 인덱스
create table TB_TBL_IDX (
    IDX_SN bigint not null,
    PRJ_SN bigint not null,
    TBL_SN bigint not null,
    IDX_NM varchar(255) not null,
    IDX_TYPE_NM varchar(255),
    UNQ_YN boolean not null,
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    -- 출처 (M-14 D-19) : USER(사용자 정의) / COLUMN(컬럼의 인덱스 여부에서 만든 인덱스, DRVN_COL_SN = 그 컬럼)
    IDX_SRC_CD varchar(20) not null,
    DRVN_COL_SN bigint,
    -- WHERE 조건 (M-14 2차, PostgreSQL 부분 인덱스)
    CND_CN TEXT,
    constraint PK_TBL_IDX primary key (IDX_SN)
);

-- 인덱스 구성 요소 : 컬럼(COL_SN) 또는 표현식(EXPR_CN) 하나. SORT_CD = null(ASC) / DESC / DESC NULLS LAST / NULLS FIRST / INCLUDE(키가 아닌 포함 컬럼)
create table TB_TBL_IDX_COL (
    IDX_COL_SN bigint not null,
    IDX_SN bigint not null,
    COL_SN bigint,
    COL_ORD integer not null,
    EXPR_CN TEXT,
    SORT_CD varchar(20),
    constraint PK_TBL_IDX_COL primary key (IDX_COL_SN)
);

-- 승인된 인덱스의 변경 요청 (M-14 D-20)
-- DMND_STTS_CD : REQUESTED / APPROVED / REJECTED, 요청자·요청 일시 = RGTR_ID·REG_DT, 처리자·처리 일시 = MDFR_ID·MDFCN_DT
create table TB_IDX_CHG_DMND (
    IDX_CHG_DMND_SN bigint not null,
    PRJ_SN bigint not null,
    IDX_SN bigint not null,
    CHG_BFR_CN TEXT not null,
    CHG_AFTR_CN TEXT not null,
    DMND_STTS_CD varchar(20) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_IDX_CHG_DMND primary key (IDX_CHG_DMND_SN)
);

-- 테이블 제약조건 (CNSTRT_TYPE_CD : UNIQUE / CHECK)
create table TB_TBL_CNSTRT (
    CNSTRT_SN bigint not null,
    PRJ_SN bigint not null,
    TBL_SN bigint not null,
    CNSTRT_NM varchar(255) not null,
    CNSTRT_TYPE_CD varchar(20) not null,
    INSP_EXPR_CN varchar(1000),
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_TBL_CNSTRT primary key (CNSTRT_SN)
);

create table TB_TBL_CNSTRT_COL (
    CNSTRT_COL_SN bigint not null,
    CNSTRT_SN bigint not null,
    COL_SN bigint not null,
    COL_ORD integer not null,
    constraint PK_TBL_CNSTRT_COL primary key (CNSTRT_COL_SN)
);

-- VIEW (M-15) : 테이블 모델과 독립. 내부 ID(VIEW_SN)로 식별하고, 이름은 스키마 안에서 TABLE 이름과 같은 이름 공간이다
-- (대문자 저장, UX_VIEW_01 = 스키마 + 이름, TABLE 과의 교차 중복은 서비스가 스키마 이름 공간 잠금 안에서 확인한다)
-- VIEW_DFN_CN : CREATE VIEW … AS 뒤의 조회 문 원문 (실행하지 않는다)
create table TB_VIEW (
    VIEW_SN bigint not null,
    PRJ_SN bigint not null,
    SCHM_SN bigint not null,
    VIEW_NM varchar(255) not null,
    VIEW_KORN_NM varchar(255),
    VIEW_EXPLN varchar(1000),
    VIEW_DFN_CN TEXT not null,
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_VIEW primary key (VIEW_SN)
);
-- 출력 컬럼 : 사용자가 관리하는 메타데이터 (정의 SQL 에서 추출하지 않는다)
create table TB_VIEW_COL (
    VIEW_COL_SN bigint not null,
    VIEW_SN bigint not null,
    COL_NM varchar(255) not null,
    COL_KORN_NM varchar(255),
    DATA_TYPE_NM varchar(255),
    COL_ORD integer not null,
    COL_EXPLN varchar(255),
    constraint PK_VIEW_COL primary key (VIEW_COL_SN)
);
-- 참조 객체 : RFRNC_TYPE_CD TABLE / VIEW. 내부 참조는 RFRNC_TBL_SN 또는 RFRNC_VIEW_SN, 모델에 없는 객체(미확인)는 두 ID 가 비고 RFRNC_SCHM_NM·RFRNC_TRGT_NM 만 둔다
create table TB_VIEW_RFRNC (
    VIEW_RFRNC_SN bigint not null,
    VIEW_SN bigint not null,
    RFRNC_TYPE_CD varchar(20) not null,
    RFRNC_TBL_SN bigint,
    RFRNC_VIEW_SN bigint,
    RFRNC_SCHM_NM varchar(255),
    RFRNC_TRGT_NM varchar(255),
    constraint PK_VIEW_RFRNC primary key (VIEW_RFRNC_SN)
);
-- 승인된 VIEW 의 변경 요청 : 변경 전·후 정의 전체(기본 정보·정의 SQL·출력 컬럼·참조 객체) JSON. DMND_STTS_CD : REQUESTED / APPROVED / REJECTED
create table TB_VIEW_CHG_DMND (
    VIEW_CHG_DMND_SN bigint not null,
    PRJ_SN bigint not null,
    VIEW_SN bigint not null,
    CHG_BFR_CN TEXT not null,
    CHG_AFTR_CN TEXT not null,
    DMND_STTS_CD varchar(20) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_VIEW_CHG_DMND primary key (VIEW_CHG_DMND_SN)
);

-- 물리 ERD 레이아웃 : 사용자 × 프로젝트 1건 (물리 모델과 분리).
-- NODE_PSTN_CN : {"테이블 일련번호": {"x": .., "y": ..}} JSON, VER_NO : JPA @Version (오래된 탭의 저장 거절)
create table TB_ERD_LYT (
    ERD_LYT_SN bigint not null,
    PRJ_SN bigint not null,
    ACNT_SN bigint not null,
    NODE_PSTN_CN text not null,
    SCRN_X_CRDNT double precision not null,
    SCRN_Y_CRDNT double precision not null,
    SCRN_MGNF double precision not null,
    VER_NO bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_ERD_LYT primary key (ERD_LYT_SN)
);

-- 논리 모델 (M-6) : 물리 모델(TB_TBL·TB_COL)과 독립. 표준 용어만 참조한다. 카디널리티는 양쪽 최소·최대 참여 수 (최대 null = 다)
create table TB_LGCL_MDL (
    LGCL_MDL_SN bigint not null,
    PRJ_SN bigint not null,
    MDL_NM varchar(255) not null,
    MDL_EXPLN varchar(1000),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_MDL primary key (LGCL_MDL_SN)
);

create table TB_LGCL_ENTT (
    LGCL_ENTT_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    ENTT_NM varchar(255) not null,
    ENTT_EXPLN varchar(1000),
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_ENTT primary key (LGCL_ENTT_SN)
);

create table TB_LGCL_ATRB (
    LGCL_ATRB_SN bigint not null,
    LGCL_ENTT_SN bigint not null,
    ATRB_NM varchar(255) not null,
    ATRB_EXPLN varchar(1000),
    DATA_TYPE_NM varchar(255),
    ESNTL_YN boolean,
    SORT_ORD integer,
    STD_TRM_SN bigint,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_ATRB primary key (LGCL_ATRB_SN)
);

create table TB_LGCL_IDNTFR (
    LGCL_IDNTFR_SN bigint not null,
    LGCL_ENTT_SN bigint not null,
    IDNTFR_NM varchar(255) not null,
    BSC_YN boolean not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_IDNTFR primary key (LGCL_IDNTFR_SN)
);

create table TB_LGCL_IDNTFR_ATRB (
    LGCL_IDNTFR_ATRB_SN bigint not null,
    LGCL_IDNTFR_SN bigint not null,
    LGCL_ATRB_SN bigint not null,
    SORT_ORD integer not null,
    constraint PK_LGCL_IDNTFR_ATRB primary key (LGCL_IDNTFR_ATRB_SN)
);

create table TB_LGCL_REL (
    LGCL_REL_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    ORGNL_ENTT_SN bigint not null,
    TRGT_ENTT_SN bigint not null,
    REL_NM varchar(255),
    REL_EXPLN varchar(1000),
    IDNTF_YN boolean not null,
    ORGNL_MIN_CNT integer not null,
    ORGNL_MAX_CNT integer,
    TRGT_MIN_CNT integer not null,
    TRGT_MAX_CNT integer,
    APRV_YN boolean,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint CK_LGCL_REL_01 check (ORGNL_MIN_CNT >= 0 and (ORGNL_MAX_CNT is null or (ORGNL_MAX_CNT >= 1 and ORGNL_MAX_CNT >= ORGNL_MIN_CNT))),
    constraint CK_LGCL_REL_02 check (TRGT_MIN_CNT >= 0 and (TRGT_MAX_CNT is null or (TRGT_MAX_CNT >= 1 and TRGT_MAX_CNT >= TRGT_MIN_CNT))),
    constraint PK_LGCL_REL primary key (LGCL_REL_SN)
);

-- 논리 ERD 레이아웃 : 사용자 × 논리 모델 1건 (물리 ERD 레이아웃 TB_ERD_LYT 와 분리).
-- NODE_PSTN_CN : {"논리 엔터티 일련번호": {"x": .., "y": ..}} JSON, VER_NO : JPA @Version
create table TB_LGCL_ERD_LYT (
    LGCL_ERD_LYT_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    ACNT_SN bigint not null,
    NODE_PSTN_CN text not null,
    SCRN_X_CRDNT double precision not null,
    SCRN_Y_CRDNT double precision not null,
    SCRN_MGNF double precision not null,
    VER_NO bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_ERD_LYT primary key (LGCL_ERD_LYT_SN)
);

-- ---------- 인덱스 ----------

create unique index UX_SCHM_01 on TB_SCHM (PRJ_SN, SCHM_NM);
create unique index UX_TBL_01 on TB_TBL (SCHM_SN, TBL_NM);
create index IX_CMN_COL_01 on TB_CMN_COL (PRJ_SN);
create index IX_TBL_REL_01 on TB_TBL_REL (PRJ_SN);
create index IX_TBL_REL_02 on TB_TBL_REL (ORGNL_TBL_SN);
create index IX_TBL_REL_03 on TB_TBL_REL (TRGT_TBL_SN);
create unique index UX_TBL_REL_01 on TB_TBL_REL (DRVN_COL_SN);
create unique index UX_TBL_REL_COL_01 on TB_TBL_REL_COL (REL_SN, COL_ORD);
create index IX_TBL_REL_COL_01 on TB_TBL_REL_COL (ORGNL_COL_SN);
create index IX_TBL_REL_COL_02 on TB_TBL_REL_COL (TRGT_COL_SN);
create index IX_TBL_IDX_01 on TB_TBL_IDX (TBL_SN);
create unique index UX_TBL_IDX_01 on TB_TBL_IDX (DRVN_COL_SN);
create unique index UX_TBL_IDX_COL_01 on TB_TBL_IDX_COL (IDX_SN, COL_ORD);
create index IX_TBL_IDX_COL_01 on TB_TBL_IDX_COL (COL_SN);
create index IX_TBL_CNSTRT_01 on TB_TBL_CNSTRT (TBL_SN);
create unique index UX_TBL_CNSTRT_COL_01 on TB_TBL_CNSTRT_COL (CNSTRT_SN, COL_ORD);
create index IX_TBL_CNSTRT_COL_01 on TB_TBL_CNSTRT_COL (COL_SN);
create index IX_IDX_CHG_DMND_01 on TB_IDX_CHG_DMND (IDX_SN, DMND_STTS_CD);
create unique index UX_VIEW_01 on TB_VIEW (SCHM_SN, VIEW_NM);
create unique index UX_VIEW_COL_01 on TB_VIEW_COL (VIEW_SN, COL_ORD);
create unique index UX_VIEW_RFRNC_01 on TB_VIEW_RFRNC (VIEW_SN, RFRNC_TBL_SN);
create unique index UX_VIEW_RFRNC_02 on TB_VIEW_RFRNC (VIEW_SN, RFRNC_VIEW_SN);
create index IX_VIEW_RFRNC_01 on TB_VIEW_RFRNC (RFRNC_TBL_SN);
create index IX_VIEW_RFRNC_02 on TB_VIEW_RFRNC (RFRNC_VIEW_SN);
create index IX_VIEW_CHG_DMND_01 on TB_VIEW_CHG_DMND (VIEW_SN, DMND_STTS_CD);
create unique index UX_ERD_LYT_01 on TB_ERD_LYT (PRJ_SN, ACNT_SN);
create unique index UX_LGCL_MDL_01 on TB_LGCL_MDL (PRJ_SN, MDL_NM);
create unique index UX_LGCL_ENTT_01 on TB_LGCL_ENTT (LGCL_MDL_SN, ENTT_NM);
create unique index UX_LGCL_ATRB_01 on TB_LGCL_ATRB (LGCL_ENTT_SN, ATRB_NM);
create index IX_LGCL_ATRB_01 on TB_LGCL_ATRB (STD_TRM_SN);
create unique index UX_LGCL_IDNTFR_01 on TB_LGCL_IDNTFR (LGCL_ENTT_SN, IDNTFR_NM);
create unique index UX_LGCL_IDNTFR_ATRB_01 on TB_LGCL_IDNTFR_ATRB (LGCL_IDNTFR_SN, LGCL_ATRB_SN);
create index IX_LGCL_IDNTFR_ATRB_01 on TB_LGCL_IDNTFR_ATRB (LGCL_ATRB_SN);
create index IX_LGCL_REL_01 on TB_LGCL_REL (LGCL_MDL_SN);
create index IX_LGCL_REL_02 on TB_LGCL_REL (ORGNL_ENTT_SN);
create index IX_LGCL_REL_03 on TB_LGCL_REL (TRGT_ENTT_SN);
create unique index UX_LGCL_ERD_LYT_01 on TB_LGCL_ERD_LYT (LGCL_MDL_SN, ACNT_SN);

-- ---------- 외래 키 ----------

alter table TB_ACNT_ROLE add constraint FK_ACNT_ROLE_01 foreign key (ROLE_SN) references TB_ROLE;
alter table TB_ACNT_ROLE add constraint FK_ACNT_ROLE_02 foreign key (ACNT_SN) references TB_ACNT;
alter table TB_PRJ add constraint FK_PRJ_01 foreign key (MNGR_ACNT_SN) references TB_ACNT;
alter table TB_PRJ_MBR add constraint FK_PRJ_MBR_01 foreign key (ACNT_SN) references TB_ACNT;
alter table TB_PRJ_MBR add constraint FK_PRJ_MBR_02 foreign key (PRJ_SN) references TB_PRJ;
alter table TB_PST add constraint FK_PST_01 foreign key (CTGRY_CD) references TB_PST_CTGRY;
alter table TB_PST_ACNT add constraint FK_PST_ACNT_01 foreign key (ACNT_SN) references TB_ACNT;
alter table TB_PST_ACNT add constraint FK_PST_ACNT_02 foreign key (PST_SN) references TB_PST;
alter table TB_ATCH_FILE add constraint FK_ATCH_FILE_01 foreign key (PST_SN) references TB_PST;
alter table TB_STD_TRM add constraint FK_STD_TRM_01 foreign key (STD_DMN_SN) references TB_STD_DMN;
alter table TB_STD_TRM_SYNM add constraint FK_STD_TRM_SYNM_01 foreign key (STD_TRM_SN) references TB_STD_TRM;
alter table TB_STD_TRM_WORD add constraint FK_STD_TRM_WORD_01 foreign key (STD_TRM_SN) references TB_STD_TRM;
alter table TB_STD_TRM_WORD add constraint FK_STD_TRM_WORD_02 foreign key (STD_WORD_SN) references TB_STD_WORD;
alter table TB_STD_WORD_SYNM add constraint FK_STD_WORD_SYNM_01 foreign key (STD_WORD_SN) references TB_STD_WORD;
alter table TB_STD_WORD_PRHBT add constraint FK_STD_WORD_PRHBT_01 foreign key (STD_WORD_SN) references TB_STD_WORD;
alter table TB_TBL add constraint FK_TBL_01 foreign key (SCHM_SN) references TB_SCHM;
alter table TB_COL add constraint FK_COL_01 foreign key (TBL_SN) references TB_TBL;
alter table TB_COL add constraint FK_COL_02 foreign key (STD_TRM_SN) references TB_STD_TRM;
-- 테이블 삭제는 컬럼이 없을 때만 가능하므로 테이블 쪽은 CASCADE, 컬럼 쪽은 서비스가 먼저 정리한다 (RESTRICT)
alter table TB_TBL_REL add constraint FK_TBL_REL_01 foreign key (ORGNL_TBL_SN) references TB_TBL on delete cascade;
alter table TB_TBL_REL add constraint FK_TBL_REL_02 foreign key (TRGT_TBL_SN) references TB_TBL on delete cascade;
alter table TB_TBL_REL_COL add constraint FK_TBL_REL_COL_01 foreign key (REL_SN) references TB_TBL_REL on delete cascade;
alter table TB_TBL_REL_COL add constraint FK_TBL_REL_COL_02 foreign key (ORGNL_COL_SN) references TB_COL;
alter table TB_TBL_REL_COL add constraint FK_TBL_REL_COL_03 foreign key (TRGT_COL_SN) references TB_COL;
alter table TB_TBL_IDX add constraint FK_TBL_IDX_01 foreign key (TBL_SN) references TB_TBL on delete cascade;
alter table TB_TBL_IDX_COL add constraint FK_TBL_IDX_COL_01 foreign key (IDX_SN) references TB_TBL_IDX on delete cascade;
alter table TB_TBL_IDX_COL add constraint FK_TBL_IDX_COL_02 foreign key (COL_SN) references TB_COL;
alter table TB_TBL_CNSTRT add constraint FK_TBL_CNSTRT_01 foreign key (TBL_SN) references TB_TBL on delete cascade;
alter table TB_TBL_CNSTRT_COL add constraint FK_TBL_CNSTRT_COL_01 foreign key (CNSTRT_SN) references TB_TBL_CNSTRT on delete cascade;
alter table TB_TBL_CNSTRT_COL add constraint FK_TBL_CNSTRT_COL_02 foreign key (COL_SN) references TB_COL;
alter table TB_IDX_CHG_DMND add constraint FK_IDX_CHG_DMND_01 foreign key (IDX_SN) references TB_TBL_IDX on delete cascade;
-- VIEW 의 참조 대상(TABLE·VIEW)은 CASCADE 하지 않는다 : 참조하는 VIEW 가 있으면 삭제를 막는다 (서비스가 먼저 알린다)
alter table TB_VIEW add constraint FK_VIEW_01 foreign key (SCHM_SN) references TB_SCHM;
alter table TB_VIEW_COL add constraint FK_VIEW_COL_01 foreign key (VIEW_SN) references TB_VIEW on delete cascade;
alter table TB_VIEW_RFRNC add constraint FK_VIEW_RFRNC_01 foreign key (VIEW_SN) references TB_VIEW on delete cascade;
alter table TB_VIEW_RFRNC add constraint FK_VIEW_RFRNC_02 foreign key (RFRNC_TBL_SN) references TB_TBL;
alter table TB_VIEW_RFRNC add constraint FK_VIEW_RFRNC_03 foreign key (RFRNC_VIEW_SN) references TB_VIEW;
alter table TB_VIEW_CHG_DMND add constraint FK_VIEW_CHG_DMND_01 foreign key (VIEW_SN) references TB_VIEW on delete cascade;
-- 논리 모델 : 삭제 순서는 서비스가 정한다 (엔터티는 관계가 없을 때만, 속성은 식별자에 쓰이지 않을 때만) - CASCADE 없음
alter table TB_LGCL_ENTT add constraint FK_LGCL_ENTT_01 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;
alter table TB_LGCL_ATRB add constraint FK_LGCL_ATRB_01 foreign key (LGCL_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_LGCL_ATRB add constraint FK_LGCL_ATRB_02 foreign key (STD_TRM_SN) references TB_STD_TRM;
alter table TB_LGCL_IDNTFR add constraint FK_LGCL_IDNTFR_01 foreign key (LGCL_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_LGCL_IDNTFR_ATRB add constraint FK_LGCL_IDNTFR_ATRB_01 foreign key (LGCL_IDNTFR_SN) references TB_LGCL_IDNTFR;
alter table TB_LGCL_IDNTFR_ATRB add constraint FK_LGCL_IDNTFR_ATRB_02 foreign key (LGCL_ATRB_SN) references TB_LGCL_ATRB;
alter table TB_LGCL_REL add constraint FK_LGCL_REL_01 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;
alter table TB_LGCL_REL add constraint FK_LGCL_REL_02 foreign key (ORGNL_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_LGCL_REL add constraint FK_LGCL_REL_03 foreign key (TRGT_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_LGCL_ERD_LYT add constraint FK_LGCL_ERD_LYT_01 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;


-- ---------- 논리·물리 모델 매핑 (엔터티 ↔ 테이블, 속성 ↔ 컬럼) ----------
-- 1:1 매핑은 UNIQUE 로, 원본(엔터티·테이블·속성·컬럼) 삭제 차단은 CASCADE 없는 FK 로 DB 에서도 보장한다.
-- 속성 매핑의 엔터티·테이블이 부모 매핑과 같은지는 서비스가 검사한다 (ModelMappingService).

create sequence SQ_ENTT_TBL_MPNG start with 1 increment by 50;
create sequence SQ_ATRB_COL_MPNG start with 1 increment by 50;

create table TB_ENTT_TBL_MPNG (
    ENTT_TBL_MPNG_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_ENTT_SN bigint not null,
    TBL_SN bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_ENTT_TBL_MPNG primary key (ENTT_TBL_MPNG_SN)
);

create table TB_ATRB_COL_MPNG (
    ATRB_COL_MPNG_SN bigint not null,
    ENTT_TBL_MPNG_SN bigint not null,
    LGCL_ATRB_SN bigint not null,
    COL_SN bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_ATRB_COL_MPNG primary key (ATRB_COL_MPNG_SN)
);

create unique index UX_ENTT_TBL_MPNG_01 on TB_ENTT_TBL_MPNG (LGCL_ENTT_SN);
create unique index UX_ENTT_TBL_MPNG_02 on TB_ENTT_TBL_MPNG (TBL_SN);
create index IX_ENTT_TBL_MPNG_01 on TB_ENTT_TBL_MPNG (PRJ_SN);
create unique index UX_ATRB_COL_MPNG_01 on TB_ATRB_COL_MPNG (LGCL_ATRB_SN);
create unique index UX_ATRB_COL_MPNG_02 on TB_ATRB_COL_MPNG (COL_SN);
create index IX_ATRB_COL_MPNG_01 on TB_ATRB_COL_MPNG (ENTT_TBL_MPNG_SN);

alter table TB_ENTT_TBL_MPNG add constraint FK_ENTT_TBL_MPNG_01 foreign key (LGCL_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_ENTT_TBL_MPNG add constraint FK_ENTT_TBL_MPNG_02 foreign key (TBL_SN) references TB_TBL;
alter table TB_ATRB_COL_MPNG add constraint FK_ATRB_COL_MPNG_01 foreign key (ENTT_TBL_MPNG_SN) references TB_ENTT_TBL_MPNG;
alter table TB_ATRB_COL_MPNG add constraint FK_ATRB_COL_MPNG_02 foreign key (LGCL_ATRB_SN) references TB_LGCL_ATRB;
alter table TB_ATRB_COL_MPNG add constraint FK_ATRB_COL_MPNG_03 foreign key (COL_SN) references TB_COL;

-- ---------- 모델 변경 이력 ----------
-- 대상이 삭제돼도 이력이 남아야 하므로 모델 테이블로 가는 FK 는 두지 않는다.

create sequence SQ_MDL_CHG_HSTRY start with 1 increment by 50;

create table TB_MDL_CHG_HSTRY (
    MDL_CHG_HSTRY_SN bigint not null,
    PRJ_SN bigint not null,
    CHG_GROUP_ID varchar(255) not null,
    TRGT_TYPE_CD varchar(20) not null,
    TRGT_SN bigint not null,
    TRGT_NM varchar(255),
    CHG_TYPE_CD varchar(20) not null,
    CHG_BFR_CN TEXT,
    CHG_AFTR_CN TEXT,
    CHG_SRC_CD varchar(20) not null,
    USER_ID varchar(255),
    CHG_DT timestamp(6) not null,
    constraint PK_MDL_CHG_HSTRY primary key (MDL_CHG_HSTRY_SN)
);

-- 프로젝트의 최신 이력 / 객체별 이력 / 변경 그룹
create index IX_MDL_CHG_HSTRY_01 on TB_MDL_CHG_HSTRY (PRJ_SN, CHG_DT);
create index IX_MDL_CHG_HSTRY_02 on TB_MDL_CHG_HSTRY (PRJ_SN, TRGT_TYPE_CD, TRGT_SN);
create index IX_MDL_CHG_HSTRY_03 on TB_MDL_CHG_HSTRY (CHG_GROUP_ID);

-- ---------- 모델 버전 (M-9 3차) ----------
-- 사용자가 명시적으로 만든 시점의 모델 전체 구조(JSON). 추가만 한다 (수정·삭제 API 없음). 변경 이력과 같이 모델 테이블로 가는 FK 는 두지 않는다.

create sequence SQ_MDL_VER start with 1 increment by 50;

create table TB_MDL_VER (
    MDL_VER_SN bigint not null,
    PRJ_SN bigint not null,
    VER_NM varchar(100) not null,
    VER_EXPLN varchar(1000),
    FRMT_VER_NO integer not null,
    MDL_CNT_CN TEXT not null,
    MDL_CN TEXT not null,
    RGTR_ID varchar(255),
    REG_DT timestamp(6) not null,
    constraint PK_MDL_VER primary key (MDL_VER_SN)
);

-- 프로젝트 안에서 버전명은 하나뿐이다 (동시에 같은 이름으로 만들어도 하나만 남는다)
create unique index UK_MDL_VER_01 on TB_MDL_VER (PRJ_SN, VER_NM);

-- ---------- 로그인 세션 (Spring Session JDBC) ----------
-- spring-session-jdbc 4.1.1 의 schema-postgresql.sql 그대로 (spring.session.jdbc.initialize-schema: never). 라이브러리 고정 스키마라 명명 규칙에서 제외한다.
-- 로그인 세션을 DB 에 보관해 애플리케이션 재시작/컨테이너 재생성 후에도 유지한다. 만료 세션은 Spring Session 이 주기적으로 지운다.

CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);

-- ---------- COMMENT (테이블 = 한글명, 컬럼 = 한글 용어) ----------

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
comment on column TB_TBL_IDX.IDX_SRC_CD is '인덱스 출처 코드';
comment on column TB_TBL_IDX.DRVN_COL_SN is '파생 컬럼 일련번호';
comment on column TB_TBL_IDX.CND_CN is '조건 내용';

comment on table TB_TBL_IDX_COL is '테이블 인덱스 컬럼';
comment on column TB_TBL_IDX_COL.IDX_COL_SN is '인덱스 컬럼 일련번호';
comment on column TB_TBL_IDX_COL.IDX_SN is '인덱스 일련번호';
comment on column TB_TBL_IDX_COL.COL_SN is '컬럼 일련번호';
comment on column TB_TBL_IDX_COL.COL_ORD is '컬럼 순서';
comment on column TB_TBL_IDX_COL.EXPR_CN is '표현식 내용';
comment on column TB_TBL_IDX_COL.SORT_CD is '정렬 코드';

comment on table TB_IDX_CHG_DMND is '인덱스 변경 요청';
comment on column TB_IDX_CHG_DMND.IDX_CHG_DMND_SN is '인덱스 변경 요청 일련번호';
comment on column TB_IDX_CHG_DMND.PRJ_SN is '프로젝트 일련번호';
comment on column TB_IDX_CHG_DMND.IDX_SN is '인덱스 일련번호';
comment on column TB_IDX_CHG_DMND.CHG_BFR_CN is '변경 이전 내용';
comment on column TB_IDX_CHG_DMND.CHG_AFTR_CN is '변경 이후 내용';
comment on column TB_IDX_CHG_DMND.DMND_STTS_CD is '요청 상태 코드';
comment on column TB_IDX_CHG_DMND.REG_DT is '등록 일시';
comment on column TB_IDX_CHG_DMND.MDFCN_DT is '수정 일시';
comment on column TB_IDX_CHG_DMND.RGTR_ID is '등록자 아이디';
comment on column TB_IDX_CHG_DMND.MDFR_ID is '수정자 아이디';

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

comment on table TB_VIEW is '뷰';
comment on column TB_VIEW.VIEW_SN is '뷰 일련번호';
comment on column TB_VIEW.PRJ_SN is '프로젝트 일련번호';
comment on column TB_VIEW.SCHM_SN is '스키마 일련번호';
comment on column TB_VIEW.VIEW_NM is '뷰 명';
comment on column TB_VIEW.VIEW_KORN_NM is '뷰 한글 명';
comment on column TB_VIEW.VIEW_EXPLN is '뷰 설명';
comment on column TB_VIEW.VIEW_DFN_CN is '뷰 정의 내용';
comment on column TB_VIEW.APRV_YN is '승인 여부';
comment on column TB_VIEW.REG_DT is '등록 일시';
comment on column TB_VIEW.MDFCN_DT is '수정 일시';
comment on column TB_VIEW.RGTR_ID is '등록자 아이디';
comment on column TB_VIEW.MDFR_ID is '수정자 아이디';
comment on table TB_VIEW_COL is '뷰 컬럼';
comment on column TB_VIEW_COL.VIEW_COL_SN is '뷰 컬럼 일련번호';
comment on column TB_VIEW_COL.VIEW_SN is '뷰 일련번호';
comment on column TB_VIEW_COL.COL_NM is '컬럼 명';
comment on column TB_VIEW_COL.COL_KORN_NM is '컬럼 한글 명';
comment on column TB_VIEW_COL.DATA_TYPE_NM is '자료 유형 명';
comment on column TB_VIEW_COL.COL_ORD is '컬럼 순서';
comment on column TB_VIEW_COL.COL_EXPLN is '컬럼 설명';
comment on table TB_VIEW_RFRNC is '뷰 참조';
comment on column TB_VIEW_RFRNC.VIEW_RFRNC_SN is '뷰 참조 일련번호';
comment on column TB_VIEW_RFRNC.VIEW_SN is '뷰 일련번호';
comment on column TB_VIEW_RFRNC.RFRNC_TYPE_CD is '참조 유형 코드';
comment on column TB_VIEW_RFRNC.RFRNC_TBL_SN is '참조 테이블 일련번호';
comment on column TB_VIEW_RFRNC.RFRNC_VIEW_SN is '참조 뷰 일련번호';
comment on column TB_VIEW_RFRNC.RFRNC_SCHM_NM is '참조 스키마 명';
comment on column TB_VIEW_RFRNC.RFRNC_TRGT_NM is '참조 대상 명';
comment on table TB_VIEW_CHG_DMND is '뷰 변경 요청';
comment on column TB_VIEW_CHG_DMND.VIEW_CHG_DMND_SN is '뷰 변경 요청 일련번호';
comment on column TB_VIEW_CHG_DMND.PRJ_SN is '프로젝트 일련번호';
comment on column TB_VIEW_CHG_DMND.VIEW_SN is '뷰 일련번호';
comment on column TB_VIEW_CHG_DMND.CHG_BFR_CN is '변경 이전 내용';
comment on column TB_VIEW_CHG_DMND.CHG_AFTR_CN is '변경 이후 내용';
comment on column TB_VIEW_CHG_DMND.DMND_STTS_CD is '요청 상태 코드';
comment on column TB_VIEW_CHG_DMND.REG_DT is '등록 일시';
comment on column TB_VIEW_CHG_DMND.MDFCN_DT is '수정 일시';
comment on column TB_VIEW_CHG_DMND.RGTR_ID is '등록자 아이디';
comment on column TB_VIEW_CHG_DMND.MDFR_ID is '수정자 아이디';

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
comment on table TB_MDL_CHG_HSTRY is '모델 변경 이력';
comment on column TB_MDL_CHG_HSTRY.MDL_CHG_HSTRY_SN is '모델 변경 이력 일련번호';
comment on column TB_MDL_CHG_HSTRY.PRJ_SN is '프로젝트 일련번호';
comment on column TB_MDL_CHG_HSTRY.CHG_GROUP_ID is '변경 그룹 아이디';
comment on column TB_MDL_CHG_HSTRY.TRGT_TYPE_CD is '대상 유형 코드';
comment on column TB_MDL_CHG_HSTRY.TRGT_SN is '대상 일련번호';
comment on column TB_MDL_CHG_HSTRY.TRGT_NM is '대상 명';
comment on column TB_MDL_CHG_HSTRY.CHG_TYPE_CD is '변경 유형 코드';
comment on column TB_MDL_CHG_HSTRY.CHG_BFR_CN is '변경 이전 내용';
comment on column TB_MDL_CHG_HSTRY.CHG_AFTR_CN is '변경 이후 내용';
comment on column TB_MDL_CHG_HSTRY.CHG_SRC_CD is '변경 출처 코드';
comment on column TB_MDL_CHG_HSTRY.USER_ID is '사용자 아이디';
comment on column TB_MDL_CHG_HSTRY.CHG_DT is '변경 일시';
comment on table TB_MDL_VER is '모델 버전';
comment on column TB_MDL_VER.MDL_VER_SN is '모델 버전 일련번호';
comment on column TB_MDL_VER.PRJ_SN is '프로젝트 일련번호';
comment on column TB_MDL_VER.VER_NM is '버전 명';
comment on column TB_MDL_VER.VER_EXPLN is '버전 설명';
comment on column TB_MDL_VER.FRMT_VER_NO is '형식 버전 번호';
comment on column TB_MDL_VER.MDL_CNT_CN is '모델 수 내용';
comment on column TB_MDL_VER.MDL_CN is '모델 내용';
comment on column TB_MDL_VER.RGTR_ID is '등록자 아이디';
comment on column TB_MDL_VER.REG_DT is '등록 일시';

-- =====================================================================
-- 원본 메타데이터 카탈로그 (AI-1b, lexorma-source) : 프로젝트별 외부 DB 연결(출처 접속)과 수집한 원본 구조
-- 물리 모델(TB_SCHM·TB_TBL·TB_COL …)과 따로 둔다 : 원본은 외부 DB 에서 수집한 사실이고, 물리 모델은 사용자가 설계하는 데이터다.
-- 이름의 SRC(출처)는 원본 DB 쪽을 뜻한다. ORGNL(원본)은 관계의 "원본 테이블" 로 이미 쓰고 있어 겹치지 않게 했다.
-- 같은 접속의 객체끼리만 이어지도록 스키마·테이블·관계는 접속 일련번호를 함께 묶어 참조한다 (FK_SRC_TBL_01, FK_SRC_REL_01·02).
-- =====================================================================

create sequence SQ_SRC_CNTN start with 1 increment by 50;
create sequence SQ_SRC_SCHM start with 1 increment by 50;
create sequence SQ_SRC_TBL start with 1 increment by 50;
create sequence SQ_SRC_COL start with 1 increment by 50;
create sequence SQ_SRC_CNSTRT start with 1 increment by 50;
create sequence SQ_SRC_CNSTRT_COL start with 1 increment by 50;
create sequence SQ_SRC_IDX start with 1 increment by 50;
create sequence SQ_SRC_IDX_COL start with 1 increment by 50;
create sequence SQ_SRC_REL start with 1 increment by 50;
create sequence SQ_SRC_REL_COL start with 1 increment by 50;

-- 출처 접속
create table TB_SRC_CNTN (
    SRC_CNTN_SN bigint not null,
    PRJ_SN bigint not null,
    CNTN_NM varchar(100) not null,
    DB_TYPE_CD varchar(20) not null,
    HOST_NM varchar(255) not null,
    PORT_NO integer not null,
    DB_NM varchar(255) not null,
    DB_NM_TYPE_CD varchar(20) not null,
    CNTN_USER_NM varchar(255) not null,
    CNTN_PSWD_CN TEXT,
    CNTN_OPTN_CN TEXT,
    IDNTFR_CMPR_CD varchar(20) not null,
    CNTN_EXPLN varchar(1000),
    ACTVTN_YN boolean not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_CNTN primary key (SRC_CNTN_SN)
);

-- 출처 스키마
create table TB_SRC_SCHM (
    SRC_SCHM_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    CTLG_NM varchar(255),
    SCHM_NM varchar(255),
    PATH_CN varchar(1000) not null,
    EXST_YN boolean not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    CRT_CYCL integer not null,
    constraint PK_SRC_SCHM primary key (SRC_SCHM_SN)
);

-- 출처 테이블
create table TB_SRC_TBL (
    SRC_TBL_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_SCHM_SN bigint not null,
    TBL_NM varchar(255) not null,
    CMPR_NM varchar(255) not null,
    QUOT_YN boolean,
    TBL_TYPE_CD varchar(20) not null,
    ANTN_CN TEXT,
    HASH_VL varchar(255),
    EXST_YN boolean not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    CRT_CYCL integer not null,
    constraint PK_SRC_TBL primary key (SRC_TBL_SN)
);

-- 출처 컬럼
create table TB_SRC_COL (
    SRC_COL_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_TBL_SN bigint not null,
    COL_NM varchar(255) not null,
    CMPR_NM varchar(255) not null,
    QUOT_YN boolean,
    COL_ORD integer not null,
    DATA_TYPE_NM varchar(255),
    MAX_LEN integer,
    DATA_PRCSN numeric(38,2),
    DCPT_LEN integer,
    NULL_ALWNC_YN boolean,
    BSC_VL_CN TEXT,
    ANTN_CN TEXT,
    HASH_VL varchar(255),
    EXST_YN boolean not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_COL primary key (SRC_COL_SN)
);

-- 출처 제약조건
create table TB_SRC_CNSTRT (
    SRC_CNSTRT_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_TBL_SN bigint not null,
    CNSTRT_NM varchar(255),
    CNSTRT_TYPE_CD varchar(20) not null,
    INSP_CND_CN TEXT,
    constraint PK_SRC_CNSTRT primary key (SRC_CNSTRT_SN)
);

-- 출처 제약조건 컬럼
create table TB_SRC_CNSTRT_COL (
    SRC_CNSTRT_COL_SN bigint not null,
    SRC_CNSTRT_SN bigint not null,
    SRC_COL_SN bigint not null,
    COL_ORD integer not null,
    constraint PK_SRC_CNSTRT_COL primary key (SRC_CNSTRT_COL_SN)
);

-- 출처 인덱스
create table TB_SRC_IDX (
    SRC_IDX_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_TBL_SN bigint not null,
    IDX_NM varchar(255) not null,
    IDX_TYPE_NM varchar(255),
    UNQ_YN boolean not null,
    -- 부분 인덱스 조건 (AI-2b)
    CND_CN TEXT,
    constraint PK_SRC_IDX primary key (SRC_IDX_SN)
);

-- 출처 인덱스 컬럼 : 컬럼 또는 표현식(EXPR_CN, 출처 컬럼 없음) 하나 (AI-2b)
create table TB_SRC_IDX_COL (
    SRC_IDX_COL_SN bigint not null,
    SRC_IDX_SN bigint not null,
    SRC_COL_SN bigint,
    COL_ORD integer not null,
    EXPR_CN TEXT,
    SORT_CD varchar(20),
    constraint PK_SRC_IDX_COL primary key (SRC_IDX_COL_SN)
);

-- 출처 관계
create table TB_SRC_REL (
    SRC_REL_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_TBL_SN bigint not null,
    RFRNC_SRC_TBL_SN bigint,
    RFRNC_SCHM_NM varchar(255),
    RFRNC_TBL_NM varchar(255) not null,
    REL_NM varchar(255),
    DEL_RULE_CD varchar(20),
    MDFCN_RULE_CD varchar(20),
    RFRNC_CTLG_NM varchar(255),
    constraint PK_SRC_REL primary key (SRC_REL_SN)
);

-- 출처 관계 컬럼
create table TB_SRC_REL_COL (
    SRC_REL_COL_SN bigint not null,
    SRC_REL_SN bigint not null,
    SRC_COL_SN bigint not null,
    RFRNC_SRC_COL_SN bigint,
    RFRNC_COL_NM varchar(255) not null,
    COL_ORD integer not null,
    constraint PK_SRC_REL_COL primary key (SRC_REL_COL_SN)
);

create unique index UX_SRC_CNTN_01 on TB_SRC_CNTN (PRJ_SN, CNTN_NM);
create unique index UX_SRC_SCHM_01 on TB_SRC_SCHM (SRC_CNTN_SN, PATH_CN);
create index IX_SRC_SCHM_01 on TB_SRC_SCHM (PRJ_SN);
create unique index UX_SRC_TBL_01 on TB_SRC_TBL (SRC_SCHM_SN, CMPR_NM);
create index IX_SRC_TBL_01 on TB_SRC_TBL (PRJ_SN);
create index IX_SRC_TBL_02 on TB_SRC_TBL (SRC_CNTN_SN);
create unique index UX_SRC_COL_01 on TB_SRC_COL (SRC_TBL_SN, CMPR_NM);
create index IX_SRC_COL_01 on TB_SRC_COL (PRJ_SN);
create index IX_SRC_CNSTRT_01 on TB_SRC_CNSTRT (SRC_TBL_SN);
create index IX_SRC_CNSTRT_02 on TB_SRC_CNSTRT (PRJ_SN);
create unique index UX_SRC_CNSTRT_COL_01 on TB_SRC_CNSTRT_COL (SRC_CNSTRT_SN, COL_ORD);
create index IX_SRC_CNSTRT_COL_01 on TB_SRC_CNSTRT_COL (SRC_COL_SN);
create index IX_SRC_IDX_01 on TB_SRC_IDX (SRC_TBL_SN);
create index IX_SRC_IDX_02 on TB_SRC_IDX (PRJ_SN);
create unique index UX_SRC_IDX_COL_01 on TB_SRC_IDX_COL (SRC_IDX_SN, COL_ORD);
create index IX_SRC_IDX_COL_01 on TB_SRC_IDX_COL (SRC_COL_SN);
create index IX_SRC_REL_01 on TB_SRC_REL (SRC_TBL_SN);
create index IX_SRC_REL_02 on TB_SRC_REL (RFRNC_SRC_TBL_SN);
create index IX_SRC_REL_03 on TB_SRC_REL (PRJ_SN);
create unique index UX_SRC_REL_COL_01 on TB_SRC_REL_COL (SRC_REL_SN, COL_ORD);
create index IX_SRC_REL_COL_01 on TB_SRC_REL_COL (SRC_COL_SN);
create index IX_SRC_REL_COL_02 on TB_SRC_REL_COL (RFRNC_SRC_COL_SN);

-- 같은 접속의 객체만 참조하게 하는 복합 FK 의 대상 (H2 는 UNIQUE 인덱스가 아니라 UNIQUE 제약을 요구한다)
alter table TB_SRC_SCHM add constraint UX_SRC_SCHM_02 unique (SRC_SCHM_SN, SRC_CNTN_SN);
alter table TB_SRC_TBL add constraint UX_SRC_TBL_02 unique (SRC_TBL_SN, SRC_CNTN_SN);
alter table TB_SRC_SCHM add constraint FK_SRC_SCHM_01 foreign key (SRC_CNTN_SN) references TB_SRC_CNTN;
alter table TB_SRC_TBL add constraint FK_SRC_TBL_01 foreign key (SRC_SCHM_SN, SRC_CNTN_SN) references TB_SRC_SCHM (SRC_SCHM_SN, SRC_CNTN_SN);
alter table TB_SRC_COL add constraint FK_SRC_COL_01 foreign key (SRC_TBL_SN) references TB_SRC_TBL;
alter table TB_SRC_CNSTRT add constraint FK_SRC_CNSTRT_01 foreign key (SRC_TBL_SN) references TB_SRC_TBL;
alter table TB_SRC_CNSTRT_COL add constraint FK_SRC_CNSTRT_COL_01 foreign key (SRC_CNSTRT_SN) references TB_SRC_CNSTRT;
alter table TB_SRC_CNSTRT_COL add constraint FK_SRC_CNSTRT_COL_02 foreign key (SRC_COL_SN) references TB_SRC_COL;
alter table TB_SRC_IDX add constraint FK_SRC_IDX_01 foreign key (SRC_TBL_SN) references TB_SRC_TBL;
alter table TB_SRC_IDX_COL add constraint FK_SRC_IDX_COL_01 foreign key (SRC_IDX_SN) references TB_SRC_IDX;
alter table TB_SRC_IDX_COL add constraint FK_SRC_IDX_COL_02 foreign key (SRC_COL_SN) references TB_SRC_COL;
alter table TB_SRC_REL add constraint FK_SRC_REL_01 foreign key (SRC_TBL_SN, SRC_CNTN_SN) references TB_SRC_TBL (SRC_TBL_SN, SRC_CNTN_SN);
alter table TB_SRC_REL add constraint FK_SRC_REL_02 foreign key (RFRNC_SRC_TBL_SN, SRC_CNTN_SN) references TB_SRC_TBL (SRC_TBL_SN, SRC_CNTN_SN);
alter table TB_SRC_REL_COL add constraint FK_SRC_REL_COL_01 foreign key (SRC_REL_SN) references TB_SRC_REL;
alter table TB_SRC_REL_COL add constraint FK_SRC_REL_COL_02 foreign key (SRC_COL_SN) references TB_SRC_COL;
alter table TB_SRC_REL_COL add constraint FK_SRC_REL_COL_03 foreign key (RFRNC_SRC_COL_SN) references TB_SRC_COL;

comment on table TB_SRC_CNTN is '출처 접속';
comment on column TB_SRC_CNTN.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_CNTN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_CNTN.CNTN_NM is '접속 명';
comment on column TB_SRC_CNTN.DB_TYPE_CD is 'DB 유형 코드';
comment on column TB_SRC_CNTN.HOST_NM is '호스트 명';
comment on column TB_SRC_CNTN.PORT_NO is '포트 번호';
comment on column TB_SRC_CNTN.DB_NM is 'DB 명';
comment on column TB_SRC_CNTN.DB_NM_TYPE_CD is 'DB 명 유형 코드';
comment on column TB_SRC_CNTN.CNTN_USER_NM is '접속 사용자 명';
comment on column TB_SRC_CNTN.CNTN_PSWD_CN is '접속 비밀번호 내용';
comment on column TB_SRC_CNTN.CNTN_OPTN_CN is '접속 옵션 내용';
comment on column TB_SRC_CNTN.IDNTFR_CMPR_CD is '식별자 비교 코드';
comment on column TB_SRC_CNTN.CNTN_EXPLN is '접속 설명';
comment on column TB_SRC_CNTN.ACTVTN_YN is '활성 여부';
comment on column TB_SRC_CNTN.REG_DT is '등록 일시';
comment on column TB_SRC_CNTN.MDFCN_DT is '수정 일시';
comment on column TB_SRC_CNTN.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_CNTN.MDFR_ID is '수정자 아이디';
comment on table TB_SRC_SCHM is '출처 스키마';
comment on column TB_SRC_SCHM.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_SCHM.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_SCHM.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_SCHM.CTLG_NM is '카탈로그 명';
comment on column TB_SRC_SCHM.SCHM_NM is '스키마 명';
comment on column TB_SRC_SCHM.PATH_CN is '경로 내용';
comment on column TB_SRC_SCHM.EXST_YN is '존재 여부';
comment on column TB_SRC_SCHM.REG_DT is '등록 일시';
comment on column TB_SRC_SCHM.MDFCN_DT is '수정 일시';
comment on column TB_SRC_SCHM.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_SCHM.MDFR_ID is '수정자 아이디';
comment on column TB_SRC_SCHM.CRT_CYCL is '생성 차수';
comment on table TB_SRC_TBL is '출처 테이블';
comment on column TB_SRC_TBL.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_TBL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_TBL.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_TBL.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_TBL.TBL_NM is '테이블 명';
comment on column TB_SRC_TBL.CMPR_NM is '비교 명';
comment on column TB_SRC_TBL.QUOT_YN is '따옴표 여부';
comment on column TB_SRC_TBL.TBL_TYPE_CD is '테이블 유형 코드';
comment on column TB_SRC_TBL.ANTN_CN is '주석 내용';
comment on column TB_SRC_TBL.HASH_VL is '해시 값';
comment on column TB_SRC_TBL.EXST_YN is '존재 여부';
comment on column TB_SRC_TBL.REG_DT is '등록 일시';
comment on column TB_SRC_TBL.MDFCN_DT is '수정 일시';
comment on column TB_SRC_TBL.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_TBL.MDFR_ID is '수정자 아이디';
comment on column TB_SRC_TBL.CRT_CYCL is '생성 차수';
comment on table TB_SRC_COL is '출처 컬럼';
comment on column TB_SRC_COL.SRC_COL_SN is '출처 컬럼 일련번호';
comment on column TB_SRC_COL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_COL.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_COL.COL_NM is '컬럼 명';
comment on column TB_SRC_COL.CMPR_NM is '비교 명';
comment on column TB_SRC_COL.QUOT_YN is '따옴표 여부';
comment on column TB_SRC_COL.COL_ORD is '컬럼 순서';
comment on column TB_SRC_COL.DATA_TYPE_NM is '자료 유형 명';
comment on column TB_SRC_COL.MAX_LEN is '최대 길이';
comment on column TB_SRC_COL.DATA_PRCSN is '자료 정밀도';
comment on column TB_SRC_COL.DCPT_LEN is '소수점 길이';
comment on column TB_SRC_COL.NULL_ALWNC_YN is 'NULL 허용 여부';
comment on column TB_SRC_COL.BSC_VL_CN is '기본 값 내용';
comment on column TB_SRC_COL.ANTN_CN is '주석 내용';
comment on column TB_SRC_COL.HASH_VL is '해시 값';
comment on column TB_SRC_COL.EXST_YN is '존재 여부';
comment on column TB_SRC_COL.REG_DT is '등록 일시';
comment on column TB_SRC_COL.MDFCN_DT is '수정 일시';
comment on column TB_SRC_COL.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_COL.MDFR_ID is '수정자 아이디';
comment on table TB_SRC_CNSTRT is '출처 제약조건';
comment on column TB_SRC_CNSTRT.SRC_CNSTRT_SN is '출처 제약조건 일련번호';
comment on column TB_SRC_CNSTRT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_CNSTRT.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_CNSTRT.CNSTRT_NM is '제약조건 명';
comment on column TB_SRC_CNSTRT.CNSTRT_TYPE_CD is '제약조건 유형 코드';
comment on column TB_SRC_CNSTRT.INSP_CND_CN is '검사 조건 내용';
comment on table TB_SRC_CNSTRT_COL is '출처 제약조건 컬럼';
comment on column TB_SRC_CNSTRT_COL.SRC_CNSTRT_COL_SN is '출처 제약조건 컬럼 일련번호';
comment on column TB_SRC_CNSTRT_COL.SRC_CNSTRT_SN is '출처 제약조건 일련번호';
comment on column TB_SRC_CNSTRT_COL.SRC_COL_SN is '출처 컬럼 일련번호';
comment on column TB_SRC_CNSTRT_COL.COL_ORD is '컬럼 순서';
comment on table TB_SRC_IDX is '출처 인덱스';
comment on column TB_SRC_IDX.SRC_IDX_SN is '출처 인덱스 일련번호';
comment on column TB_SRC_IDX.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_IDX.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_IDX.IDX_NM is '인덱스 명';
comment on column TB_SRC_IDX.IDX_TYPE_NM is '인덱스 유형 명';
comment on column TB_SRC_IDX.UNQ_YN is '고유 여부';
comment on column TB_SRC_IDX.CND_CN is '조건 내용';
comment on table TB_SRC_IDX_COL is '출처 인덱스 컬럼';
comment on column TB_SRC_IDX_COL.SRC_IDX_COL_SN is '출처 인덱스 컬럼 일련번호';
comment on column TB_SRC_IDX_COL.SRC_IDX_SN is '출처 인덱스 일련번호';
comment on column TB_SRC_IDX_COL.SRC_COL_SN is '출처 컬럼 일련번호';
comment on column TB_SRC_IDX_COL.COL_ORD is '컬럼 순서';
comment on column TB_SRC_IDX_COL.EXPR_CN is '표현식 내용';
comment on column TB_SRC_IDX_COL.SORT_CD is '정렬 코드';
comment on table TB_SRC_REL is '출처 관계';
comment on column TB_SRC_REL.SRC_REL_SN is '출처 관계 일련번호';
comment on column TB_SRC_REL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_REL.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_REL.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_REL.RFRNC_SRC_TBL_SN is '참조 출처 테이블 일련번호';
comment on column TB_SRC_REL.RFRNC_SCHM_NM is '참조 스키마 명';
comment on column TB_SRC_REL.RFRNC_TBL_NM is '참조 테이블 명';
comment on column TB_SRC_REL.REL_NM is '관계 명';
comment on column TB_SRC_REL.DEL_RULE_CD is '삭제 규칙 코드';
comment on column TB_SRC_REL.MDFCN_RULE_CD is '수정 규칙 코드';
comment on column TB_SRC_REL.RFRNC_CTLG_NM is '참조 카탈로그 명';
comment on table TB_SRC_REL_COL is '출처 관계 컬럼';
comment on column TB_SRC_REL_COL.SRC_REL_COL_SN is '출처 관계 컬럼 일련번호';
comment on column TB_SRC_REL_COL.SRC_REL_SN is '출처 관계 일련번호';
comment on column TB_SRC_REL_COL.SRC_COL_SN is '출처 컬럼 일련번호';
comment on column TB_SRC_REL_COL.RFRNC_SRC_COL_SN is '참조 출처 컬럼 일련번호';
comment on column TB_SRC_REL_COL.RFRNC_COL_NM is '참조 컬럼 명';
comment on column TB_SRC_REL_COL.COL_ORD is '컬럼 순서';

-- =====================================================================
-- 원본 카탈로그 수집 실행·스냅샷·변경 이력 (AI-1c, lexorma-source)
-- 현재 상태 = 위의 원본 카탈로그 9개 테이블. 과거 상태 = 바뀐 스키마·테이블만 새 버전을 쌓는 버전 테이블 (테이블 단위 Copy-on-Write).
-- 시점의 순서는 접속별 반영 순서(RFLT_ORD)다 : 일련번호(시퀀스가 50개씩 나눠 줘 인스턴스가 여럿이면 시간 순서가 아니다)나 등록 일시가 아니다.
-- 버전은 비교 경로·비교 명과 정규화한 JSON 을 함께 저장해 현재 행 없이도 과거를 재구성한다. 만든 뒤 고치지 않는다.
-- =====================================================================

create sequence SQ_SRC_CLCT_EXCN start with 1 increment by 50;
create sequence SQ_SRC_CLCT_TRGT start with 1 increment by 50;
create sequence SQ_SRC_SCHM_VER start with 1 increment by 50;
create sequence SQ_SRC_TBL_VER start with 1 increment by 50;
create sequence SQ_SRC_CHG_HSTRY start with 1 increment by 50;

-- 출처 수집 실행 : 수집 한 번. 진행 중(CREATED·RUNNING)인 동안만 진행 접속 일련번호에 접속 일련번호가 들어가 같은 접속의 수집이 둘이 될 수 없다 (UNIQUE, NULL 은 여럿)
create table TB_SRC_CLCT_EXCN (
    SRC_CLCT_EXCN_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    JOB_ID varchar(255) not null,
    EXCN_TYPE_CD varchar(20) not null,
    ACNT_SN bigint,
    CLCT_TYPE_CD varchar(20) not null,
    EXCN_STTS_CD varchar(20) not null,
    RFLT_ORD integer,
    PRGRS_CNTN_SN bigint,
    EXCN_SRVR_ID varchar(255) not null,
    BGNG_DT timestamp(6) not null,
    LAST_IDNTY_DT timestamp(6) not null,
    END_DT timestamp(6),
    RFLT_DT timestamp(6),
    TBL_CNT integer,
    COL_CNT integer,
    VER_CNT integer,
    CHG_CNT integer,
    ERR_CD varchar(100),
    ERR_CN TEXT,
    constraint PK_SRC_CLCT_EXCN primary key (SRC_CLCT_EXCN_SN)
);

-- 출처 수집 대상 : 실행이 대상으로 한 이름 공간과 그 결과. 삭제 판정은 결과가 COMPLETED 인 대상 안에서만 한다
create table TB_SRC_CLCT_TRGT (
    SRC_CLCT_TRGT_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CLCT_EXCN_SN bigint not null,
    PATH_CN varchar(1000) not null,
    CTLG_NM varchar(255),
    SCHM_NM varchar(255),
    SRC_SCHM_SN bigint,
    CLCT_RSLT_CD varchar(20) not null,
    TBL_CNT integer,
    COL_CNT integer,
    ERR_CD varchar(100),
    constraint PK_SRC_CLCT_TRGT primary key (SRC_CLCT_TRGT_SN)
);

-- 출처 스키마 버전 : 스키마가 처음 수집되거나(BASE) 생기거나 바뀌거나 사라질(REMOVED, 삭제 표시) 때만 1행
create table TB_SRC_SCHM_VER (
    SRC_SCHM_VER_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_SCHM_SN bigint not null,
    SRC_CLCT_EXCN_SN bigint not null,
    RFLT_ORD integer not null,
    PATH_CN varchar(1000) not null,
    CRT_CYCL integer not null,
    CHG_TYPE_CD varchar(20) not null,
    HASH_VL varchar(255) not null,
    FRMT_VER_NO integer not null,
    SCHM_CN TEXT not null,
    constraint PK_SRC_SCHM_VER primary key (SRC_SCHM_VER_SN)
);

-- 출처 테이블 버전 : 테이블(컬럼·제약조건·인덱스·관계 포함)이 처음 수집되거나 생기거나 바뀌거나 사라질 때만 1행
create table TB_SRC_TBL_VER (
    SRC_TBL_VER_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_SCHM_SN bigint not null,
    SRC_TBL_SN bigint not null,
    SRC_CLCT_EXCN_SN bigint not null,
    RFLT_ORD integer not null,
    PATH_CN varchar(1000) not null,
    CMPR_NM varchar(255) not null,
    CRT_CYCL integer not null,
    CHG_TYPE_CD varchar(20) not null,
    HASH_VL varchar(255) not null,
    FRMT_VER_NO integer not null,
    TBL_CN TEXT not null,
    constraint PK_SRC_TBL_VER primary key (SRC_TBL_VER_SN)
);

-- 출처 변경 이력 : 객체별 추가·수정·삭제와 바뀐 항목, 변경 전후 JSON. 최초 수집(BASE)은 남기지 않는다
create table TB_SRC_CHG_HSTRY (
    SRC_CHG_HSTRY_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_CLCT_EXCN_SN bigint not null,
    RFLT_ORD integer not null,
    SRC_SCHM_SN bigint,
    SRC_TBL_SN bigint,
    TRGT_TYPE_CD varchar(20) not null,
    TRGT_IDNTFR_CN varchar(2000) not null,
    TRGT_NM varchar(255),
    CRT_CYCL integer not null,
    CHG_TYPE_CD varchar(20) not null,
    CHG_ARTCL_CN TEXT,
    CHG_BFR_CN TEXT,
    CHG_AFTR_CN TEXT,
    constraint PK_SRC_CHG_HSTRY primary key (SRC_CHG_HSTRY_SN)
);

create unique index UX_SRC_CLCT_EXCN_01 on TB_SRC_CLCT_EXCN (JOB_ID);
create unique index UX_SRC_CLCT_EXCN_02 on TB_SRC_CLCT_EXCN (SRC_CNTN_SN, RFLT_ORD);
create unique index UX_SRC_CLCT_EXCN_03 on TB_SRC_CLCT_EXCN (PRGRS_CNTN_SN);
create index IX_SRC_CLCT_EXCN_01 on TB_SRC_CLCT_EXCN (PRJ_SN);
create index IX_SRC_CLCT_EXCN_02 on TB_SRC_CLCT_EXCN (SRC_CNTN_SN, BGNG_DT);
create unique index UX_SRC_CLCT_TRGT_01 on TB_SRC_CLCT_TRGT (SRC_CLCT_EXCN_SN, PATH_CN);
create index IX_SRC_CLCT_TRGT_01 on TB_SRC_CLCT_TRGT (PRJ_SN);
create index IX_SRC_CLCT_TRGT_02 on TB_SRC_CLCT_TRGT (SRC_SCHM_SN);
create unique index UX_SRC_SCHM_VER_01 on TB_SRC_SCHM_VER (SRC_SCHM_SN, RFLT_ORD);
create index IX_SRC_SCHM_VER_01 on TB_SRC_SCHM_VER (SRC_CNTN_SN, RFLT_ORD);
create index IX_SRC_SCHM_VER_02 on TB_SRC_SCHM_VER (PRJ_SN);
create index IX_SRC_SCHM_VER_03 on TB_SRC_SCHM_VER (SRC_CLCT_EXCN_SN);
create unique index UX_SRC_TBL_VER_01 on TB_SRC_TBL_VER (SRC_TBL_SN, RFLT_ORD);
create index IX_SRC_TBL_VER_01 on TB_SRC_TBL_VER (SRC_CNTN_SN, RFLT_ORD);
create index IX_SRC_TBL_VER_02 on TB_SRC_TBL_VER (SRC_SCHM_SN, RFLT_ORD);
create index IX_SRC_TBL_VER_03 on TB_SRC_TBL_VER (PRJ_SN);
create index IX_SRC_TBL_VER_04 on TB_SRC_TBL_VER (SRC_CLCT_EXCN_SN);
create index IX_SRC_CHG_HSTRY_01 on TB_SRC_CHG_HSTRY (SRC_CLCT_EXCN_SN);
create index IX_SRC_CHG_HSTRY_02 on TB_SRC_CHG_HSTRY (SRC_TBL_SN, RFLT_ORD);
create index IX_SRC_CHG_HSTRY_03 on TB_SRC_CHG_HSTRY (SRC_CNTN_SN, RFLT_ORD);
create index IX_SRC_CHG_HSTRY_04 on TB_SRC_CHG_HSTRY (PRJ_SN);

-- 실행·버전·이력은 같은 접속의 것끼리만 이어진다 (접속 일련번호를 함께 묶은 FK)
alter table TB_SRC_CLCT_EXCN add constraint UX_SRC_CLCT_EXCN_04 unique (SRC_CLCT_EXCN_SN, SRC_CNTN_SN);
alter table TB_SRC_CLCT_EXCN add constraint FK_SRC_CLCT_EXCN_01 foreign key (SRC_CNTN_SN) references TB_SRC_CNTN;
alter table TB_SRC_CLCT_TRGT add constraint FK_SRC_CLCT_TRGT_01 foreign key (SRC_CLCT_EXCN_SN) references TB_SRC_CLCT_EXCN;
alter table TB_SRC_CLCT_TRGT add constraint FK_SRC_CLCT_TRGT_02 foreign key (SRC_SCHM_SN) references TB_SRC_SCHM;
alter table TB_SRC_SCHM_VER add constraint FK_SRC_SCHM_VER_01 foreign key (SRC_SCHM_SN, SRC_CNTN_SN) references TB_SRC_SCHM (SRC_SCHM_SN, SRC_CNTN_SN);
alter table TB_SRC_SCHM_VER add constraint FK_SRC_SCHM_VER_02 foreign key (SRC_CLCT_EXCN_SN, SRC_CNTN_SN) references TB_SRC_CLCT_EXCN (SRC_CLCT_EXCN_SN, SRC_CNTN_SN);
alter table TB_SRC_TBL_VER add constraint FK_SRC_TBL_VER_01 foreign key (SRC_TBL_SN, SRC_CNTN_SN) references TB_SRC_TBL (SRC_TBL_SN, SRC_CNTN_SN);
alter table TB_SRC_TBL_VER add constraint FK_SRC_TBL_VER_02 foreign key (SRC_CLCT_EXCN_SN, SRC_CNTN_SN) references TB_SRC_CLCT_EXCN (SRC_CLCT_EXCN_SN, SRC_CNTN_SN);
alter table TB_SRC_TBL_VER add constraint FK_SRC_TBL_VER_03 foreign key (SRC_SCHM_SN, SRC_CNTN_SN) references TB_SRC_SCHM (SRC_SCHM_SN, SRC_CNTN_SN);
alter table TB_SRC_CHG_HSTRY add constraint FK_SRC_CHG_HSTRY_01 foreign key (SRC_CLCT_EXCN_SN, SRC_CNTN_SN) references TB_SRC_CLCT_EXCN (SRC_CLCT_EXCN_SN, SRC_CNTN_SN);

comment on table TB_SRC_CLCT_EXCN is '출처 수집 실행';
comment on column TB_SRC_CLCT_EXCN.SRC_CLCT_EXCN_SN is '출처 수집 실행 일련번호';
comment on column TB_SRC_CLCT_EXCN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_CLCT_EXCN.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_CLCT_EXCN.JOB_ID is '작업 아이디';
comment on column TB_SRC_CLCT_EXCN.EXCN_TYPE_CD is '실행 유형 코드';
comment on column TB_SRC_CLCT_EXCN.ACNT_SN is '계정 일련번호';
comment on column TB_SRC_CLCT_EXCN.CLCT_TYPE_CD is '수집 유형 코드';
comment on column TB_SRC_CLCT_EXCN.EXCN_STTS_CD is '실행 상태 코드';
comment on column TB_SRC_CLCT_EXCN.RFLT_ORD is '반영 순서';
comment on column TB_SRC_CLCT_EXCN.PRGRS_CNTN_SN is '진행 접속 일련번호';
comment on column TB_SRC_CLCT_EXCN.EXCN_SRVR_ID is '실행 서버 아이디';
comment on column TB_SRC_CLCT_EXCN.BGNG_DT is '시작 일시';
comment on column TB_SRC_CLCT_EXCN.LAST_IDNTY_DT is '최종 확인 일시';
comment on column TB_SRC_CLCT_EXCN.END_DT is '종료 일시';
comment on column TB_SRC_CLCT_EXCN.RFLT_DT is '반영 일시';
comment on column TB_SRC_CLCT_EXCN.TBL_CNT is '테이블 수';
comment on column TB_SRC_CLCT_EXCN.COL_CNT is '컬럼 수';
comment on column TB_SRC_CLCT_EXCN.VER_CNT is '버전 수';
comment on column TB_SRC_CLCT_EXCN.CHG_CNT is '변경 수';
comment on column TB_SRC_CLCT_EXCN.ERR_CD is '오류 코드';
comment on column TB_SRC_CLCT_EXCN.ERR_CN is '오류 내용';
comment on table TB_SRC_CLCT_TRGT is '출처 수집 대상';
comment on column TB_SRC_CLCT_TRGT.SRC_CLCT_TRGT_SN is '출처 수집 대상 일련번호';
comment on column TB_SRC_CLCT_TRGT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_CLCT_TRGT.SRC_CLCT_EXCN_SN is '출처 수집 실행 일련번호';
comment on column TB_SRC_CLCT_TRGT.PATH_CN is '경로 내용';
comment on column TB_SRC_CLCT_TRGT.CTLG_NM is '카탈로그 명';
comment on column TB_SRC_CLCT_TRGT.SCHM_NM is '스키마 명';
comment on column TB_SRC_CLCT_TRGT.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_CLCT_TRGT.CLCT_RSLT_CD is '수집 결과 코드';
comment on column TB_SRC_CLCT_TRGT.TBL_CNT is '테이블 수';
comment on column TB_SRC_CLCT_TRGT.COL_CNT is '컬럼 수';
comment on column TB_SRC_CLCT_TRGT.ERR_CD is '오류 코드';
comment on table TB_SRC_SCHM_VER is '출처 스키마 버전';
comment on column TB_SRC_SCHM_VER.SRC_SCHM_VER_SN is '출처 스키마 버전 일련번호';
comment on column TB_SRC_SCHM_VER.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_SCHM_VER.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_SCHM_VER.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_SCHM_VER.SRC_CLCT_EXCN_SN is '출처 수집 실행 일련번호';
comment on column TB_SRC_SCHM_VER.RFLT_ORD is '반영 순서';
comment on column TB_SRC_SCHM_VER.PATH_CN is '경로 내용';
comment on column TB_SRC_SCHM_VER.CRT_CYCL is '생성 차수';
comment on column TB_SRC_SCHM_VER.CHG_TYPE_CD is '변경 유형 코드';
comment on column TB_SRC_SCHM_VER.HASH_VL is '해시 값';
comment on column TB_SRC_SCHM_VER.FRMT_VER_NO is '형식 버전 번호';
comment on column TB_SRC_SCHM_VER.SCHM_CN is '스키마 내용';
comment on table TB_SRC_TBL_VER is '출처 테이블 버전';
comment on column TB_SRC_TBL_VER.SRC_TBL_VER_SN is '출처 테이블 버전 일련번호';
comment on column TB_SRC_TBL_VER.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_TBL_VER.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_TBL_VER.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_TBL_VER.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_TBL_VER.SRC_CLCT_EXCN_SN is '출처 수집 실행 일련번호';
comment on column TB_SRC_TBL_VER.RFLT_ORD is '반영 순서';
comment on column TB_SRC_TBL_VER.PATH_CN is '경로 내용';
comment on column TB_SRC_TBL_VER.CMPR_NM is '비교 명';
comment on column TB_SRC_TBL_VER.CRT_CYCL is '생성 차수';
comment on column TB_SRC_TBL_VER.CHG_TYPE_CD is '변경 유형 코드';
comment on column TB_SRC_TBL_VER.HASH_VL is '해시 값';
comment on column TB_SRC_TBL_VER.FRMT_VER_NO is '형식 버전 번호';
comment on column TB_SRC_TBL_VER.TBL_CN is '테이블 내용';
comment on table TB_SRC_CHG_HSTRY is '출처 변경 이력';
comment on column TB_SRC_CHG_HSTRY.SRC_CHG_HSTRY_SN is '출처 변경 이력 일련번호';
comment on column TB_SRC_CHG_HSTRY.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_CHG_HSTRY.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_CHG_HSTRY.SRC_CLCT_EXCN_SN is '출처 수집 실행 일련번호';
comment on column TB_SRC_CHG_HSTRY.RFLT_ORD is '반영 순서';
comment on column TB_SRC_CHG_HSTRY.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_CHG_HSTRY.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_CHG_HSTRY.TRGT_TYPE_CD is '대상 유형 코드';
comment on column TB_SRC_CHG_HSTRY.TRGT_IDNTFR_CN is '대상 식별자 내용';
comment on column TB_SRC_CHG_HSTRY.TRGT_NM is '대상 명';
comment on column TB_SRC_CHG_HSTRY.CRT_CYCL is '생성 차수';
comment on column TB_SRC_CHG_HSTRY.CHG_TYPE_CD is '변경 유형 코드';
comment on column TB_SRC_CHG_HSTRY.CHG_ARTCL_CN is '변경 항목 내용';
comment on column TB_SRC_CHG_HSTRY.CHG_BFR_CN is '변경 이전 내용';
comment on column TB_SRC_CHG_HSTRY.CHG_AFTR_CN is '변경 이후 내용';

-- ---------- 예전 V2 : M-4c : 원본(출처) DB 스키마·테이블 매핑 (docs/source-mapping-m4c.md, 정책 docs/m4-foundation.md 5장) ----------

create sequence SQ_PRJ_SRC_CNTN start with 1 increment by 50;
create sequence SQ_SRC_SCHM_MPNG start with 1 increment by 50;
create sequence SQ_SRC_TBL_MPNG start with 1 increment by 50;
-- 프로젝트의 가져오기 기준 원본 연결 (없으면 기준 연결이 없다). 매핑 변경은 이 행을 잠가 프로젝트 안에서 한 번에 하나씩 한다
create table TB_PRJ_SRC_CNTN (
    PRJ_SRC_CNTN_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_PRJ_SRC_CNTN primary key (PRJ_SRC_CNTN_SN)
);
-- 원본 스키마 ↔ 모델 스키마. PATH_CN·SCHM_NM·CRT_CYCL·RFLT_ORD : 확정할 때의 원본 식별 정보와 반영 순서
create table TB_SRC_SCHM_MPNG (
    SRC_SCHM_MPNG_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_SCHM_SN bigint not null,
    PATH_CN varchar(1000) not null,
    SCHM_NM varchar(255) not null,
    CRT_CYCL integer not null,
    RFLT_ORD integer not null,
    SCHM_SN bigint,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_SCHM_MPNG primary key (SRC_SCHM_MPNG_SN)
);
-- 원본 TABLE ↔ 모델 TABLE. CMPR_NM : 확정할 때의 원본 비교 이름 (DBMS 규칙으로 정규화)
create table TB_SRC_TBL_MPNG (
    SRC_TBL_MPNG_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_SCHM_SN bigint not null,
    SRC_TBL_SN bigint not null,
    PATH_CN varchar(1000) not null,
    CMPR_NM varchar(255) not null,
    CRT_CYCL integer not null,
    RFLT_ORD integer not null,
    TBL_SN bigint,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    MPNG_EXCL_YN boolean default false not null,
    constraint PK_SRC_TBL_MPNG primary key (SRC_TBL_MPNG_SN)
);
create unique index UX_PRJ_SRC_CNTN_01 on TB_PRJ_SRC_CNTN (PRJ_SN);
create index IX_PRJ_SRC_CNTN_01 on TB_PRJ_SRC_CNTN (SRC_CNTN_SN);
create unique index UX_SRC_SCHM_MPNG_01 on TB_SRC_SCHM_MPNG (SRC_CNTN_SN, SRC_SCHM_SN);
create unique index UX_SRC_SCHM_MPNG_02 on TB_SRC_SCHM_MPNG (SRC_CNTN_SN, SCHM_SN);
create index IX_SRC_SCHM_MPNG_01 on TB_SRC_SCHM_MPNG (PRJ_SN);
create index IX_SRC_SCHM_MPNG_02 on TB_SRC_SCHM_MPNG (SCHM_SN);
create unique index UX_SRC_TBL_MPNG_01 on TB_SRC_TBL_MPNG (SRC_CNTN_SN, SRC_TBL_SN);
create unique index UX_SRC_TBL_MPNG_02 on TB_SRC_TBL_MPNG (SRC_CNTN_SN, TBL_SN);
create index IX_SRC_TBL_MPNG_01 on TB_SRC_TBL_MPNG (PRJ_SN);
create index IX_SRC_TBL_MPNG_02 on TB_SRC_TBL_MPNG (TBL_SN);
-- 모델 쪽만 FK : 모델 스키마·테이블을 지우면 매핑은 남고 모델 쪽이 비어 "모델 없음" 이 된다
alter table TB_SRC_SCHM_MPNG add constraint FK_SRC_SCHM_MPNG_01 foreign key (SCHM_SN) references TB_SCHM on delete set null;
alter table TB_SRC_TBL_MPNG add constraint FK_SRC_TBL_MPNG_01 foreign key (TBL_SN) references TB_TBL on delete set null;
comment on table TB_PRJ_SRC_CNTN is '프로젝트 출처 접속';
comment on column TB_PRJ_SRC_CNTN.PRJ_SRC_CNTN_SN is '프로젝트 출처 접속 일련번호';
comment on column TB_PRJ_SRC_CNTN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_PRJ_SRC_CNTN.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_PRJ_SRC_CNTN.REG_DT is '등록 일시';
comment on column TB_PRJ_SRC_CNTN.MDFCN_DT is '수정 일시';
comment on column TB_PRJ_SRC_CNTN.RGTR_ID is '등록자 아이디';
comment on column TB_PRJ_SRC_CNTN.MDFR_ID is '수정자 아이디';
comment on table TB_SRC_SCHM_MPNG is '출처 스키마 매핑';
comment on column TB_SRC_SCHM_MPNG.SRC_SCHM_MPNG_SN is '출처 스키마 매핑 일련번호';
comment on column TB_SRC_SCHM_MPNG.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_SCHM_MPNG.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_SCHM_MPNG.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_SCHM_MPNG.PATH_CN is '경로 내용';
comment on column TB_SRC_SCHM_MPNG.SCHM_NM is '스키마 명';
comment on column TB_SRC_SCHM_MPNG.CRT_CYCL is '생성 차수';
comment on column TB_SRC_SCHM_MPNG.RFLT_ORD is '반영 순서';
comment on column TB_SRC_SCHM_MPNG.SCHM_SN is '스키마 일련번호';
comment on column TB_SRC_SCHM_MPNG.REG_DT is '등록 일시';
comment on column TB_SRC_SCHM_MPNG.MDFCN_DT is '수정 일시';
comment on column TB_SRC_SCHM_MPNG.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_SCHM_MPNG.MDFR_ID is '수정자 아이디';
comment on table TB_SRC_TBL_MPNG is '출처 테이블 매핑';
comment on column TB_SRC_TBL_MPNG.SRC_TBL_MPNG_SN is '출처 테이블 매핑 일련번호';
comment on column TB_SRC_TBL_MPNG.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_TBL_MPNG.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_TBL_MPNG.SRC_SCHM_SN is '출처 스키마 일련번호';
comment on column TB_SRC_TBL_MPNG.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_TBL_MPNG.PATH_CN is '경로 내용';
comment on column TB_SRC_TBL_MPNG.CMPR_NM is '비교 명';
comment on column TB_SRC_TBL_MPNG.CRT_CYCL is '생성 차수';
comment on column TB_SRC_TBL_MPNG.RFLT_ORD is '반영 순서';
comment on column TB_SRC_TBL_MPNG.TBL_SN is '테이블 일련번호';
comment on column TB_SRC_TBL_MPNG.REG_DT is '등록 일시';
comment on column TB_SRC_TBL_MPNG.MDFCN_DT is '수정 일시';
comment on column TB_SRC_TBL_MPNG.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_TBL_MPNG.MDFR_ID is '수정자 아이디';

-- ---------- 예전 V3 : M-4e : 원본(출처) 메타데이터 선택 가져오기 (docs/source-import-m4e.md, 정책 docs/m4-foundation.md 5.5·5.10) ----------

create sequence SQ_SRC_IMPRT_EXCN start with 1 increment by 50;
-- 가져오기 실행 (성공한 가져오기만 남는다 : 실패하면 모델 변경과 함께 롤백된다). CHG_GROUP_ID = 요청 식별자 = 모델 변경 이력의 변경 그룹 (같은 요청을 두 번 적용하지 않는다)
create table TB_SRC_IMPRT_EXCN (
    SRC_IMPRT_EXCN_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_CLCT_EXCN_SN bigint,
    RFLT_ORD integer not null,
    CHG_GROUP_ID varchar(255) not null,
    IMPRT_CN TEXT,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_IMPRT_EXCN primary key (SRC_IMPRT_EXCN_SN)
);
create unique index UX_SRC_IMPRT_EXCN_01 on TB_SRC_IMPRT_EXCN (CHG_GROUP_ID);
create index IX_SRC_IMPRT_EXCN_01 on TB_SRC_IMPRT_EXCN (PRJ_SN);
comment on column TB_TBL.SRC_ANTN_CN is '출처 주석 내용';
comment on column TB_COL.SRC_ANTN_CN is '출처 주석 내용';
comment on table TB_SRC_IMPRT_EXCN is '출처 가져오기 실행';
comment on column TB_SRC_IMPRT_EXCN.SRC_IMPRT_EXCN_SN is '출처 가져오기 실행 일련번호';
comment on column TB_SRC_IMPRT_EXCN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_IMPRT_EXCN.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_IMPRT_EXCN.SRC_CLCT_EXCN_SN is '출처 수집 실행 일련번호';
comment on column TB_SRC_IMPRT_EXCN.RFLT_ORD is '반영 순서';
comment on column TB_SRC_IMPRT_EXCN.CHG_GROUP_ID is '변경 그룹 아이디';
comment on column TB_SRC_IMPRT_EXCN.IMPRT_CN is '가져오기 내용';
comment on column TB_SRC_IMPRT_EXCN.REG_DT is '등록 일시';
comment on column TB_SRC_IMPRT_EXCN.MDFCN_DT is '수정 일시';
comment on column TB_SRC_IMPRT_EXCN.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_IMPRT_EXCN.MDFR_ID is '수정자 아이디';

-- ---------- 예전 V4 : SEC-1 : 외부 연결 보안 정책·관리자 허용 목록 (docs/external-connection-security-sec1.md) ----------

create sequence SQ_EXTRL_CNTN_RULE start with 1 increment by 50;
create sequence SQ_EXTRL_CNTN_RULE_HSTRY start with 1 increment by 50;
create sequence SQ_EXTRL_CNTN_HSTRY start with 1 increment by 50;
create table TB_EXTRL_CNTN_RULE (
    EXTRL_CNTN_RULE_SN bigint not null,
    CNTN_TYPE_CD varchar(20) not null,
    HOST_NM varchar(255),
    ALWNC_ADDR varchar(255) not null,
    PORT_NO integer not null,
    ACTVTN_YN boolean not null,
    RULE_EXPLN varchar(1000),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_EXTRL_CNTN_RULE primary key (EXTRL_CNTN_RULE_SN)
);
create table TB_EXTRL_CNTN_RULE_HSTRY (
    EXTRL_CNTN_RULE_HSTRY_SN bigint not null,
    EXTRL_CNTN_RULE_SN bigint not null,
    CHG_TYPE_CD varchar(20) not null,
    CHG_BFR_CN TEXT,
    CHG_AFTR_CN TEXT,
    DMND_ID varchar(255),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_EXTRL_CNTN_RULE_HSTRY primary key (EXTRL_CNTN_RULE_HSTRY_SN)
);
create table TB_EXTRL_CNTN_HSTRY (
    EXTRL_CNTN_HSTRY_SN bigint not null,
    CNTN_TYPE_CD varchar(20) not null,
    HOST_NM varchar(255) not null,
    PORT_NO integer not null,
    CNTN_ADDR_CN varchar(1000),
    EXTRL_CNTN_RULE_SN bigint,
    RSLT_CD varchar(20) not null,
    RSN_CD varchar(20),
    EXCN_TYPE_CD varchar(20),
    DMND_ID varchar(255),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_EXTRL_CNTN_HSTRY primary key (EXTRL_CNTN_HSTRY_SN)
);
create index IX_EXTRL_CNTN_RULE_01 on TB_EXTRL_CNTN_RULE (CNTN_TYPE_CD, PORT_NO);
create index IX_EXTRL_CNTN_RULE_HSTRY_01 on TB_EXTRL_CNTN_RULE_HSTRY (EXTRL_CNTN_RULE_SN);
create index IX_EXTRL_CNTN_HSTRY_01 on TB_EXTRL_CNTN_HSTRY (REG_DT);
create index IX_EXTRL_CNTN_HSTRY_02 on TB_EXTRL_CNTN_HSTRY (CNTN_TYPE_CD, RSLT_CD);
comment on table TB_EXTRL_CNTN_RULE is '외부 접속 규칙';
comment on column TB_EXTRL_CNTN_RULE.EXTRL_CNTN_RULE_SN is '외부 접속 규칙 일련번호';
comment on column TB_EXTRL_CNTN_RULE.CNTN_TYPE_CD is '접속 유형 코드';
comment on column TB_EXTRL_CNTN_RULE.HOST_NM is '호스트 명';
comment on column TB_EXTRL_CNTN_RULE.ALWNC_ADDR is '허용 주소';
comment on column TB_EXTRL_CNTN_RULE.PORT_NO is '포트 번호';
comment on column TB_EXTRL_CNTN_RULE.ACTVTN_YN is '활성 여부';
comment on column TB_EXTRL_CNTN_RULE.RULE_EXPLN is '규칙 설명';
comment on column TB_EXTRL_CNTN_RULE.REG_DT is '등록 일시';
comment on column TB_EXTRL_CNTN_RULE.MDFCN_DT is '수정 일시';
comment on column TB_EXTRL_CNTN_RULE.RGTR_ID is '등록자 아이디';
comment on column TB_EXTRL_CNTN_RULE.MDFR_ID is '수정자 아이디';
comment on table TB_EXTRL_CNTN_RULE_HSTRY is '외부 접속 규칙 이력';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.EXTRL_CNTN_RULE_HSTRY_SN is '외부 접속 규칙 이력 일련번호';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.EXTRL_CNTN_RULE_SN is '외부 접속 규칙 일련번호';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.CHG_TYPE_CD is '변경 유형 코드';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.CHG_BFR_CN is '변경 이전 내용';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.CHG_AFTR_CN is '변경 이후 내용';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.DMND_ID is '요청 아이디';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.REG_DT is '등록 일시';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.MDFCN_DT is '수정 일시';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.RGTR_ID is '등록자 아이디';
comment on column TB_EXTRL_CNTN_RULE_HSTRY.MDFR_ID is '수정자 아이디';
comment on table TB_EXTRL_CNTN_HSTRY is '외부 접속 이력';
comment on column TB_EXTRL_CNTN_HSTRY.EXTRL_CNTN_HSTRY_SN is '외부 접속 이력 일련번호';
comment on column TB_EXTRL_CNTN_HSTRY.CNTN_TYPE_CD is '접속 유형 코드';
comment on column TB_EXTRL_CNTN_HSTRY.HOST_NM is '호스트 명';
comment on column TB_EXTRL_CNTN_HSTRY.PORT_NO is '포트 번호';
comment on column TB_EXTRL_CNTN_HSTRY.CNTN_ADDR_CN is '접속 주소 내용';
comment on column TB_EXTRL_CNTN_HSTRY.EXTRL_CNTN_RULE_SN is '외부 접속 규칙 일련번호';
comment on column TB_EXTRL_CNTN_HSTRY.RSLT_CD is '결과 코드';
comment on column TB_EXTRL_CNTN_HSTRY.RSN_CD is '사유 코드';
comment on column TB_EXTRL_CNTN_HSTRY.EXCN_TYPE_CD is '실행 유형 코드';
comment on column TB_EXTRL_CNTN_HSTRY.DMND_ID is '요청 아이디';
comment on column TB_EXTRL_CNTN_HSTRY.REG_DT is '등록 일시';
comment on column TB_EXTRL_CNTN_HSTRY.MDFCN_DT is '수정 일시';
comment on column TB_EXTRL_CNTN_HSTRY.RGTR_ID is '등록자 아이디';
comment on column TB_EXTRL_CNTN_HSTRY.MDFR_ID is '수정자 아이디';

-- ---------- 예전 V5 : M-4g : 원본 TABLE 매핑 예외 (docs/source-mapping-m4c.md 13장) ----------

comment on column TB_SRC_TBL_MPNG.MPNG_EXCL_YN is '매핑 예외 여부';

-- ---------- 예전 V7 : FK 삭제·수정 규칙과 컬럼 IDENTITY (docs/lexorma-example-sync.md 2장) ----------

comment on column TB_TBL_REL.DEL_RULE_CD is '삭제 규칙 코드';
comment on column TB_TBL_REL.MDFCN_RULE_CD is '수정 규칙 코드';
comment on column TB_COL.AUTO_CRT_MTHD_CD is '자동 생성 방법 코드';

-- ---------- 예전 V8 : AI-3 : AI Provider 관리 (docs/ai-provider-ai3.md) ----------

create sequence SQ_AI_PRVDR start with 1 increment by 50;
create table TB_AI_PRVDR (
    AI_PRVDR_SN bigint not null,
    PRVDR_NM varchar(100) not null,
    PRVDR_TYPE_CD varchar(20) not null,
    BSC_URL_ADDR varchar(255) not null,
    BSC_MDL_NM varchar(255),
    ACTVTN_YN boolean not null,
    PRVDR_EXPLN varchar(1000),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_PRVDR primary key (AI_PRVDR_SN)
);
create unique index UX_AI_PRVDR_01 on TB_AI_PRVDR (PRVDR_NM);
comment on table TB_AI_PRVDR is 'AI 제공자';
comment on column TB_AI_PRVDR.AI_PRVDR_SN is 'AI 제공자 일련번호';
comment on column TB_AI_PRVDR.PRVDR_NM is '제공자 명';
comment on column TB_AI_PRVDR.PRVDR_TYPE_CD is '제공자 유형 코드';
comment on column TB_AI_PRVDR.BSC_URL_ADDR is '기본 URL 주소';
comment on column TB_AI_PRVDR.BSC_MDL_NM is '기본 모델 명';
comment on column TB_AI_PRVDR.ACTVTN_YN is '활성 여부';
comment on column TB_AI_PRVDR.PRVDR_EXPLN is '제공자 설명';
comment on column TB_AI_PRVDR.REG_DT is '등록 일시';
comment on column TB_AI_PRVDR.MDFCN_DT is '수정 일시';
comment on column TB_AI_PRVDR.RGTR_ID is '등록자 아이디';
comment on column TB_AI_PRVDR.MDFR_ID is '수정자 아이디';

-- ---------- 예전 V9 : 시스템 기본 AI Provider (docs/ai-provider-ai3.md 13장) ----------

create table TB_AI_STNG (
    AI_STNG_SN bigint not null,
    BSC_AI_PRVDR_SN bigint,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_STNG primary key (AI_STNG_SN),
    constraint CK_AI_STNG_01 check (AI_STNG_SN = 1),
    constraint FK_AI_STNG_01 foreign key (BSC_AI_PRVDR_SN) references TB_AI_PRVDR (AI_PRVDR_SN)
);
comment on table TB_AI_STNG is 'AI 설정';
comment on column TB_AI_STNG.AI_STNG_SN is 'AI 설정 일련번호';
comment on column TB_AI_STNG.BSC_AI_PRVDR_SN is '기본 AI 제공자 일련번호';
comment on column TB_AI_STNG.REG_DT is '등록 일시';
comment on column TB_AI_STNG.MDFCN_DT is '수정 일시';
comment on column TB_AI_STNG.RGTR_ID is '등록자 아이디';
comment on column TB_AI_STNG.MDFR_ID is '수정자 아이디';

-- ---------- 예전 V10 : AI-5 : AI 데이터 사전 초안 생성 및 승인 (docs/ai-dictionary-draft-ai5.md) ----------

create sequence SQ_AI_EXPLN_SGSTN start with 1 increment by 50;
create sequence SQ_AI_EXPLN_SGSTN_HSTRY start with 1 increment by 50;
create sequence SQ_AI_EXPLN_CRT_EXCN start with 1 increment by 50;
create table TB_AI_EXPLN_SGSTN (
    AI_EXPLN_SGSTN_SN bigint not null,
    PRJ_SN bigint not null,
    TRGT_TYPE_CD varchar(20) not null,
    TBL_SN bigint not null,
    COL_SN bigint,
    TRGT_NM varchar(255) not null,
    SGSTN_CYCL integer not null,
    SGSTN_EXPLN TEXT not null,
    CRTNTY_CD varchar(20) not null,
    RVW_STTS_CD varchar(20) not null,
    SGSTN_DTL_CN TEXT,
    EVDNC_CN TEXT,
    CRTR_VER_CN TEXT not null,
    AI_PRVDR_SN bigint,
    MDL_NM varchar(255),
    PRMPT_VER_NM varchar(30) not null,
    PRMPT_HASH_VL varchar(255) not null,
    CRT_MTHD_CD varchar(20) not null,
    AI_EXPLN_CRT_EXCN_SN bigint,
    RVWR_ID varchar(255),
    RVW_DT timestamp(6),
    CFMTN_EXPLN TEXT,
    RVW_RSN_CN varchar(1000),
    RRVW_NEED_YN boolean not null,
    VER_NO bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_EXPLN_SGSTN primary key (AI_EXPLN_SGSTN_SN)
);
create index IX_AI_EXPLN_SGSTN_01 on TB_AI_EXPLN_SGSTN (PRJ_SN, TBL_SN, COL_SN);
create index IX_AI_EXPLN_SGSTN_02 on TB_AI_EXPLN_SGSTN (PRJ_SN, RVW_STTS_CD);
create table TB_AI_EXPLN_SGSTN_HSTRY (
    AI_EXPLN_SGSTN_HSTRY_SN bigint not null,
    PRJ_SN bigint not null,
    AI_EXPLN_SGSTN_SN bigint not null,
    PRCS_TYPE_CD varchar(20) not null,
    BFR_STTS_CD varchar(20),
    AFTR_STTS_CD varchar(20) not null,
    PRCR_ID varchar(255),
    PRCS_DT timestamp(6) not null,
    PRCS_RSN_CN varchar(1000),
    CFMTN_EXPLN TEXT,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_EXPLN_SGSTN_HSTRY primary key (AI_EXPLN_SGSTN_HSTRY_SN)
);
create index IX_AI_EXPLN_SGSTN_HSTRY_01 on TB_AI_EXPLN_SGSTN_HSTRY (AI_EXPLN_SGSTN_SN);
create table TB_AI_EXPLN_CRT_EXCN (
    AI_EXPLN_CRT_EXCN_SN bigint not null,
    PRJ_SN bigint not null,
    ACNT_SN bigint not null,
    EXCN_STTS_CD varchar(20) not null,
    TRGT_CN TEXT not null,
    TRGT_CNT integer not null,
    CMPTN_CNT integer not null,
    FAIL_CNT integer not null,
    SKIP_CNT integer not null,
    BGNG_DT timestamp(6),
    END_DT timestamp(6),
    ERR_CN TEXT,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_EXPLN_CRT_EXCN primary key (AI_EXPLN_CRT_EXCN_SN)
);
create index IX_AI_EXPLN_CRT_EXCN_01 on TB_AI_EXPLN_CRT_EXCN (PRJ_SN, EXCN_STTS_CD);
comment on column TB_TBL.TASK_EXPLN is '업무 설명';
comment on column TB_COL.TASK_EXPLN is '업무 설명';
comment on table TB_AI_EXPLN_SGSTN is 'AI 설명 제안';
comment on column TB_AI_EXPLN_SGSTN.AI_EXPLN_SGSTN_SN is 'AI 설명 제안 일련번호';
comment on column TB_AI_EXPLN_SGSTN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_AI_EXPLN_SGSTN.TRGT_TYPE_CD is '대상 유형 코드';
comment on column TB_AI_EXPLN_SGSTN.TBL_SN is '테이블 일련번호';
comment on column TB_AI_EXPLN_SGSTN.COL_SN is '컬럼 일련번호';
comment on column TB_AI_EXPLN_SGSTN.TRGT_NM is '대상 명';
comment on column TB_AI_EXPLN_SGSTN.SGSTN_CYCL is '제안 차수';
comment on column TB_AI_EXPLN_SGSTN.SGSTN_EXPLN is '제안 설명';
comment on column TB_AI_EXPLN_SGSTN.CRTNTY_CD is '확실성 코드';
comment on column TB_AI_EXPLN_SGSTN.RVW_STTS_CD is '검토 상태 코드';
comment on column TB_AI_EXPLN_SGSTN.SGSTN_DTL_CN is '제안 상세 내용';
comment on column TB_AI_EXPLN_SGSTN.EVDNC_CN is '근거 내용';
comment on column TB_AI_EXPLN_SGSTN.CRTR_VER_CN is '기준 버전 내용';
comment on column TB_AI_EXPLN_SGSTN.AI_PRVDR_SN is 'AI 제공자 일련번호';
comment on column TB_AI_EXPLN_SGSTN.MDL_NM is '모델 명';
comment on column TB_AI_EXPLN_SGSTN.PRMPT_VER_NM is '프롬프트 버전 명';
comment on column TB_AI_EXPLN_SGSTN.PRMPT_HASH_VL is '프롬프트 해시 값';
comment on column TB_AI_EXPLN_SGSTN.CRT_MTHD_CD is '생성 방법 코드';
comment on column TB_AI_EXPLN_SGSTN.AI_EXPLN_CRT_EXCN_SN is 'AI 설명 생성 실행 일련번호';
comment on column TB_AI_EXPLN_SGSTN.RVWR_ID is '검토자 아이디';
comment on column TB_AI_EXPLN_SGSTN.RVW_DT is '검토 일시';
comment on column TB_AI_EXPLN_SGSTN.CFMTN_EXPLN is '확정 설명';
comment on column TB_AI_EXPLN_SGSTN.RVW_RSN_CN is '검토 사유 내용';
comment on column TB_AI_EXPLN_SGSTN.RRVW_NEED_YN is '재검토 필요 여부';
comment on column TB_AI_EXPLN_SGSTN.VER_NO is '버전 번호';
comment on column TB_AI_EXPLN_SGSTN.REG_DT is '등록 일시';
comment on column TB_AI_EXPLN_SGSTN.MDFCN_DT is '수정 일시';
comment on column TB_AI_EXPLN_SGSTN.RGTR_ID is '등록자 아이디';
comment on column TB_AI_EXPLN_SGSTN.MDFR_ID is '수정자 아이디';
comment on table TB_AI_EXPLN_SGSTN_HSTRY is 'AI 설명 제안 이력';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.AI_EXPLN_SGSTN_HSTRY_SN is 'AI 설명 제안 이력 일련번호';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.PRJ_SN is '프로젝트 일련번호';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.AI_EXPLN_SGSTN_SN is 'AI 설명 제안 일련번호';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.PRCS_TYPE_CD is '처리 유형 코드';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.BFR_STTS_CD is '이전 상태 코드';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.AFTR_STTS_CD is '이후 상태 코드';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.PRCR_ID is '처리자 아이디';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.PRCS_DT is '처리 일시';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.PRCS_RSN_CN is '처리 사유 내용';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.CFMTN_EXPLN is '확정 설명';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.REG_DT is '등록 일시';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.MDFCN_DT is '수정 일시';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.RGTR_ID is '등록자 아이디';
comment on column TB_AI_EXPLN_SGSTN_HSTRY.MDFR_ID is '수정자 아이디';
comment on table TB_AI_EXPLN_CRT_EXCN is 'AI 설명 생성 실행';
comment on column TB_AI_EXPLN_CRT_EXCN.AI_EXPLN_CRT_EXCN_SN is 'AI 설명 생성 실행 일련번호';
comment on column TB_AI_EXPLN_CRT_EXCN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_AI_EXPLN_CRT_EXCN.ACNT_SN is '계정 일련번호';
comment on column TB_AI_EXPLN_CRT_EXCN.EXCN_STTS_CD is '실행 상태 코드';
comment on column TB_AI_EXPLN_CRT_EXCN.TRGT_CN is '대상 내용';
comment on column TB_AI_EXPLN_CRT_EXCN.TRGT_CNT is '대상 수';
comment on column TB_AI_EXPLN_CRT_EXCN.CMPTN_CNT is '완료 수';
comment on column TB_AI_EXPLN_CRT_EXCN.FAIL_CNT is '실패 수';
comment on column TB_AI_EXPLN_CRT_EXCN.SKIP_CNT is '건너뜀 수';
comment on column TB_AI_EXPLN_CRT_EXCN.BGNG_DT is '시작 일시';
comment on column TB_AI_EXPLN_CRT_EXCN.END_DT is '종료 일시';
comment on column TB_AI_EXPLN_CRT_EXCN.ERR_CN is '오류 내용';
comment on column TB_AI_EXPLN_CRT_EXCN.REG_DT is '등록 일시';
comment on column TB_AI_EXPLN_CRT_EXCN.MDFCN_DT is '수정 일시';
comment on column TB_AI_EXPLN_CRT_EXCN.RGTR_ID is '등록자 아이디';
comment on column TB_AI_EXPLN_CRT_EXCN.MDFR_ID is '수정자 아이디';

-- ---------- AI-5 품질 평가 : 평가 대상·판정 (2026-11-15, 예전 증분 V2. docs/completed/ai-dictionary-draft-ai5.md 16장) ----------
-- TB_AI_EXPLN_EVL_TRGT : 평가 대상 (테이블·컬럼, 범주, 담당자가 확인한 실제 업무 의미·확인 상태, 등록 시점 메타데이터 버전). 실제 의미는 평가 기준이라 AI 에 보내지 않는다
-- TB_AI_EXPLN_EVL : AI 설명 제안 하나에 대한 사람의 판정 (검토자당 하나). 모델·프롬프트 버전을 함께 남긴다. AI 가 판정하지 않는다
-- 모델 테이블·제안에 FK 를 걸지 않는다 (AI-5 제안과 같은 방식). 프로젝트 삭제 때 함께 지운다 (ProjectDataCleanup)
create sequence SQ_AI_EXPLN_EVL_TRGT start with 1 increment by 50;
create sequence SQ_AI_EXPLN_EVL start with 1 increment by 50;

create table TB_AI_EXPLN_EVL_TRGT (
    AI_EXPLN_EVL_TRGT_SN bigint not null,
    PRJ_SN bigint not null,
    TRGT_TYPE_CD varchar(20) not null,
    TBL_SN bigint not null,
    COL_SN bigint,
    TRGT_NM varchar(255) not null,
    EVL_CTGRY_CN TEXT,
    ACTL_MNNG_CN TEXT,
    MNNG_IDNTY_STTS_CD varchar(20) not null,
    CRTR_VER_CN TEXT not null,
    RVWR_ID varchar(255),
    RVW_DT timestamp(6),
    VER_NO bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_EXPLN_EVL_TRGT primary key (AI_EXPLN_EVL_TRGT_SN)
);

create index IX_AI_EXPLN_EVL_TRGT_01 on TB_AI_EXPLN_EVL_TRGT (PRJ_SN, TBL_SN, COL_SN);

create table TB_AI_EXPLN_EVL (
    AI_EXPLN_EVL_SN bigint not null,
    PRJ_SN bigint not null,
    AI_EXPLN_EVL_TRGT_SN bigint not null,
    AI_EXPLN_SGSTN_SN bigint not null,
    MDL_NM varchar(255),
    PRMPT_VER_NM varchar(30) not null,
    JGMT_RSLT_CD varchar(20) not null,
    UNFND_CLM_YN boolean not null,
    OMSN_INFO_CN TEXT,
    UNCRT_JGMT_CD varchar(20) not null,
    TRM_RCMDTN_JGMT_CD varchar(20) not null,
    DMN_RCMDTN_JGMT_CD varchar(20) not null,
    MDFCN_NEED_CN TEXT,
    RVW_REQ_HR integer,
    WRT_REQ_HR integer,
    RVWR_ID varchar(255) not null,
    RVW_DT timestamp(6) not null,
    VER_NO bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_EXPLN_EVL primary key (AI_EXPLN_EVL_SN)
);

create unique index UX_AI_EXPLN_EVL_01 on TB_AI_EXPLN_EVL (AI_EXPLN_SGSTN_SN, RVWR_ID);
create index IX_AI_EXPLN_EVL_01 on TB_AI_EXPLN_EVL (PRJ_SN, AI_EXPLN_EVL_TRGT_SN);

comment on table TB_AI_EXPLN_EVL_TRGT is 'AI 설명 평가 대상';
comment on column TB_AI_EXPLN_EVL_TRGT.AI_EXPLN_EVL_TRGT_SN is 'AI 설명 평가 대상 일련번호';
comment on column TB_AI_EXPLN_EVL_TRGT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_AI_EXPLN_EVL_TRGT.TRGT_TYPE_CD is '대상 유형 코드';
comment on column TB_AI_EXPLN_EVL_TRGT.TBL_SN is '테이블 일련번호';
comment on column TB_AI_EXPLN_EVL_TRGT.COL_SN is '컬럼 일련번호';
comment on column TB_AI_EXPLN_EVL_TRGT.TRGT_NM is '대상 명';
comment on column TB_AI_EXPLN_EVL_TRGT.EVL_CTGRY_CN is '평가 범주 내용';
comment on column TB_AI_EXPLN_EVL_TRGT.ACTL_MNNG_CN is '실제 의미 내용';
comment on column TB_AI_EXPLN_EVL_TRGT.MNNG_IDNTY_STTS_CD is '의미 확인 상태 코드';
comment on column TB_AI_EXPLN_EVL_TRGT.CRTR_VER_CN is '기준 버전 내용';
comment on column TB_AI_EXPLN_EVL_TRGT.RVWR_ID is '검토자 아이디';
comment on column TB_AI_EXPLN_EVL_TRGT.RVW_DT is '검토 일시';
comment on column TB_AI_EXPLN_EVL_TRGT.VER_NO is '버전 번호';
comment on column TB_AI_EXPLN_EVL_TRGT.REG_DT is '등록 일시';
comment on column TB_AI_EXPLN_EVL_TRGT.MDFCN_DT is '수정 일시';
comment on column TB_AI_EXPLN_EVL_TRGT.RGTR_ID is '등록자 아이디';
comment on column TB_AI_EXPLN_EVL_TRGT.MDFR_ID is '수정자 아이디';
comment on table TB_AI_EXPLN_EVL is 'AI 설명 평가';
comment on column TB_AI_EXPLN_EVL.AI_EXPLN_EVL_SN is 'AI 설명 평가 일련번호';
comment on column TB_AI_EXPLN_EVL.PRJ_SN is '프로젝트 일련번호';
comment on column TB_AI_EXPLN_EVL.AI_EXPLN_EVL_TRGT_SN is 'AI 설명 평가 대상 일련번호';
comment on column TB_AI_EXPLN_EVL.AI_EXPLN_SGSTN_SN is 'AI 설명 제안 일련번호';
comment on column TB_AI_EXPLN_EVL.MDL_NM is '모델 명';
comment on column TB_AI_EXPLN_EVL.PRMPT_VER_NM is '프롬프트 버전 명';
comment on column TB_AI_EXPLN_EVL.JGMT_RSLT_CD is '판정 결과 코드';
comment on column TB_AI_EXPLN_EVL.UNFND_CLM_YN is '무근거 주장 여부';
comment on column TB_AI_EXPLN_EVL.OMSN_INFO_CN is '누락 정보 내용';
comment on column TB_AI_EXPLN_EVL.UNCRT_JGMT_CD is '불확실 판정 코드';
comment on column TB_AI_EXPLN_EVL.TRM_RCMDTN_JGMT_CD is '용어 추천 판정 코드';
comment on column TB_AI_EXPLN_EVL.DMN_RCMDTN_JGMT_CD is '도메인 추천 판정 코드';
comment on column TB_AI_EXPLN_EVL.MDFCN_NEED_CN is '수정 필요 내용';
comment on column TB_AI_EXPLN_EVL.RVW_REQ_HR is '검토 소요 시간';
comment on column TB_AI_EXPLN_EVL.WRT_REQ_HR is '작성 소요 시간';
comment on column TB_AI_EXPLN_EVL.RVWR_ID is '검토자 아이디';
comment on column TB_AI_EXPLN_EVL.RVW_DT is '검토 일시';
comment on column TB_AI_EXPLN_EVL.VER_NO is '버전 번호';
comment on column TB_AI_EXPLN_EVL.REG_DT is '등록 일시';
comment on column TB_AI_EXPLN_EVL.MDFCN_DT is '수정 일시';
comment on column TB_AI_EXPLN_EVL.RGTR_ID is '등록자 아이디';
comment on column TB_AI_EXPLN_EVL.MDFR_ID is '수정자 아이디';

-- ---------- 예전 증분 V2 (2026-11-18 세 번째 합침) ----------
-- 예전 증분 V2 : AI 설명 평가 판정 변경 이력 (DEC-27, 2026-11-16 — docs/completed/ai-dictionary-draft-ai5.md 29장). 판정(TB_AI_EXPLN_EVL)을 저장·수정할 때마다 추가만 한다.
-- 기존 판정 데이터는 그대로 두고 이력을 소급해 만들지 않는다. 예제(프로젝트 0)에 같은 테이블을 용어 → 물리 → 논리 → 매핑 → 인덱스 → 업무 설명 순서로 넣는다 (V0SeedMigrationTest·SmokeTest).
create sequence SQ_AI_EXPLN_EVL_HSTRY start with 1 increment by 50;
create table TB_AI_EXPLN_EVL_HSTRY (
    AI_EXPLN_EVL_HSTRY_SN bigint not null,
    PRJ_SN bigint not null,
    AI_EXPLN_EVL_SN bigint not null,
    AI_EXPLN_EVL_TRGT_SN bigint not null,
    AI_EXPLN_SGSTN_SN bigint not null,
    CHG_TYPE_CD varchar(20) not null,
    RVWR_ID varchar(255) not null,
    CHG_BFR_CN TEXT,
    CHG_AFTR_CN TEXT not null,
    PRCS_RSN_CN varchar(1000),
    CHG_DT timestamp(6) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_AI_EXPLN_EVL_HSTRY primary key (AI_EXPLN_EVL_HSTRY_SN)
);
create index IX_AI_EXPLN_EVL_HSTRY_01 on TB_AI_EXPLN_EVL_HSTRY (AI_EXPLN_EVL_SN);
comment on table TB_AI_EXPLN_EVL_HSTRY is 'AI 설명 평가 이력';
comment on column TB_AI_EXPLN_EVL_HSTRY.AI_EXPLN_EVL_HSTRY_SN is 'AI 설명 평가 이력 일련번호';
comment on column TB_AI_EXPLN_EVL_HSTRY.PRJ_SN is '프로젝트 일련번호';
comment on column TB_AI_EXPLN_EVL_HSTRY.AI_EXPLN_EVL_SN is 'AI 설명 평가 일련번호';
comment on column TB_AI_EXPLN_EVL_HSTRY.AI_EXPLN_EVL_TRGT_SN is 'AI 설명 평가 대상 일련번호';
comment on column TB_AI_EXPLN_EVL_HSTRY.AI_EXPLN_SGSTN_SN is 'AI 설명 제안 일련번호';
comment on column TB_AI_EXPLN_EVL_HSTRY.CHG_TYPE_CD is '변경 유형 코드';
comment on column TB_AI_EXPLN_EVL_HSTRY.RVWR_ID is '검토자 아이디';
comment on column TB_AI_EXPLN_EVL_HSTRY.CHG_BFR_CN is '변경 이전 내용';
comment on column TB_AI_EXPLN_EVL_HSTRY.CHG_AFTR_CN is '변경 이후 내용';
comment on column TB_AI_EXPLN_EVL_HSTRY.PRCS_RSN_CN is '처리 사유 내용';
comment on column TB_AI_EXPLN_EVL_HSTRY.CHG_DT is '변경 일시';
comment on column TB_AI_EXPLN_EVL_HSTRY.REG_DT is '등록 일시';
comment on column TB_AI_EXPLN_EVL_HSTRY.MDFCN_DT is '수정 일시';
comment on column TB_AI_EXPLN_EVL_HSTRY.RGTR_ID is '등록자 아이디';
comment on column TB_AI_EXPLN_EVL_HSTRY.MDFR_ID is '수정자 아이디';

-- ---------- 예전 증분 V3 (2026-11-18 세 번째 합침) ----------
-- 예전 증분 V3 : 프로젝트별 AI 제공자 지정 (DEC-18, 2026-11-16 — docs/completed/ai-provider-ai3.md 15장). 행이 없으면 시스템 기본 제공자(TB_AI_STNG)를 쓴다.
-- 기존 데이터는 바꾸지 않는다 (지정 없는 상태로 시작). 예제(프로젝트 0)에 같은 테이블을 용어 → 물리 → 논리 → 매핑 → 관계 → 인덱스 → 업무 설명 순서로 넣는다.
create sequence SQ_PRJ_AI_STNG start with 1 increment by 50;
create table TB_PRJ_AI_STNG (
    PRJ_AI_STNG_SN bigint not null,
    PRJ_SN bigint not null,
    AI_PRVDR_SN bigint not null,
    MDL_NM varchar(255),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_PRJ_AI_STNG primary key (PRJ_AI_STNG_SN)
);
create unique index UX_PRJ_AI_STNG_01 on TB_PRJ_AI_STNG (PRJ_SN);
alter table TB_PRJ_AI_STNG add constraint FK_PRJ_AI_STNG_01 foreign key (PRJ_SN) references TB_PRJ;
alter table TB_PRJ_AI_STNG add constraint FK_PRJ_AI_STNG_02 foreign key (AI_PRVDR_SN) references TB_AI_PRVDR;
comment on table TB_PRJ_AI_STNG is '프로젝트 AI 설정';
comment on column TB_PRJ_AI_STNG.PRJ_AI_STNG_SN is '프로젝트 AI 설정 일련번호';
comment on column TB_PRJ_AI_STNG.PRJ_SN is '프로젝트 일련번호';
comment on column TB_PRJ_AI_STNG.AI_PRVDR_SN is 'AI 제공자 일련번호';
comment on column TB_PRJ_AI_STNG.MDL_NM is '모델 명';
comment on column TB_PRJ_AI_STNG.REG_DT is '등록 일시';
comment on column TB_PRJ_AI_STNG.MDFCN_DT is '수정 일시';
comment on column TB_PRJ_AI_STNG.RGTR_ID is '등록자 아이디';
comment on column TB_PRJ_AI_STNG.MDFR_ID is '수정자 아이디';

-- ---------- 예전 증분 V4 (2026-11-18 세 번째 합침) ----------
-- 예전 증분 V4 : 출처 컬럼 매핑·매핑 이력 (DEC-06 이름 변경 매핑, 2026-11-16 — docs/completed/source-rename-mapping-dec06.md).
-- 출처에서 이름이 바뀐 테이블·컬럼을 사용자가 확인해 기존 모델 객체와 잇는다 (자동 확정 없음). 기존 테이블 매핑(TB_SRC_TBL_MPNG)·데이터는 바꾸지 않는다.
-- 예제(프로젝트 0)에 두 테이블을 용어 → 물리 → 논리 → 매핑 → 관계 → 인덱스 → 업무 설명 순서로 넣는다 (V3 와 같은 규칙).
create sequence SQ_SRC_COL_MPNG start with 1 increment by 50;
create table TB_SRC_COL_MPNG (
    SRC_COL_MPNG_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_TBL_MPNG_SN bigint not null,
    CMPR_NM varchar(255) not null,
    COL_NM varchar(255) not null,
    COL_SN bigint,
    RFLT_ORD integer not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_COL_MPNG primary key (SRC_COL_MPNG_SN)
);
create unique index UX_SRC_COL_MPNG_01 on TB_SRC_COL_MPNG (SRC_TBL_MPNG_SN, CMPR_NM);
create unique index UX_SRC_COL_MPNG_02 on TB_SRC_COL_MPNG (SRC_TBL_MPNG_SN, COL_SN);
create index IX_SRC_COL_MPNG_01 on TB_SRC_COL_MPNG (PRJ_SN);
create index IX_SRC_COL_MPNG_02 on TB_SRC_COL_MPNG (COL_SN);
alter table TB_SRC_COL_MPNG add constraint FK_SRC_COL_MPNG_01 foreign key (SRC_TBL_MPNG_SN) references TB_SRC_TBL_MPNG on delete cascade;
alter table TB_SRC_COL_MPNG add constraint FK_SRC_COL_MPNG_02 foreign key (COL_SN) references TB_COL on delete set null;
comment on table TB_SRC_COL_MPNG is '출처 컬럼 매핑';
comment on column TB_SRC_COL_MPNG.SRC_COL_MPNG_SN is '출처 컬럼 매핑 일련번호';
comment on column TB_SRC_COL_MPNG.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_COL_MPNG.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_COL_MPNG.SRC_TBL_MPNG_SN is '출처 테이블 매핑 일련번호';
comment on column TB_SRC_COL_MPNG.CMPR_NM is '비교 명';
comment on column TB_SRC_COL_MPNG.COL_NM is '컬럼 명';
comment on column TB_SRC_COL_MPNG.COL_SN is '컬럼 일련번호';
comment on column TB_SRC_COL_MPNG.RFLT_ORD is '반영 순서';
comment on column TB_SRC_COL_MPNG.REG_DT is '등록 일시';
comment on column TB_SRC_COL_MPNG.MDFCN_DT is '수정 일시';
comment on column TB_SRC_COL_MPNG.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_COL_MPNG.MDFR_ID is '수정자 아이디';
create sequence SQ_SRC_MPNG_HSTRY start with 1 increment by 50;
create table TB_SRC_MPNG_HSTRY (
    SRC_MPNG_HSTRY_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_TBL_MPNG_SN bigint not null,
    TRGT_TYPE_CD varchar(20) not null,
    CHG_TYPE_CD varchar(20) not null,
    CHG_BFR_CN TEXT,
    CHG_AFTR_CN TEXT,
    CHG_DT timestamp(6) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_MPNG_HSTRY primary key (SRC_MPNG_HSTRY_SN)
);
create index IX_SRC_MPNG_HSTRY_01 on TB_SRC_MPNG_HSTRY (SRC_TBL_MPNG_SN);
create index IX_SRC_MPNG_HSTRY_02 on TB_SRC_MPNG_HSTRY (PRJ_SN);
comment on table TB_SRC_MPNG_HSTRY is '출처 매핑 이력';
comment on column TB_SRC_MPNG_HSTRY.SRC_MPNG_HSTRY_SN is '출처 매핑 이력 일련번호';
comment on column TB_SRC_MPNG_HSTRY.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_MPNG_HSTRY.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_MPNG_HSTRY.SRC_TBL_MPNG_SN is '출처 테이블 매핑 일련번호';
comment on column TB_SRC_MPNG_HSTRY.TRGT_TYPE_CD is '대상 유형 코드';
comment on column TB_SRC_MPNG_HSTRY.CHG_TYPE_CD is '변경 유형 코드';
comment on column TB_SRC_MPNG_HSTRY.CHG_BFR_CN is '변경 이전 내용';
comment on column TB_SRC_MPNG_HSTRY.CHG_AFTR_CN is '변경 이후 내용';
comment on column TB_SRC_MPNG_HSTRY.CHG_DT is '변경 일시';
comment on column TB_SRC_MPNG_HSTRY.REG_DT is '등록 일시';
comment on column TB_SRC_MPNG_HSTRY.MDFCN_DT is '수정 일시';
comment on column TB_SRC_MPNG_HSTRY.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_MPNG_HSTRY.MDFR_ID is '수정자 아이디';

-- ---------- 예전 증분 V5 (2026-11-18 세 번째 합침) ----------
-- 예전 증분 V5 : 출처 VIEW 정의·정의 확인 이력 (DEC-16 원본 VIEW 정의 수집, 2026-11-16 — docs/completed/source-view-definition-dec16.md).
-- 정의 원문은 이 테이블에만 둔다 (카탈로그 버전·이력에는 해시만). 기존 원본 카탈로그 데이터는 바꾸지 않는다 : 정의는 다음 수집부터 채워진다.
-- 예제(프로젝트 0)에 두 테이블을 용어 → 물리 → 논리 → 매핑 → 관계 → 인덱스 → 업무 설명 순서로 넣는다 (V3·V4 와 같은 규칙).
create sequence SQ_SRC_VIEW_DFN start with 1 increment by 50;
create table TB_SRC_VIEW_DFN (
    SRC_VIEW_DFN_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_TBL_SN bigint not null,
    DFN_STTS_CD varchar(20) not null,
    DFN_CN TEXT,
    ENCPT_YN boolean not null,
    HASH_VL varchar(255),
    BFR_HASH_VL varchar(255),
    DFN_LEN integer,
    CLCT_DT timestamp(6),
    CHG_RFLT_ORD integer,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_VIEW_DFN primary key (SRC_VIEW_DFN_SN)
);
create unique index UX_SRC_VIEW_DFN_01 on TB_SRC_VIEW_DFN (SRC_TBL_SN);
create index IX_SRC_VIEW_DFN_01 on TB_SRC_VIEW_DFN (PRJ_SN);
create index IX_SRC_VIEW_DFN_02 on TB_SRC_VIEW_DFN (SRC_CNTN_SN);
alter table TB_SRC_VIEW_DFN add constraint FK_SRC_VIEW_DFN_01 foreign key (SRC_TBL_SN) references TB_SRC_TBL on delete cascade;
comment on table TB_SRC_VIEW_DFN is '출처 뷰 정의';
comment on column TB_SRC_VIEW_DFN.SRC_VIEW_DFN_SN is '출처 뷰 정의 일련번호';
comment on column TB_SRC_VIEW_DFN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_VIEW_DFN.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_VIEW_DFN.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_VIEW_DFN.DFN_STTS_CD is '정의 상태 코드';
comment on column TB_SRC_VIEW_DFN.DFN_CN is '정의 내용';
comment on column TB_SRC_VIEW_DFN.ENCPT_YN is '암호화 여부';
comment on column TB_SRC_VIEW_DFN.HASH_VL is '해시 값';
comment on column TB_SRC_VIEW_DFN.BFR_HASH_VL is '이전 해시 값';
comment on column TB_SRC_VIEW_DFN.DFN_LEN is '정의 길이';
comment on column TB_SRC_VIEW_DFN.CLCT_DT is '수집 일시';
comment on column TB_SRC_VIEW_DFN.CHG_RFLT_ORD is '변경 반영 순서';
comment on column TB_SRC_VIEW_DFN.REG_DT is '등록 일시';
comment on column TB_SRC_VIEW_DFN.MDFCN_DT is '수정 일시';
comment on column TB_SRC_VIEW_DFN.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_VIEW_DFN.MDFR_ID is '수정자 아이디';
create sequence SQ_SRC_VIEW_DFN_IDNTY_HSTRY start with 1 increment by 50;
create table TB_SRC_VIEW_DFN_IDNTY_HSTRY (
    SRC_VIEW_DFN_IDNTY_HSTRY_SN bigint not null,
    PRJ_SN bigint not null,
    SRC_CNTN_SN bigint not null,
    SRC_TBL_SN bigint not null,
    HASH_VL varchar(255),
    IDNTY_DT timestamp(6) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SRC_VIEW_DFN_IDNTY_HSTRY primary key (SRC_VIEW_DFN_IDNTY_HSTRY_SN)
);
create index IX_SRC_VIEW_DFN_IDNTY_HSTRY_01 on TB_SRC_VIEW_DFN_IDNTY_HSTRY (SRC_TBL_SN);
create index IX_SRC_VIEW_DFN_IDNTY_HSTRY_02 on TB_SRC_VIEW_DFN_IDNTY_HSTRY (PRJ_SN);
comment on table TB_SRC_VIEW_DFN_IDNTY_HSTRY is '출처 뷰 정의 확인 이력';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.SRC_VIEW_DFN_IDNTY_HSTRY_SN is '출처 뷰 정의 확인 이력 일련번호';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.PRJ_SN is '프로젝트 일련번호';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.SRC_CNTN_SN is '출처 접속 일련번호';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.SRC_TBL_SN is '출처 테이블 일련번호';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.HASH_VL is '해시 값';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.IDNTY_DT is '확인 일시';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.REG_DT is '등록 일시';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.MDFCN_DT is '수정 일시';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.RGTR_ID is '등록자 아이디';
comment on column TB_SRC_VIEW_DFN_IDNTY_HSTRY.MDFR_ID is '수정자 아이디';

-- ---------- 예전 증분 V6 (2026-11-18 세 번째 합침) ----------
-- 예전 증분 V6 : 선택형 내장 MCP — 프로젝트 MCP 설정·MCP 토큰·MCP 호출 이력 (DEC-31, 2026-11-16 — docs/completed/embedded-mcp-dec31.md).
-- 토큰 원문은 저장하지 않는다 (해시만). 기존 데이터는 바꾸지 않는다 : 설정 행이 없는 프로젝트는 MCP 가 꺼져 있다.
-- 예제(프로젝트 0)에 두 테이블을 용어 → 물리 → 논리 → 매핑 → 관계 → 인덱스 → 업무 설명 순서로 넣는다 (V3~V5 와 같은 규칙).
create sequence SQ_PRJ_MCP_STNG start with 1 increment by 50;
create table TB_PRJ_MCP_STNG (
    PRJ_MCP_STNG_SN bigint not null,
    PRJ_SN bigint not null,
    ACTVTN_YN boolean not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_PRJ_MCP_STNG primary key (PRJ_MCP_STNG_SN)
);
create unique index UX_PRJ_MCP_STNG_01 on TB_PRJ_MCP_STNG (PRJ_SN);
alter table TB_PRJ_MCP_STNG add constraint FK_PRJ_MCP_STNG_01 foreign key (PRJ_SN) references TB_PRJ;
comment on table TB_PRJ_MCP_STNG is '프로젝트 MCP 설정';
comment on column TB_PRJ_MCP_STNG.PRJ_MCP_STNG_SN is '프로젝트 MCP 설정 일련번호';
comment on column TB_PRJ_MCP_STNG.PRJ_SN is '프로젝트 일련번호';
comment on column TB_PRJ_MCP_STNG.ACTVTN_YN is '활성 여부';
comment on column TB_PRJ_MCP_STNG.REG_DT is '등록 일시';
comment on column TB_PRJ_MCP_STNG.MDFCN_DT is '수정 일시';
comment on column TB_PRJ_MCP_STNG.RGTR_ID is '등록자 아이디';
comment on column TB_PRJ_MCP_STNG.MDFR_ID is '수정자 아이디';
create sequence SQ_MCP_TKN start with 1 increment by 50;
create table TB_MCP_TKN (
    MCP_TKN_SN bigint not null,
    PRJ_SN bigint not null,
    ACNT_SN bigint not null,
    TKN_NM varchar(100) not null,
    HASH_VL varchar(255) not null,
    TKN_PFX_NM varchar(20) not null,
    EXPRY_DT timestamp(6) not null,
    DSCD_DT timestamp(6),
    LAST_USE_DT timestamp(6),
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_MCP_TKN primary key (MCP_TKN_SN)
);
create unique index UX_MCP_TKN_01 on TB_MCP_TKN (HASH_VL);
create index IX_MCP_TKN_01 on TB_MCP_TKN (PRJ_SN);
alter table TB_MCP_TKN add constraint FK_MCP_TKN_01 foreign key (PRJ_SN) references TB_PRJ;
comment on table TB_MCP_TKN is 'MCP 토큰';
comment on column TB_MCP_TKN.MCP_TKN_SN is 'MCP 토큰 일련번호';
comment on column TB_MCP_TKN.PRJ_SN is '프로젝트 일련번호';
comment on column TB_MCP_TKN.ACNT_SN is '계정 일련번호';
comment on column TB_MCP_TKN.TKN_NM is '토큰 명';
comment on column TB_MCP_TKN.HASH_VL is '해시 값';
comment on column TB_MCP_TKN.TKN_PFX_NM is '토큰 접두어 명';
comment on column TB_MCP_TKN.EXPRY_DT is '만료 일시';
comment on column TB_MCP_TKN.DSCD_DT is '폐기 일시';
comment on column TB_MCP_TKN.LAST_USE_DT is '최종 사용 일시';
comment on column TB_MCP_TKN.REG_DT is '등록 일시';
comment on column TB_MCP_TKN.MDFCN_DT is '수정 일시';
comment on column TB_MCP_TKN.RGTR_ID is '등록자 아이디';
comment on column TB_MCP_TKN.MDFR_ID is '수정자 아이디';
create sequence SQ_MCP_CALL_HSTRY start with 1 increment by 50;
create table TB_MCP_CALL_HSTRY (
    MCP_CALL_HSTRY_SN bigint not null,
    PRJ_SN bigint not null,
    MCP_TKN_SN bigint not null,
    TOOL_NM varchar(100) not null,
    RSLT_CD varchar(20) not null,
    CALL_DT timestamp(6) not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_MCP_CALL_HSTRY primary key (MCP_CALL_HSTRY_SN)
);
create index IX_MCP_CALL_HSTRY_01 on TB_MCP_CALL_HSTRY (MCP_TKN_SN);
create index IX_MCP_CALL_HSTRY_02 on TB_MCP_CALL_HSTRY (PRJ_SN);
comment on table TB_MCP_CALL_HSTRY is 'MCP 호출 이력';
comment on column TB_MCP_CALL_HSTRY.MCP_CALL_HSTRY_SN is 'MCP 호출 이력 일련번호';
comment on column TB_MCP_CALL_HSTRY.PRJ_SN is '프로젝트 일련번호';
comment on column TB_MCP_CALL_HSTRY.MCP_TKN_SN is 'MCP 토큰 일련번호';
comment on column TB_MCP_CALL_HSTRY.TOOL_NM is '도구 명';
comment on column TB_MCP_CALL_HSTRY.RSLT_CD is '결과 코드';
comment on column TB_MCP_CALL_HSTRY.CALL_DT is '호출 일시';
comment on column TB_MCP_CALL_HSTRY.REG_DT is '등록 일시';
comment on column TB_MCP_CALL_HSTRY.MDFCN_DT is '수정 일시';
comment on column TB_MCP_CALL_HSTRY.RGTR_ID is '등록자 아이디';
comment on column TB_MCP_CALL_HSTRY.MDFR_ID is '수정자 아이디';

-- ---------- 예전 증분 V7 (2026-11-18 세 번째 합침) ----------
-- 예전 증분 V7 : 논리 관계 속성 짝 (M-7 보강 3차 P1, 2026-11-17 — docs/completed/logical-physical-mapping-m7.md 14장).
-- 관계(원본 = 부모, 대상 = 자식)마다 부모 주 식별자 속성 ↔ 자식 속성 짝을 순서대로 저장한다. 기존 관계에는 짝을 만들지 않는다 (추론하지 않는다 — 짝이 없는 관계는 FK 를 만들지 않는다, P1-a).
-- 관계를 지울 때는 서비스가 짝을 먼저 지운다 (변경 이력이 남도록 FK 에 CASCADE 를 두지 않는다). 속성은 짝에서 쓰는 동안 지울 수 없다.
-- 예제(프로젝트 0)에 새 테이블을 용어 → 물리 → 논리 → 매핑 → 관계(+ 그 관계의 속성 짝) → 인덱스 → 업무 설명 순서로 넣는다 (V3~V6 과 같은 규칙).
create sequence SQ_LGCL_REL_ATRB start with 1 increment by 50;
create table TB_LGCL_REL_ATRB (
    LGCL_REL_ATRB_SN bigint not null,
    LGCL_REL_SN bigint not null,
    ORGNL_ATRB_SN bigint not null,
    TRGT_ATRB_SN bigint not null,
    SORT_ORD integer not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_REL_ATRB primary key (LGCL_REL_ATRB_SN)
);
create unique index UX_LGCL_REL_ATRB_01 on TB_LGCL_REL_ATRB (LGCL_REL_SN, ORGNL_ATRB_SN);
create unique index UX_LGCL_REL_ATRB_02 on TB_LGCL_REL_ATRB (LGCL_REL_SN, TRGT_ATRB_SN);
create unique index UX_LGCL_REL_ATRB_03 on TB_LGCL_REL_ATRB (LGCL_REL_SN, SORT_ORD);
create index IX_LGCL_REL_ATRB_01 on TB_LGCL_REL_ATRB (ORGNL_ATRB_SN);
create index IX_LGCL_REL_ATRB_02 on TB_LGCL_REL_ATRB (TRGT_ATRB_SN);
alter table TB_LGCL_REL_ATRB add constraint FK_LGCL_REL_ATRB_01 foreign key (LGCL_REL_SN) references TB_LGCL_REL;
alter table TB_LGCL_REL_ATRB add constraint FK_LGCL_REL_ATRB_02 foreign key (ORGNL_ATRB_SN) references TB_LGCL_ATRB;
alter table TB_LGCL_REL_ATRB add constraint FK_LGCL_REL_ATRB_03 foreign key (TRGT_ATRB_SN) references TB_LGCL_ATRB;
comment on table TB_LGCL_REL_ATRB is '논리 관계 속성';
comment on column TB_LGCL_REL_ATRB.LGCL_REL_ATRB_SN is '논리 관계 속성 일련번호';
comment on column TB_LGCL_REL_ATRB.LGCL_REL_SN is '논리 관계 일련번호';
comment on column TB_LGCL_REL_ATRB.ORGNL_ATRB_SN is '원본 속성 일련번호';
comment on column TB_LGCL_REL_ATRB.TRGT_ATRB_SN is '대상 속성 일련번호';
comment on column TB_LGCL_REL_ATRB.SORT_ORD is '정렬 순서';
comment on column TB_LGCL_REL_ATRB.REG_DT is '등록 일시';
comment on column TB_LGCL_REL_ATRB.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_REL_ATRB.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_REL_ATRB.MDFR_ID is '수정자 아이디';

-- ---------- 예전 증분 V2 (논리 주제 영역 M-6 후속 4차, 2026-11-22 네 번째 합침) ----------
-- 예전 증분 V2 : 논리 모델 주제 영역 (M-6 후속 4차, 2026-11-20 — docs/completed/logical-subject-area.md)
-- 논리 모델 안의 업무 분류. 영역은 상위 영역(UP_SBJ_AREA_SN)으로 최대 3단계 계층을 이루고(최상위 = 1단계, 서비스가 검사),
-- 엔터티는 소속 테이블(TB_LGCL_SBJ_AREA_ENTT)로 영역 하나에만 속한다 (엔터티당 0~1건, 행이 없으면 미분류).
-- 소속을 엔터티 행에 두지 않는다 : 분류를 바꿔도 엔터티의 수정 일시(DEC-08 기준값)·승인 상태가 바뀌지 않는다.
-- 주제 영역별 ERD 배치(TB_LGCL_SBJ_AREA_LYT)는 사용자 × 영역 1건이고 모델 전체 배치(TB_LGCL_ERD_LYT)와 따로 저장한다.
-- 정합성 : 모델·영역·엔터티의 존재·자기 부모 금지·엔터티 단일 소속은 DB 가 막고, 같은 모델·같은 프로젝트·깊이·순환·같은 상위 아래 이름 중복(대소문자 무시)은
--   논리 모델 행을 잠근 뒤 서비스가 검사한다 (논리 관계의 양 끝 엔터티와 같은 방식). UX_LGCL_SBJ_AREA_01 은 대소문자가 같은 이름만, 상위가 있는 영역만 막는다
--   (NULL 상위는 서로 다른 값으로 본다 — 최상위 영역의 중복은 서비스 검사가 막는다).
-- 기존 논리 모델·엔터티는 그대로 미분류다 : 기본 영역을 만들거나 엔터티를 분류하지 않고, 기존 배치를 복제하지 않는다.
-- 예제(프로젝트 0)에 새 테이블을 단어·용어 → 물리 → 논리 → 매핑 → 관계(+ 속성 짝) → 제약조건 → 인덱스 → 업무 설명 순서로 넣는다 (예전 증분 V7 과 같은 규칙).
-- 단어 : 주제(SBJ)·영역(AREA)은 공공데이터 공통표준단어에 없어 Lexorma 기관 표준단어로 정했다 (2026-11-20 사용자 결정, 공통표준 AREA = 면적),
--   상위(UP)는 공통표준단어 그대로. 예제 주제 영역 데이터는 만들지 않는다 (D-18).

create sequence SQ_LGCL_SBJ_AREA start with 1 increment by 50;
create table TB_LGCL_SBJ_AREA (
    LGCL_SBJ_AREA_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    UP_SBJ_AREA_SN bigint,
    SBJ_AREA_NM varchar(255) not null,
    SBJ_AREA_EXPLN varchar(1000),
    SORT_ORD integer not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint CK_LGCL_SBJ_AREA_01 check (UP_SBJ_AREA_SN <> LGCL_SBJ_AREA_SN),
    constraint PK_LGCL_SBJ_AREA primary key (LGCL_SBJ_AREA_SN)
);
create sequence SQ_LGCL_SBJ_AREA_ENTT start with 1 increment by 50;
create table TB_LGCL_SBJ_AREA_ENTT (
    LGCL_SBJ_AREA_ENTT_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    LGCL_SBJ_AREA_SN bigint not null,
    LGCL_ENTT_SN bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_SBJ_AREA_ENTT primary key (LGCL_SBJ_AREA_ENTT_SN)
);
create sequence SQ_LGCL_SBJ_AREA_LYT start with 1 increment by 50;
create table TB_LGCL_SBJ_AREA_LYT (
    LGCL_SBJ_AREA_LYT_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    LGCL_SBJ_AREA_SN bigint not null,
    ACNT_SN bigint not null,
    NODE_PSTN_CN text not null,
    SCRN_X_CRDNT double precision not null,
    SCRN_Y_CRDNT double precision not null,
    SCRN_MGNF double precision not null,
    VER_NO bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_SBJ_AREA_LYT primary key (LGCL_SBJ_AREA_LYT_SN)
);
create unique index UX_LGCL_SBJ_AREA_01 on TB_LGCL_SBJ_AREA (LGCL_MDL_SN, UP_SBJ_AREA_SN, SBJ_AREA_NM);
create index IX_LGCL_SBJ_AREA_01 on TB_LGCL_SBJ_AREA (UP_SBJ_AREA_SN);
create unique index UX_LGCL_SBJ_AREA_ENTT_01 on TB_LGCL_SBJ_AREA_ENTT (LGCL_ENTT_SN);
create index IX_LGCL_SBJ_AREA_ENTT_01 on TB_LGCL_SBJ_AREA_ENTT (LGCL_SBJ_AREA_SN, LGCL_ENTT_SN);
create index IX_LGCL_SBJ_AREA_ENTT_02 on TB_LGCL_SBJ_AREA_ENTT (LGCL_MDL_SN);
create unique index UX_LGCL_SBJ_AREA_LYT_01 on TB_LGCL_SBJ_AREA_LYT (LGCL_SBJ_AREA_SN, ACNT_SN);
alter table TB_LGCL_SBJ_AREA add constraint FK_LGCL_SBJ_AREA_01 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;
alter table TB_LGCL_SBJ_AREA add constraint FK_LGCL_SBJ_AREA_02 foreign key (UP_SBJ_AREA_SN) references TB_LGCL_SBJ_AREA;
alter table TB_LGCL_SBJ_AREA_ENTT add constraint FK_LGCL_SBJ_AREA_ENTT_01 foreign key (LGCL_SBJ_AREA_SN) references TB_LGCL_SBJ_AREA;
alter table TB_LGCL_SBJ_AREA_ENTT add constraint FK_LGCL_SBJ_AREA_ENTT_02 foreign key (LGCL_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_LGCL_SBJ_AREA_ENTT add constraint FK_LGCL_SBJ_AREA_ENTT_03 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;
alter table TB_LGCL_SBJ_AREA_LYT add constraint FK_LGCL_SBJ_AREA_LYT_01 foreign key (LGCL_SBJ_AREA_SN) references TB_LGCL_SBJ_AREA;
alter table TB_LGCL_SBJ_AREA_LYT add constraint FK_LGCL_SBJ_AREA_LYT_02 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;
comment on table TB_LGCL_SBJ_AREA is '논리 주제 영역';
comment on column TB_LGCL_SBJ_AREA.LGCL_SBJ_AREA_SN is '논리 주제 영역 일련번호';
comment on column TB_LGCL_SBJ_AREA.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_SBJ_AREA.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_SBJ_AREA.UP_SBJ_AREA_SN is '상위 주제 영역 일련번호';
comment on column TB_LGCL_SBJ_AREA.SBJ_AREA_NM is '주제 영역 명';
comment on column TB_LGCL_SBJ_AREA.SBJ_AREA_EXPLN is '주제 영역 설명';
comment on column TB_LGCL_SBJ_AREA.SORT_ORD is '정렬 순서';
comment on column TB_LGCL_SBJ_AREA.REG_DT is '등록 일시';
comment on column TB_LGCL_SBJ_AREA.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_SBJ_AREA.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_SBJ_AREA.MDFR_ID is '수정자 아이디';
comment on table TB_LGCL_SBJ_AREA_ENTT is '논리 주제 영역 엔터티';
comment on column TB_LGCL_SBJ_AREA_ENTT.LGCL_SBJ_AREA_ENTT_SN is '논리 주제 영역 엔터티 일련번호';
comment on column TB_LGCL_SBJ_AREA_ENTT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_SBJ_AREA_ENTT.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_SBJ_AREA_ENTT.LGCL_SBJ_AREA_SN is '논리 주제 영역 일련번호';
comment on column TB_LGCL_SBJ_AREA_ENTT.LGCL_ENTT_SN is '논리 엔터티 일련번호';
comment on column TB_LGCL_SBJ_AREA_ENTT.REG_DT is '등록 일시';
comment on column TB_LGCL_SBJ_AREA_ENTT.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_SBJ_AREA_ENTT.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_SBJ_AREA_ENTT.MDFR_ID is '수정자 아이디';
comment on table TB_LGCL_SBJ_AREA_LYT is '논리 주제 영역 레이아웃';
comment on column TB_LGCL_SBJ_AREA_LYT.LGCL_SBJ_AREA_LYT_SN is '논리 주제 영역 레이아웃 일련번호';
comment on column TB_LGCL_SBJ_AREA_LYT.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_SBJ_AREA_LYT.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_SBJ_AREA_LYT.LGCL_SBJ_AREA_SN is '논리 주제 영역 일련번호';
comment on column TB_LGCL_SBJ_AREA_LYT.ACNT_SN is '계정 일련번호';
comment on column TB_LGCL_SBJ_AREA_LYT.NODE_PSTN_CN is '노드 위치 내용';
comment on column TB_LGCL_SBJ_AREA_LYT.SCRN_X_CRDNT is '화면 X 좌표';
comment on column TB_LGCL_SBJ_AREA_LYT.SCRN_Y_CRDNT is '화면 Y 좌표';
comment on column TB_LGCL_SBJ_AREA_LYT.SCRN_MGNF is '화면 배율';
comment on column TB_LGCL_SBJ_AREA_LYT.VER_NO is '버전 번호';
comment on column TB_LGCL_SBJ_AREA_LYT.REG_DT is '등록 일시';
comment on column TB_LGCL_SBJ_AREA_LYT.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_SBJ_AREA_LYT.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_SBJ_AREA_LYT.MDFR_ID is '수정자 아이디';


-- ---------- 예전 증분 V3 (슈퍼타입·서브타입 M-6 후속 5차, 2026-11-22 네 번째 합침) ----------
-- 예전 증분 V3 : 논리 모델 슈퍼타입·서브타입 (M-6 후속 5차, 2026-11-20 — docs/completed/logical-subtype.md)
-- 슈퍼타입 엔터티는 서브타입 그룹(TB_LGCL_SBTYP_GROUP)을 여러 개 가질 수 있고, 그룹은 서브타입(TB_LGCL_SBTYP) 2개 이상으로 이루어진다 (최소 2개는 서비스가 검사).
-- 단일 상속 : 엔터티 하나는 서브타입 행 하나에만 (UX_LGCL_SBTYP_01) — 직접 슈퍼타입 하나, 그룹 하나. 최대 깊이(루트 = 1단계, 3단계까지)·자기 상속·순환·같은 모델·
--   같은 프로젝트·그룹명 중복(같은 슈퍼타입 안, 대소문자 무시)은 논리 모델 행을 잠근 뒤 서비스가 검사한다 (주제 영역과 같은 분담).
-- 분류 정책은 그룹마다 필수값이고 DB 기본값이 없다 (사용자가 고른다) : 중복 가능 여부(DPCN_PSBLTY_YN — Y = Overlapping, N = Disjoint),
--   완전 여부(TOTL_YN — Y = Total, N = Partial). 분류 속성(CLSF_ATRB_SN, 선택)은 슈퍼타입의 유효 속성(직접 또는 조상에서 상속)의 원본 속성 ID, 분류 값(CLSF_VL)은 메타데이터 문자열이다.
-- 상속 속성·식별자는 저장하지 않는다 (원본 엔터티의 행을 조회 때 계산한다). 상속은 엔터티 행을 바꾸지 않는다 (수정 일시·승인 상태 그대로).
-- 기존 논리 모델·엔터티에는 서브타입 그룹을 만들지 않는다. 예제(프로젝트 0)에도 그룹 데이터는 만들지 않는다.
-- 예제(프로젝트 0)에 새 테이블을 단어·용어 → 물리 → 논리 → 매핑 → 관계(+ 속성 짝) → 인덱스 → 업무 설명 순서로 넣는다 (V2 와 같은 규칙).
-- 단어 (2026-11-20 사용자 결정 D-49) : 슈퍼타입(SPRTYP)·서브타입(SBTYP)·완전(TOTL)은 Lexorma 기관 표준단어 (공통표준단어에 없다 — static/excel/dataPortal.xlsx 로 확인).
--   그룹(GROUP)·분류(CLSF)·값(VL)·속성(ATRB)은 예제 사전에 이미 있다. 중복(DPCN)·가능(PSBLTY)은 공통표준단어, 중복_가능_여부(DPCN_PSBLTY_YN)는 공통표준용어 그대로
--   (같은 날 사용자 결정 — 처음 정한 DPLCT_ALWNC_YN 은 공통표준 중복 = DPCN 과 달라 바꿨다). 도메인은 컬럼이 BOOLEAN 이라 자체 도메인 여부B (필수_여부 와 같은 규칙).

create sequence SQ_LGCL_SBTYP_GROUP start with 1 increment by 50;
create table TB_LGCL_SBTYP_GROUP (
    LGCL_SBTYP_GROUP_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    SPRTYP_ENTT_SN bigint not null,
    SBTYP_GROUP_NM varchar(255) not null,
    SBTYP_GROUP_EXPLN varchar(1000),
    DPCN_PSBLTY_YN boolean not null,
    TOTL_YN boolean not null,
    CLSF_ATRB_SN bigint,
    SORT_ORD integer not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_SBTYP_GROUP primary key (LGCL_SBTYP_GROUP_SN)
);
create sequence SQ_LGCL_SBTYP start with 1 increment by 50;
create table TB_LGCL_SBTYP (
    LGCL_SBTYP_SN bigint not null,
    PRJ_SN bigint not null,
    LGCL_MDL_SN bigint not null,
    LGCL_SBTYP_GROUP_SN bigint not null,
    SBTYP_ENTT_SN bigint not null,
    CLSF_VL varchar(255),
    SORT_ORD integer not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_LGCL_SBTYP primary key (LGCL_SBTYP_SN)
);
create unique index UX_LGCL_SBTYP_GROUP_01 on TB_LGCL_SBTYP_GROUP (SPRTYP_ENTT_SN, SBTYP_GROUP_NM);
create index IX_LGCL_SBTYP_GROUP_01 on TB_LGCL_SBTYP_GROUP (LGCL_MDL_SN);
create index IX_LGCL_SBTYP_GROUP_02 on TB_LGCL_SBTYP_GROUP (CLSF_ATRB_SN);
create unique index UX_LGCL_SBTYP_01 on TB_LGCL_SBTYP (SBTYP_ENTT_SN);
create index IX_LGCL_SBTYP_01 on TB_LGCL_SBTYP (LGCL_SBTYP_GROUP_SN, SORT_ORD);
create index IX_LGCL_SBTYP_02 on TB_LGCL_SBTYP (LGCL_MDL_SN);
alter table TB_LGCL_SBTYP_GROUP add constraint FK_LGCL_SBTYP_GROUP_01 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;
alter table TB_LGCL_SBTYP_GROUP add constraint FK_LGCL_SBTYP_GROUP_02 foreign key (SPRTYP_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_LGCL_SBTYP_GROUP add constraint FK_LGCL_SBTYP_GROUP_03 foreign key (CLSF_ATRB_SN) references TB_LGCL_ATRB;
alter table TB_LGCL_SBTYP add constraint FK_LGCL_SBTYP_01 foreign key (LGCL_SBTYP_GROUP_SN) references TB_LGCL_SBTYP_GROUP;
alter table TB_LGCL_SBTYP add constraint FK_LGCL_SBTYP_02 foreign key (SBTYP_ENTT_SN) references TB_LGCL_ENTT;
alter table TB_LGCL_SBTYP add constraint FK_LGCL_SBTYP_03 foreign key (LGCL_MDL_SN) references TB_LGCL_MDL;

comment on table TB_LGCL_SBTYP_GROUP is '논리 서브타입 그룹';
comment on column TB_LGCL_SBTYP_GROUP.LGCL_SBTYP_GROUP_SN is '논리 서브타입 그룹 일련번호';
comment on column TB_LGCL_SBTYP_GROUP.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_SBTYP_GROUP.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_SBTYP_GROUP.SPRTYP_ENTT_SN is '슈퍼타입 엔터티 일련번호';
comment on column TB_LGCL_SBTYP_GROUP.SBTYP_GROUP_NM is '서브타입 그룹 명';
comment on column TB_LGCL_SBTYP_GROUP.SBTYP_GROUP_EXPLN is '서브타입 그룹 설명';
comment on column TB_LGCL_SBTYP_GROUP.DPCN_PSBLTY_YN is '중복 가능 여부';
comment on column TB_LGCL_SBTYP_GROUP.TOTL_YN is '완전 여부';
comment on column TB_LGCL_SBTYP_GROUP.CLSF_ATRB_SN is '분류 속성 일련번호';
comment on column TB_LGCL_SBTYP_GROUP.SORT_ORD is '정렬 순서';
comment on column TB_LGCL_SBTYP_GROUP.REG_DT is '등록 일시';
comment on column TB_LGCL_SBTYP_GROUP.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_SBTYP_GROUP.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_SBTYP_GROUP.MDFR_ID is '수정자 아이디';
comment on table TB_LGCL_SBTYP is '논리 서브타입';
comment on column TB_LGCL_SBTYP.LGCL_SBTYP_SN is '논리 서브타입 일련번호';
comment on column TB_LGCL_SBTYP.PRJ_SN is '프로젝트 일련번호';
comment on column TB_LGCL_SBTYP.LGCL_MDL_SN is '논리 모델 일련번호';
comment on column TB_LGCL_SBTYP.LGCL_SBTYP_GROUP_SN is '논리 서브타입 그룹 일련번호';
comment on column TB_LGCL_SBTYP.SBTYP_ENTT_SN is '서브타입 엔터티 일련번호';
comment on column TB_LGCL_SBTYP.CLSF_VL is '분류 값';
comment on column TB_LGCL_SBTYP.SORT_ORD is '정렬 순서';
comment on column TB_LGCL_SBTYP.REG_DT is '등록 일시';
comment on column TB_LGCL_SBTYP.MDFCN_DT is '수정 일시';
comment on column TB_LGCL_SBTYP.RGTR_ID is '등록자 아이디';
comment on column TB_LGCL_SBTYP.MDFR_ID is '수정자 아이디';


-- ---------- 예전 증분 V4 (상속 PK 컬럼 매핑 M-7 3차, 2026-11-22 네 번째 합침) ----------
-- 예전 증분 V4 : 서브타입 식별자 컬럼 매핑 (M-7 3차, 2026-11-20 — docs/completed/subtype-physical-transformation.md 30장, M7-D40·D43)
-- B형(엔터티마다 테이블) 물리 변환에서 서브타입 테이블의 상속 PK 컬럼 ↔ 루트 주 식별자 속성(원본 속성 ID)을 연결한다.
-- 기존 엔터티 테이블 매핑(1:1)·속성 컬럼 매핑(속성 1 : 컬럼 1, UX_ATRB_COL_MPNG_01·02)은 바꾸지 않는다 : 같은 루트 속성이 여러 서브타입 테이블의 컬럼과 연결되므로
--   원본 속성 단독 UNIQUE 는 두지 않고 (엔터티 테이블 매핑, 원본 속성) 조합과 컬럼을 각각 UNIQUE 로 둔다.
-- 컬럼이 속성 컬럼 매핑과 이 매핑에 함께 쓰이지 않는 것(교차 중복)·원본 속성이 서브타입의 유효 주 식별자인 것·컬럼이 매핑된 테이블의 것은 서비스가
--   논리 모델 행 → 테이블 행 잠금 안에서 검사한다 (DB 제약으로 표현할 수 없다). 정합성(물리 PK·타입·FK 일치)은 저장하지 않고 조회 때 계산한다.
-- 기존 데이터에서 매핑을 추정해 넣지 않는다 (이 테이블은 비어 있게 만든다). 예제(프로젝트 0)에도 매핑 행은 만들지 않는다.
-- 이름 (표준 사전 확인 2026-11-20) : 서브타입(SBTYP, V3 기관 표준단어)·식별자(IDNTFR, 기관 표준단어 — 공통표준에는 식별 IDNTF 만 있고 식별자는 없다,
--   TB_LGCL_IDNTFR 와 같은 단어)·컬럼(COL)·매핑(MPNG)·일련번호(SN) 는 예제 사전에 있다. 새 용어는 서브타입_식별자_컬럼_매핑_일련번호 하나뿐이다.
-- 예제(프로젝트 0)에 새 테이블을 용어 → 물리 → 논리 → 매핑 → 관계(+ 속성 짝) → 인덱스 → 업무 설명 순서로 넣는다 (V2·V3 와 같은 규칙).

create sequence SQ_SBTYP_IDNTFR_COL_MPNG start with 1 increment by 50;
create table TB_SBTYP_IDNTFR_COL_MPNG (
    SBTYP_IDNTFR_COL_MPNG_SN bigint not null,
    ENTT_TBL_MPNG_SN bigint not null,
    LGCL_ATRB_SN bigint not null,
    COL_SN bigint not null,
    REG_DT timestamp(6),
    MDFCN_DT timestamp(6),
    RGTR_ID varchar(255),
    MDFR_ID varchar(255),
    constraint PK_SBTYP_IDNTFR_COL_MPNG primary key (SBTYP_IDNTFR_COL_MPNG_SN)
);
create unique index UX_SBTYP_IDNTFR_COL_MPNG_01 on TB_SBTYP_IDNTFR_COL_MPNG (ENTT_TBL_MPNG_SN, LGCL_ATRB_SN);
create unique index UX_SBTYP_IDNTFR_COL_MPNG_02 on TB_SBTYP_IDNTFR_COL_MPNG (COL_SN);
create index IX_SBTYP_IDNTFR_COL_MPNG_01 on TB_SBTYP_IDNTFR_COL_MPNG (LGCL_ATRB_SN);
alter table TB_SBTYP_IDNTFR_COL_MPNG add constraint FK_SBTYP_IDNTFR_COL_MPNG_01 foreign key (ENTT_TBL_MPNG_SN) references TB_ENTT_TBL_MPNG;
alter table TB_SBTYP_IDNTFR_COL_MPNG add constraint FK_SBTYP_IDNTFR_COL_MPNG_02 foreign key (LGCL_ATRB_SN) references TB_LGCL_ATRB;
alter table TB_SBTYP_IDNTFR_COL_MPNG add constraint FK_SBTYP_IDNTFR_COL_MPNG_03 foreign key (COL_SN) references TB_COL;

comment on table TB_SBTYP_IDNTFR_COL_MPNG is '서브타입 식별자 컬럼 매핑';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.SBTYP_IDNTFR_COL_MPNG_SN is '서브타입 식별자 컬럼 매핑 일련번호';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.ENTT_TBL_MPNG_SN is '엔터티 테이블 매핑 일련번호';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.LGCL_ATRB_SN is '논리 속성 일련번호';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.COL_SN is '컬럼 일련번호';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.REG_DT is '등록 일시';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.MDFCN_DT is '수정 일시';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.RGTR_ID is '등록자 아이디';
comment on column TB_SBTYP_IDNTFR_COL_MPNG.MDFR_ID is '수정자 아이디';


-- ---------- 예전 증분 V5 (컬럼 데이터 무결성 M7-U09, 2026-11-22 네 번째 합침) ----------
-- 예전 증분 V5 : 물리 컬럼 데이터 무결성 (M-7, 2026-11-22 — docs/completed/column-name-uniqueness-m7.md, M7-U01~U07 · M7-U09 통합)
-- 미배포 상태에서 V5(컬럼명 UNIQUE)·V6(필수값)·V7(유니코드 정규화)을 하나로 합친 최종본이다 (M7-U09, 15장). V0~V4 는 그대로다.
-- 최종 구조 : TB_COL.TBL_SN · COL_NM NOT NULL, CK_COL_01 (빈 이름 = 빈 문자열 또는 ASCII 공백류 U+0020·탭·LF·VT·FF·CR 만 — 서버 ColumnNameKey.isBlankName 과 같다),
--   생성 컬럼 COL_NM_CMPR_VL varchar(765) = 앞뒤 공백(U+0020) 제거 + 로캘 중립 Unicode 확장 대문자 (ß → SS, ﬁ → FI, σ·ς → Σ — 서버 ColumnNameKey.of 와 같은 뜻),
--   UNIQUE UX_COL_01 (TBL_SN, COL_NM_CMPR_VL). 실제 컬럼명(COL_NM)은 바꾸지 않는다. 생성 컬럼은 기술 컬럼이다 (애플리케이션은 쓰지도 매핑하지도 않는다).
--   PostgreSQL : 대문자 변환에만 ICU 루트 로캘 "und-x-icu" 를 명시하고 (DB 기본 로캘과 무관 — libc·ICU·터키어 ICU 기본 로캘 DB 에서 같은 결과), 컬럼은 collate "C"
--   (UNIQUE 는 변환한 문자열의 바이트 비교, 인덱스 순서가 ICU·libc 판에 매이지 않는다). ICU 를 넣어 빌드한 PostgreSQL 이 필요하다 (공식 postgres:18.6 이미지는 ICU 76 포함).
-- 순서 : ① ICU 콜레이션 확인 ② 사전 검사 하나로 필수값 위반(부모 없음·이름 없음·빈 이름) · 새 기준 중복 · 비교 값 765자 초과를 모두 세어 처음 50개 목록과 함께
--   DDL 전에 멈춘다 (데이터를 지우거나 고치지 않는다) ③ DDL ④ 예제 메타데이터. Flyway 가 이 파일을 한 트랜잭션으로 돌려 실패하면 아무것도 남지 않는다.
-- 비교 값은 쓸 때 계산되고 ICU 판이 바뀌어도 자동으로 다시 계산되지 않는다 (문서 14.6 · 15장 운영).
-- 예제(프로젝트 0) : 도메인 값V765 · 용어 컬럼_명_비교_값 → TB_COL 컬럼 → 엔터티 속성 → 속성 매핑 → 제약조건 UX_COL_01 · CK_COL_01 → 업무 설명,
--   TB_COL.TBL_SN·COL_NM 필수. 예제 모델은 생성 컬럼을 표현하지 못한다 (예제에는 일반 컬럼으로 보이고 업무 설명에 생성 컬럼임을 적는다).
-- (예전 ② 사전 검사는 빈 새 설치에서 할 일이 없어 뺐다 — 예제 데이터는 이 제약을 지킨 채 V1 이 넣는다)

-- ① (집계로 쓴다 : 상수 cast 는 PostgreSQL 이 계획 단계에서 미리 계산해 조건과 무관하게 실패한다)
select cast('V5 컬럼 무결성 : 이 PostgreSQL 에 ICU 콜레이션 "und-x-icu" 가 없습니다 — ICU 를 포함해 빌드한 PostgreSQL 이 필요합니다' || case when count(*) = 0 then '' end as integer)
from pg_collation where collname = 'und-x-icu' and collprovider = 'i'
having count(*) = 0;

-- ③ DDL
alter table TB_COL alter column TBL_SN set not null;
alter table TB_COL alter column COL_NM set not null;
alter table TB_COL add constraint CK_COL_01 check (trim(translate(COL_NM, chr(9) || chr(10) || chr(11) || chr(12) || chr(13), '     ')) <> '');
alter table TB_COL add column COL_NM_CMPR_VL varchar(765) collate "C" generated always as (upper(trim(COL_NM) collate "und-x-icu")) stored;
alter table TB_COL add constraint UX_COL_01 unique (TBL_SN, COL_NM_CMPR_VL);
comment on column TB_COL.COL_NM_CMPR_VL is '컬럼 명 비교 값';
