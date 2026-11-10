package com.koboolean.metamora.data.schema.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

/**
 * 프로젝트 하위 스키마 (프로젝트 → 스키마 → 테이블 → 컬럼). 내부 물리 모델의 소속 단위이며 원본 DB 카탈로그(AI-1)와는 별도다.
 * - 프로젝트마다 기본 스키마 1개 : 스키마를 고르지 않은 테이블이 속하고, DDL 에 스키마명을 붙이지 않는다 (접속 계정의 기본 스키마)
 * - 스키마명은 프로젝트 안에서 대소문자 무관 중복 불가
 */
@Entity
@Table(name = "schema_info")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchemaInfo extends BaseEntity {

    /** V2 마이그레이션이 기존 프로젝트마다 만드는 기본 스키마의 이름 (DBMS 마다 기본 스키마가 달라 실제 이름 대신 쓴다) */
    public static final String DEFAULT_NAME = "DEFAULT";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "schema_info_seq")
    @SequenceGenerator(name = "schema_info_seq", sequenceName = "schema_info_seq", allocationSize = 50)
    @Column(name = "schema_info_id", nullable = false, unique = true)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private String schemaName;

    @Column
    private String schemaDescription;

    @Column(nullable = false)
    private Boolean isDefault;
}
