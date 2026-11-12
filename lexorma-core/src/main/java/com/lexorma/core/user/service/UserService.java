package com.lexorma.core.user.service;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.project.domain.dto.ProjectDto;

import java.util.List;

public interface UserService {
    AccountDto setName(AccountDto accountDto);

    String getRoleName(String authentication);

    void createUser(AccountDto accountDto);

    List<ProjectDto> selectProject(String username);

    List<ProjectDto> selectAllProjects();

    ProjectDto selectProjects(AccountDto accountDto);

    List<ProjectDto> selectAllProjectsIsActive();

    List<ProjectDto> selectAllProjectsByUsername(AccountDto accountDto);

    List<ProjectDto> selectAllProjectsByUsernameProjectManager(AccountDto accountDto);

}
