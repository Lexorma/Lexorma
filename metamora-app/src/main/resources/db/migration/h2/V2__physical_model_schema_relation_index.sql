/* M-1 물리 모델 보강 : 스키마 / 관계(복합 FK) / 인덱스(복합) / 제약조건(UNIQUE·CHECK)
   - 기존 데이터는 지우거나 바꾸지 않는다. column_info 의 ref_table_name·is_unique·is_index·is_pk 는 그대로 남는다.
   - 프로젝트마다 기본 스키마(DEFAULT)를 만들고 기존 테이블을 모두 넣는다.
   - ref_table_name 중 참조 대상을 확정할 수 있는 것만 관계(REF_COLUMN)로 옮긴다. (DDL 생성기의 FK 조건과 같다)
       같은 프로젝트에 그 이름의 테이블이 정확히 1개 + 그 테이블의 PK 컬럼이 정확히 1개 + 자기 PK 를 가리키지 않음
     후보가 여럿/없음/PK 없음/복합 PK/소속 테이블 없음은 옮기지 않고 ref_table_name 만 남는다 (DDL 은 지금처럼 경고).
   - 시퀀스 값으로 id 를 채운다 (Hibernate pooled 할당과 겹치지 않는다). 데이터 이전 INSERT 는 다시 실행해도 중복되지 않게 NOT EXISTS 로 막는다. */

create sequence schema_info_seq start with 1 increment by 50;
create sequence table_relation_seq start with 1 increment by 50;
create sequence table_relation_column_seq start with 1 increment by 50;
create sequence table_index_seq start with 1 increment by 50;
create sequence table_index_column_seq start with 1 increment by 50;
create sequence table_constraint_seq start with 1 increment by 50;
create sequence table_constraint_column_seq start with 1 increment by 50;

-- ---------- 스키마 ----------

create table schema_info (
    schema_info_id bigint not null,
    project_id bigint not null,
    schema_name varchar(255) not null,
    schema_description varchar(255),
    is_default boolean not null,
    created timestamp(6),
    updated timestamp(6),
    created_by varchar(255),
    updated_by varchar(255),
    primary key (schema_info_id)
);
create unique index ux_schema_info_project_name on schema_info (project_id, schema_name);

-- 프로젝트가 지워져도 테이블은 남아 있을 수 있으므로(프로젝트 삭제 시 데이터 정리 없음) table_info 의 project_id 도 포함한다
insert into schema_info (schema_info_id, project_id, schema_name, schema_description, is_default, created, updated, created_by, updated_by)
select next value for schema_info_seq, p.project_id, 'DEFAULT', '기본 스키마', true, current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM'
from (select project_id from project union select project_id from table_info) p
where not exists (select 1 from schema_info s where s.project_id = p.project_id and s.is_default = true);

alter table table_info add column schema_info_id bigint;
update table_info set schema_info_id =
    (select s.schema_info_id from schema_info s where s.project_id = table_info.project_id and s.is_default = true)
where schema_info_id is null;
alter table table_info alter column schema_info_id set not null;
alter table table_info add constraint fk_table_info_schema foreign key (schema_info_id) references schema_info;
create index ix_table_info_schema on table_info (schema_info_id);

-- ---------- 관계 ----------

create table table_relation (
    table_relation_id bigint not null,
    project_id bigint not null,
    source_table_id bigint not null,
    target_table_id bigint not null,
    relation_name varchar(255),
    relation_type varchar(20) not null,
    relation_source varchar(20) not null,
    derived_column_id bigint,
    is_approval boolean,
    created timestamp(6),
    updated timestamp(6),
    created_by varchar(255),
    updated_by varchar(255),
    primary key (table_relation_id)
);
create table table_relation_column (
    table_relation_column_id bigint not null,
    table_relation_id bigint not null,
    source_column_id bigint not null,
    target_column_id bigint not null,
    column_order integer not null,
    primary key (table_relation_column_id)
);
-- 테이블 삭제는 컬럼이 없을 때만 가능하므로 테이블 쪽은 CASCADE, 컬럼 쪽은 서비스가 먼저 정리한다 (RESTRICT)
alter table table_relation add constraint fk_table_relation_source foreign key (source_table_id) references table_info on delete cascade;
alter table table_relation add constraint fk_table_relation_target foreign key (target_table_id) references table_info on delete cascade;
alter table table_relation_column add constraint fk_table_relation_column_relation foreign key (table_relation_id) references table_relation on delete cascade;
alter table table_relation_column add constraint fk_table_relation_column_source foreign key (source_column_id) references column_info;
alter table table_relation_column add constraint fk_table_relation_column_target foreign key (target_column_id) references column_info;
create index ix_table_relation_project on table_relation (project_id);
create index ix_table_relation_source on table_relation (source_table_id);
create index ix_table_relation_target on table_relation (target_table_id);
create unique index ux_table_relation_derived on table_relation (derived_column_id);
create unique index ux_table_relation_column_order on table_relation_column (table_relation_id, column_order);
create index ix_table_relation_column_source on table_relation_column (source_column_id);
create index ix_table_relation_column_target on table_relation_column (target_column_id);

-- ---------- 인덱스 ----------

create table table_index (
    table_index_id bigint not null,
    project_id bigint not null,
    table_info_id bigint not null,
    index_name varchar(255) not null,
    index_type varchar(255),
    is_unique boolean not null,
    is_approval boolean,
    created timestamp(6),
    updated timestamp(6),
    created_by varchar(255),
    updated_by varchar(255),
    primary key (table_index_id)
);
create table table_index_column (
    table_index_column_id bigint not null,
    table_index_id bigint not null,
    column_info_id bigint not null,
    column_order integer not null,
    primary key (table_index_column_id)
);
alter table table_index add constraint fk_table_index_table foreign key (table_info_id) references table_info on delete cascade;
alter table table_index_column add constraint fk_table_index_column_index foreign key (table_index_id) references table_index on delete cascade;
alter table table_index_column add constraint fk_table_index_column_column foreign key (column_info_id) references column_info;
create index ix_table_index_table on table_index (table_info_id);
create unique index ux_table_index_column_order on table_index_column (table_index_id, column_order);
create index ix_table_index_column_column on table_index_column (column_info_id);

-- ---------- 제약조건 (UNIQUE / CHECK) ----------

create table table_constraint (
    table_constraint_id bigint not null,
    project_id bigint not null,
    table_info_id bigint not null,
    constraint_name varchar(255) not null,
    constraint_type varchar(20) not null,
    check_expression varchar(1000),
    is_approval boolean,
    created timestamp(6),
    updated timestamp(6),
    created_by varchar(255),
    updated_by varchar(255),
    primary key (table_constraint_id)
);
create table table_constraint_column (
    table_constraint_column_id bigint not null,
    table_constraint_id bigint not null,
    column_info_id bigint not null,
    column_order integer not null,
    primary key (table_constraint_column_id)
);
alter table table_constraint add constraint fk_table_constraint_table foreign key (table_info_id) references table_info on delete cascade;
alter table table_constraint_column add constraint fk_table_constraint_column_constraint foreign key (table_constraint_id) references table_constraint on delete cascade;
alter table table_constraint_column add constraint fk_table_constraint_column_column foreign key (column_info_id) references column_info;
create index ix_table_constraint_table on table_constraint (table_info_id);
create unique index ux_table_constraint_column_order on table_constraint_column (table_constraint_id, column_order);
create index ix_table_constraint_column_column on table_constraint_column (column_info_id);

-- ---------- ref_table_name → 관계(REF_COLUMN) 이전 ----------

insert into table_relation (table_relation_id, project_id, source_table_id, target_table_id, relation_name, relation_type,
                            relation_source, derived_column_id, is_approval, created, updated, created_by, updated_by)
select next value for table_relation_seq, c.project_id, c.table_info_id, t.table_info_id,
       'FK_' || s.table_name || '_' || c.column_name, 'FK', 'REF_COLUMN', c.column_info_id, true,
       current_timestamp, current_timestamp, 'SYSTEM', 'SYSTEM'
from column_info c
join table_info s on s.table_info_id = c.table_info_id
join table_info t on t.project_id = c.project_id and t.table_name = upper(trim(c.ref_table_name))
where trim(c.ref_table_name) <> ''
  and (select count(*) from table_info t2 where t2.project_id = c.project_id and t2.table_name = upper(trim(c.ref_table_name))) = 1
  and (select count(*) from column_info p where p.table_info_id = t.table_info_id and p.is_pk = true) = 1
  and not exists (select 1 from column_info p where p.table_info_id = t.table_info_id and p.is_pk = true and p.column_info_id = c.column_info_id)
  and not exists (select 1 from table_relation r where r.derived_column_id = c.column_info_id);

insert into table_relation_column (table_relation_column_id, table_relation_id, source_column_id, target_column_id, column_order)
select next value for table_relation_column_seq, r.table_relation_id, r.derived_column_id, p.column_info_id, 1
from table_relation r
join column_info p on p.table_info_id = r.target_table_id and p.is_pk = true
where r.relation_source = 'REF_COLUMN'
  and not exists (select 1 from table_relation_column rc where rc.table_relation_id = r.table_relation_id);
