package com.koboolean.metamora.system.project.service;

import com.koboolean.metamora.core.security.domain.dto.AccountDto;
import com.koboolean.metamora.core.project.domain.dto.ProjectDto;
import com.koboolean.metamora.core.project.domain.dto.ProjectMemberDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

public interface ProjectManageService {
    Page<ProjectMemberDto> getProjectData(Pageable pageable, String searchColumn, String searchQuery, Long projectId);

    ProjectDto getEditProjectData(Long projectId, AccountDto accountDto);

    void createProject(ProjectDto projectDto, AccountDto accountDto);
    void updateProject(ProjectDto projectDto);
    void deleteProject(Long selectedId, AccountDto accountDto);

    boolean selectLoginProject(Long projectId, String id);

    /** 멤버(ID)가 모두 이 사용자가 관리하는 프로젝트의 멤버인지 */
    boolean isManagerOfMembers(List<ProjectMemberDto> projectMemberDtos, String id);

    void saveActiveProject(List<ProjectMemberDto> projectMemberDtos, Boolean isActive);

    void deleteProjectMember(List<ProjectMemberDto> projectMemberDtos, AccountDto accountDto);

    List<AccountDto> selectProjectMember(Long selectedId, AccountDto accountDto);

    void saveProjectMember(Map<String, String> accountIds, Long projectId);
}
