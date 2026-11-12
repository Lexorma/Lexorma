package com.lexorma.core.project.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.security.domain.entity.Account;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "TB_PRJ_MBR")
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProjectMember extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_PRJ_MBR")
    @SequenceGenerator(name = "SQ_PRJ_MBR", sequenceName = "SQ_PRJ_MBR", allocationSize = 50)
    @Column(name = "PRJ_MBR_SN")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ACNT_SN")
    private Account account;

    @Column(name = "ACTVTN_YN")
    private Boolean isActive;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "PRJ_SN")
    private Project project;

}
