package com.lexorma.data.table.domain.entity;

import com.lexorma.data.physical.sync.ReferenceSyncListener;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import jakarta.persistence.*;
import org.hibernate.annotations.DynamicUpdate;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/*
 * 바뀐 칸만 UPDATE 한다 (AI-5) : 행을 잠그지 않고 읽은 다른 쓰기(일괄 편집·승인 처리·이름 변경 등)가 늦게 커밋해도 그 쓰기가 바꾸지 않은 칸
 * — 특히 AI 제안 승인이 쓴 업무 설명(TASK_EXPLN) — 을 읽은 때의 값으로 되돌리지 않는다. 같은 칸을 두 쪽이 고치는 충돌은 막지 못한다 (업무 설명은 BizDescriptions 비교·행 잠금)
 */
@DynamicUpdate
@Entity
@EntityListeners(ReferenceSyncListener.class) // 저장·수정·삭제 시 참조 관계 동기화 대상으로 표시
@Table(name = "TB_TBL")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableInfo extends BaseEntity {

    @Id
    // 시퀀스(pooled, 50개씩 할당) : IDENTITY 는 JDBC 배치 INSERT 가 꺼지므로 사용하지 않는다 (V6 마이그레이션)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_TBL")
    @SequenceGenerator(name = "SQ_TBL", sequenceName = "SQ_TBL", allocationSize = 50)
    @Column(name = "TBL_SN", nullable = false, unique = true)
    private Long id;

    /** 프로젝트 ID */
    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    /** 테이블명: ex_table */
    @Column(name = "TBL_NM", nullable = false)
    private String tableName;

    /** 테이블 설명 */
    @Column(name = "TBL_EXPLN")
    private String tableDescription;

    /** 원본 COMMENT (M-4e 가져오기, V3). 원본 DB 에서 가져온 설명이며 업무 설명(tableDescription)과 따로 둔다 */
    @Column(name = "SRC_ANTN_CN", columnDefinition = "TEXT")
    private String sourceComment;

    /**
     * 업무 설명 (AI-5, V10) : 담당자가 확정한 업무 설명. 테이블 설명(tableDescription, DDL COMMENT 로 나간다)·원본 COMMENT 와 따로 둔다.
     * 최신화·가져오기는 바꾸지 않는다. AI 제안은 승인으로만 여기에 반영된다 (객체 승인 상태와 무관 — docs/ai-dictionary-draft-ai5.md)
     */
    @Column(name = "TASK_EXPLN", columnDefinition = "TEXT")
    private String bizDescription;

    /** 테이블 한글명: 사용 내역 관리 (표준 단어 기준 테이블명 생성에 사용한 단어) */
    @Column(name = "TBL_KORN_NM")
    private String tableKorName;

    /** 마스터 여부 */
    @Column(name = "MSTR_TBL_YN")
    private Boolean isMasterTable;

    /** 정렬 순서 */
    @Column(name = "SORT_ORD")
    private Integer sortOrder;

    /** 테이블 승인 여부 */
    @Column(name = "APRV_YN")
    private Boolean isApproval;

    /** 소속 스키마 (V2). 저장 시 지정하지 않으면 프로젝트 기본 스키마 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SCHM_SN", nullable = false)
    private SchemaInfo schema;

    @OneToMany(mappedBy = "tableInfo", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ColumnInfo> columns = new ArrayList<>();

    /** 등록·수정 정보 (AI 근거 검증의 버전 : 마지막 수정 일시. bean getter 가 아니라 JSON 프로퍼티가 되지 않는다) */
    public Audit audit() {
        return toAudit();
    }
}
