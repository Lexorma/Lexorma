package com.koboolean.metamora.core.user.service;

import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.project.domain.dto.ProjectDto;
import com.koboolean.metamora.core.project.domain.dto.ProjectMemberDto;

import java.util.List;

public interface UserService {
    AccountDto setName(AccountDto accountDto);

    String getRoleName(String authentication);

    void createUser(AccountDto accountDto);

    List<ProjectDto> selectProject(String username);

    List<ProjectDto> selectAllProjects();

    ProjectDto selectProjects(AccountDto accountDto);

    List<ProjectDto> selectAllProjectsIsActive();

    List<AccountDto> getAccountList();

    String getProjectName(Long projectId);

    ProjectMemberDto getProjectRoleName(Long projectId, String username);

    List<ProjectDto> selectAllProjectsByUsername(AccountDto accountDto);

    List<ProjectDto> selectAllProjectsByUsernameProjectManager(AccountDto accountDto);

}
