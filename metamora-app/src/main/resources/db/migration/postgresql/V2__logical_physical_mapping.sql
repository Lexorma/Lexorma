-- M-7 1차 : 논리·물리 모델 매핑 (엔터티 ↔ 테이블, 속성 ↔ 컬럼). 스키마만 추가하고 기존 데이터는 바꾸지 않는다.
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
