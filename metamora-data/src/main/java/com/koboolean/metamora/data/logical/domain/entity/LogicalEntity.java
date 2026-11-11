package com.koboolean.metamora.data.logical.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/** 논리 엔터티 (업무 관점의 데이터 집합). 속성·식별자는 엔터티에 딸린 데이터라 엔터티와 함께 지워진다 */
@Entity
@Table(name = "TB_LGCL_ENTT")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LogicalEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_LGCL_ENTT")
    @SequenceGenerator(name = "SQ_LGCL_ENTT", sequenceName = "SQ_LGCL_ENTT", allocationSize = 50)
    @Column(name = "LGCL_ENTT_SN", nullable = false, unique = true)
    private Long id;

    @Column(name = "PRJ_SN", nullable = false)
    private Long projectId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "LGCL_MDL_SN", nullable = false)
    private LogicalModel model;

    @Column(name = "ENTT_NM", nullable = false)
    private String entityName;

    @Column(name = "ENTT_EXPLN", length = 1000)
    private String entityDescription;

    /** 승인된 엔터티는 엔터티·속성·식별자를 바꿀 수 없다 (승인 취소 후 변경) */
    @Column(name = "APRV_YN")
    private Boolean isApproval;

    @Builder.Default
    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<LogicalIdentifier> identifiers = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "entity", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder, id")
    private List<LogicalAttribute> attributes = new ArrayList<>();
}
