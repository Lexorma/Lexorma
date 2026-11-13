package com.lexorma.data.view.domain.entity;

import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.*;
import lombok.*;

/**
 * VIEW 가 참조하는 객체 (M-15 D-24) : 사용자가 관리하는 객체 수준 메타데이터다. 정의 SQL 을 분석해 만들지 않고, SQL 에서 실제로 쓰인다고 보장하지 않는다.
 * 내부 참조 = 같은 프로젝트의 테이블(referencedTable) 또는 VIEW(referencedView). 미확인 참조 = 두 연관이 비고 schemaName·objectName 만 있다
 * (모델에 없는 객체를 이름으로만 적어 둔 것. 존재하는 객체처럼 다루지 않는다 : DDL 순서·ERD 선에 쓰지 않는다).
 */
@Entity
@Table(name = "TB_VIEW_RFRNC")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PhysicalViewReference {

    public enum Type { TABLE, VIEW }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_VIEW_RFRNC")
    @SequenceGenerator(name = "SQ_VIEW_RFRNC", sequenceName = "SQ_VIEW_RFRNC", allocationSize = 50)
    @Column(name = "VIEW_RFRNC_SN", nullable = false, unique = true)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "VIEW_SN", nullable = false)
    private PhysicalView view;

    @Enumerated(EnumType.STRING)
    @Column(name = "RFRNC_TYPE_CD", length = 20, nullable = false)
    private Type referenceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RFRNC_TBL_SN")
    private TableInfo referencedTable;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "RFRNC_VIEW_SN")
    private PhysicalView referencedView;

    /** 미확인 참조의 스키마명 (없으면 null) */
    @Column(name = "RFRNC_SCHM_NM")
    private String schemaName;

    /** 미확인 참조의 객체명 */
    @Column(name = "RFRNC_TRGT_NM")
    private String objectName;

    public boolean isInternal() {
        return referencedTable != null || referencedView != null;
    }
}
