package com.lexorma.data.view.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 물리 모델 VIEW (M-15 D-22) : TABLE 과 분리된 객체. 프로젝트 → 스키마 체계를 그대로 쓰고, 이름은 프로젝트 안에서 TABLE 과 같은 이름 공간이다 (대문자).
 * 정의 SQL 은 CREATE VIEW … AS 뒤의 조회 문 원문이다 (D-23, 실행하지 않는다). 출력 컬럼·참조 객체는 사용자가 관리하는 메타데이터로,
 * 정의 SQL 과 서로 자동으로 맞추지 않는다 (D-24). 승인된 VIEW 는 변경 요청({@link ViewChangeRequest})으로만 바뀐다.
 */
@Entity
@Table(name = "TB_VIEW")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalView extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_VIEW")
    @SequenceGenerator(name = "SQ_VIEW", sequenceName = "SQ_VIEW", allocationSize = 50)
    @Column(name = "VIEW_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "SCHM_SN", nullable = false)
    private SchemaInfo schema;

    @Column(name = "VIEW_NM", nullable = false)
    private String viewName;

    /** 논리명 (한글명) */
    @Column(name = "VIEW_KORN_NM")
    private String viewKorName;

    @Column(name = "VIEW_EXPLN", length = 1000)
    private String viewDescription;

    /** 조회 문 원문 (대소문자·공백·주석 그대로) */
    @Column(name = "VIEW_DFN_CN", columnDefinition = "TEXT", nullable = false)
    private String definition;

    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @Builder.Default
    @OneToMany(mappedBy = "view", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("columnOrder")
    private List<PhysicalViewColumn> columns = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "view", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PhysicalViewReference> references = new ArrayList<>();

    /** VIEW 관리 목록·상세의 등록·수정 정보 (bean getter 가 아니라 JSON 프로퍼티가 되지 않는다) */
    public Audit audit() {
        return toAudit();
    }
}
