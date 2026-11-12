package com.lexorma.system.user.service.impl;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.domain.entity.Role;
import com.lexorma.core.security.repository.RoleRepository;
import com.lexorma.core.security.repository.UserRepository;
import com.lexorma.system.user.service.UserManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserManageServiceImpl implements UserManageService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectProvider<FindByIndexNameSessionRepository<? extends Session>> sessionRepository;

    @Override
    public List<String> getUserRoles() {
        return roleRepository.findAll().stream()
                .filter(role -> !role.getRoleName().equals("ROLE_ANONYMOUS"))
                .map(Role::getRoleName).toList();
    }

    @Override
    public Page<AccountDto> selectUserData(Pageable pageable, AccountDto accountDto, String searchQuery, String searchColumn) {
        return userRepository.findAllByIsActive(true, pageable).map(AccountDto::fromEntity);
    }

    @Override
    @Transactional
    public void saveUser(List<AccountDto> accountDtos) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        accountDtos.forEach(accountDto -> {

            Account account = userRepository.findById(Long.parseLong(accountDto.getId())).orElse(null);

            if(account != null){
                Role role = account.getUserRoles().stream().findFirst().get();

                // 권한이 변경된것을 확인하였으므로, 해당 권한으로 UPDATE한다.
                if(!role.getRoleName().equals(accountDto.getRole())){

                    if(accountDto.getId().equals("0")){
                        // admin 계정은 권한 변경이 불가능하다.
                        throw new CustomException(ErrorCode.MANAGER_NON_DELETED);
                    }

                    // 마지막 활성 ADMIN 은 강등할 수 없다 : 복구 로그인을 할 계정이 없어진다 (삭제와 같은 기준)
                    if(role.getRoleName().equals("ROLE_ADMIN") && activeAccountCount(role) <= 1){
                        throw new CustomException(ErrorCode.MANAGER_NON_DELETED);
                    }

                    Role byRoleName = roleRepository.findByRoleName(accountDto.getRole());
                    account.setUserRoles(Set.of(byRoleName));
                    expireSessions(account.getUsername());
                }
            }
        });
    }

    @Override
    @Transactional
    public void deleteUser(List<AccountDto> accountDtos) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        Role adminRole = roleRepository.findByRoleName("ROLE_ADMIN");

        accountDtos.forEach(accountDto -> {
            Account account = userRepository.findById(Long.parseLong(accountDto.getId()))
                    .orElse(null);

            if(account != null){
                boolean isAdmin = account.getUserRoles().contains(adminRole);

                if (isAdmin) {
                    if (activeAccountCount(adminRole) <= 1) {
                        throw new CustomException(ErrorCode.MANAGER_NON_DELETED);
                    }
                }

                account.setIsActive(false); // soft delete
                expireSessions(account.getUsername());
            }
        });
    }

    @Override
    @Transactional
    public void saveUserPassword(AccountDto accountDto) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        Account account = userRepository.findById(Long.parseLong(accountDto.getId())).orElse(null);

        if(account != null){
            account.setPassword(passwordEncoder.encode(accountDto.getUsername()));
            account.setPasswdCheck(false);
        }
    }

    private static long activeAccountCount(Role role) {
        return role.getAccounts().stream()
                .filter(Account::getIsActive)
                .distinct()
                .count();
    }

    /**
     * 역할은 로그인할 때 세션에 담기므로, 역할이 바뀌거나 삭제된 사용자의 세션을 끊어 예전 역할로 계속 요청하지 못하게 한다.
     * 그 사용자의 다음 요청은 401 → 다시 로그인하면 새 역할이 적용된다.
     */
    private void expireSessions(String username) {
        sessionRepository.ifAvailable(repository -> repository.findByPrincipalName(username).keySet().forEach(repository::deleteById));
    }
}
