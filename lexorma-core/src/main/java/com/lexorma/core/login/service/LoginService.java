package com.lexorma.core.login.service;


import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.project.domain.dto.ProjectDto;

import java.util.List;
import java.util.Map;

public interface LoginService {

    List<ProjectDto> selectLoginProjects(String username, String password);

    void changeInitialPassword(String username, String password, String newPassword);

    void updatePwd(AccountDto accountDto, AccountDto accountData);

    void updateName(AccountDto accountDto, String name);

    List<Map<String, Object>> selectRecoveryProjects();

    void activateProject(Long projectId, String username);
}
