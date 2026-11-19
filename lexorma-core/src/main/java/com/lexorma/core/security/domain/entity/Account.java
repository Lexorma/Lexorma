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
     * 패스워드 변경여부 - 관리자가 비밀번호를 초기화한 계정은 false 이고, 바꾸기 전에는 로그인할 수 없다 (PASSWORD_CHANGE_REQUIRED).
     * 회원가입·최초 관리자 계정은 true 로 만든다.
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

