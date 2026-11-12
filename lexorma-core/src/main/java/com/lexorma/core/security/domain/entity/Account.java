package com.lexorma.core.security.domain.entity;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.core.project.domain.entity.ProjectMember;
import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "TB_ACNT")
@Getter
@Setter
@ToString
@Builder
@AllArgsConstructor
@RequiredArgsConstructor
public class Account extends BaseEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "SQ_ACNT")
    @SequenceGenerator(name = "SQ_ACNT", sequenceName = "SQ_ACNT", allocationSize = 50)
    @Column(name = "ACNT_SN")
    private Long id;

    @Column(name = "USER_ID")
    private String username;

    @Column(name = "PSWD")
    @ToString.Exclude // 비밀번호 해시가 로그에 찍히지 않게
    private String password;

    @Column(name = "USER_NM")
    private String name;

    @Column(name = "ACTVTN_YN")
    private Boolean isActive;

    /**
     * 패스워드 변경여부 - 기본적으로 admin에서 접근했을 때 패스워드 변경을 먼저 할 수 있도록 내정보로 redirect한다
     * admin 이외의 계정에서는 true를 반환하여 원하는 위치로 이동할 수 있도록 작업해준다.
     */
    @Column(name = "PSWD_CHG_YN")
    private boolean isPasswdCheck;

    @ManyToMany(fetch = FetchType.LAZY, cascade={CascadeType.MERGE})
    @JoinTable(name = "TB_ACNT_ROLE", joinColumns = { @JoinColumn(name = "ACNT_SN") }, inverseJoinColumns = {
            @JoinColumn(name = "ROLE_SN") })
    @ToString.Exclude
    private Set<Role> userRoles = new HashSet<>();


    @OneToMany(mappedBy = "account", fetch = FetchType.LAZY)
    private List<ProjectMember> projectMember =  new ArrayList<>();

}

