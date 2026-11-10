package com.koboolean.metamora.core.security.domain.entity;

import com.koboolean.metamora.common.jpa.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "TB_ROLE")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Role extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_ROLE")
    @SequenceGenerator(name = "SQ_ROLE", sequenceName = "SQ_ROLE", allocationSize = 50)
    @Column(name = "ROLE_SN")
    private Long id;

    @Column(name = "ROLE_NM")
    private String roleName;

    @Column(name = "ROLE_EXPLN")
    private String roleDesc;

    @Column(name = "EXPR_YN")
    private String isExpression;

    @ManyToMany(fetch = FetchType.LAZY, mappedBy = "userRoles", cascade = CascadeType.ALL)
    private Set<Account> accounts = new HashSet<>();
}
