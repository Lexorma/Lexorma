package com.koboolean.metamora.core.security.domain.dto;

import com.koboolean.metamora.core.security.domain.entity.Account;
import com.koboolean.metamora.core.security.domain.entity.Role;
import lombok.*;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Accessors(chain = true)
@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AccountDto implements Serializable {

    // 로그인 사용자(Principal)는 SecurityContext 와 함께 세션 DB(SPRING_SESSION_ATTRIBUTES)에 직렬화되어 저장된다
    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String username;
    private String name;
    private String password;
    private String currentPassword;
    private List<String> roles;
    private String role;
    private String roleName;
    private Long projectId;
    private boolean isPasswdCheck;
    private String resetButton;
    private Boolean isActive;

    /**
     * Security 가 Principal 이름으로 쓰는 값 (Authentication#getName, 세션 DB 의 PRINCIPAL_NAME, 동시 세션 제한)
     * 객체 전체가 아니라 로그인 아이디로 고정한다.
     */
    @Override
    public String toString() {
        return username;
    }

    public static AccountDto fromEntity(Account account) {

        return AccountDto.builder()
                .id(String.valueOf(account.getId()))
                .username(account.getUsername())
                .password(null)
                .name(account.getName())
                .roles(account.getUserRoles().stream().map(Role::getRoleName).collect(Collectors.toList()))
                .role(account.getUserRoles().stream().findFirst().get().getRoleName())
                .roleName(account.getUserRoles().stream().findFirst().get().getRoleDesc())
                .resetButton("초기화")
                .build();
    }

}
