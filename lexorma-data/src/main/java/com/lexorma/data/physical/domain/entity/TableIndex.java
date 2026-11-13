package com.lexorma.data.physical.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 물리 모델 인덱스의 단일 저장소 (M-14 D-19). 목록·ERD·DDL·이력은 모두 이 행을 본다.
 * 컬럼의 인덱스 여부(isIndex)는 단일 컬럼 인덱스의 약식 입력으로 남고, 출처 COLUMN 인덱스로 자동 동기화된다
 * (참조 테이블명 → REF_COLUMN 관계와 같은 방식, {@code PhysicalModelService.syncReferenceRelations}).
 */
@Entity
@Table(name = "TB_TBL_IDX")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableIndex extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL_IDX")
    @SequenceGenerator(name = "SQ_TBL_IDX", sequenceName = "SQ_TBL_IDX", allocationSize = 50)
    @Column(name = "IDX_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "TBL_SN", nullable = false)
    private TableInfo tableInfo;

    @Column(name = "IDX_NM", nullable = false)
    private String indexName;

    /** USER : 직접 등록 / COLUMN : 컬럼의 인덱스 여부에서 만든 단일 컬럼 인덱스 (이름·승인 상태가 컬럼을 따른다, 직접 수정·삭제 불가) */
    public enum Source { USER, COLUMN }

    /** 인덱스 방식 (예: BTREE, GIN, BITMAP). 대문자로 저장하고 DDL 은 대상 DBMS 문법으로 낸다 (지원하지 않으면 주석 + 경고) */
    @Column(name = "IDX_TYPE_NM")
    private String indexType;

    @Column(name = "UNQ_YN", nullable = false)
    private Boolean isUnique;

    @Enumerated(EnumType.STRING)
    @Column(name = "IDX_SRC_CD", length = 20, nullable = false)
    @Builder.Default
    private Source indexSource = Source.USER;

    /** COLUMN 인덱스를 만든 컬럼 (컬럼당 1개) */
    @Column(name = "DRVN_COL_SN")
    private Long derivedColumnId;

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    /** 부분 인덱스 조건 (WHERE 뒤의 식, M-14 2차). 없으면 null */
    @Column(name = "CND_CN", columnDefinition = "TEXT")
    private String condition;

    @Builder.Default
    @OneToMany(mappedBy = "tableIndex", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<TableIndexColumn> columns = new ArrayList<>();

    /** 검색 키 (INCLUDE 제외, 순서대로) */
    public List<TableIndexColumn> keyMembers() {
        return columns.stream().filter(c -> !c.isInclude()).toList();
    }

    /** INCLUDE 컬럼 (순서대로) */
    public List<TableIndexColumn> includeMembers() {
        return columns.stream().filter(TableIndexColumn::isInclude).toList();
    }

    /** 예전 인덱스 표현으로 충분한가 : 오름차순 컬럼 키만, 조건·INCLUDE 없음 (중복 판정·DDL 이 예전 규칙을 그대로 쓴다) */
    public boolean isPlain() {
        return condition == null && columns.stream().allMatch(c -> c.getColumn() != null && c.getSort() == null);
    }

    /** 인덱스 관리 목록·상세의 등록·수정 정보 (bean getter 가 아니라 JSON 프로퍼티가 되지 않는다) */
    public Audit audit() {
        return toAudit();
    }
}
