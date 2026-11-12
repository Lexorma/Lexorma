package com.lexorma.system.project.service.impl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.project.domain.event.ProjectDeletedEvent;
import com.lexorma.core.project.domain.dto.ProjectDto;
import com.lexorma.core.project.domain.dto.ProjectMemberDto;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.system.project.service.ProjectManageService;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.util.AuthUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectManageServiceImpl implements ProjectManageService {

    private static final Logger log = LoggerFactory.getLogger(ProjectManageServiceImpl.class);

    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectRepository projectRepository;
    private final AccountRepository accountRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public Page<ProjectMemberDto> getProjectData(Pageable pageable, String searchColumn, String searchQuery, Long projectId) {
        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            // 검색어가 없을 경우 전체 조회
            Page<ProjectMember> allByProjectId = projectMemberRepository.findAllByProject_Id(pageable, projectId);
            return allByProjectId.map(ProjectMemberDto::fromEntity);
        }

        return projectMemberRepository.findAllByIsActiveAndProject_id(pageable, searchQuery.equals("Y"),projectId).map(ProjectMemberDto::fromEntity);
    }

    @Override
    public ProjectDto getEditProjectData(Long projectId, AccountDto accountDto) {
        return projectRepository.findById(projectId).map(m -> ProjectDto.fromEntity(m, accountDto)).orElse(null);
    }

    @Override
    @Transactional
    public void createProject(ProjectDto projectDto, AccountDto accountDto) {

        Account account = accountRepository.findById(Long.parseLong(accountDto.getId())).orElse(null);

        Project project = Project.builder()
                .projectName(projectDto.getProjectName())
                .isActive(projectDto.getIsActive())
                .account(account)
                .isAutoActive(projectDto.getIsAutoActive())
                .isDicAbbrUsed(projectDto.getIsDicAbbrUsed())
                .tablePrefix(Project.normalizeTablePrefix(projectDto.getTablePrefix()))
                .dbType(DbType.fromOrDefault(projectDto.getDbType()))
                .build();

        ProjectMember projectMember = ProjectMember.builder()
                .project(project)
                .isActive(true)
                .account(account)
                .build();

        projectMemberRepository.save(projectMember);

        project.setProjectMember(List.of(projectMember));

        projectRepository.save(project);
    }

    @Override
    @Transactional
    public void updateProject(ProjectDto projectDto, boolean confirmLastActive) {
        Project project = projectRepository.findById(projectDto.getId()).orElse(null);

        if (Boolean.TRUE.equals(project.getIsActive()) && !Boolean.TRUE.equals(projectDto.getIsActive())) {
            checkLastActiveProject(project, confirmLastActive);
        }

        Account account = accountRepository.findById(projectDto.getProjectManagerId()).orElse(null);

        project.setProjectName(projectDto.getProjectName());
        project.setAccount(account);
        project.setIsActive(projectDto.getIsActive());
        project.setIsAutoActive(projectDto.getIsAutoActive());
        project.setIsDicAbbrUsed(projectDto.getIsDicAbbrUsed());
        project.setTablePrefix(Project.normalizeTablePrefix(projectDto.getTablePrefix()));
        project.setDbType(DbType.fromOrDefault(projectDto.getDbType()));

    }

    /**
     * 마지막 활성 프로젝트 비활성화 : 금지하지는 않지만 (복구 로그인으로 되돌릴 수 있다) 확인을 받은 요청만 진행하고,
     * 복구 로그인을 할 수 있는 활성 ADMIN 이 없으면 영구히 잠기므로 막는다.
     */
    private void checkLastActiveProject(Project project, boolean confirmLastActive) {
        boolean last = projectRepository.findActiveForUpdate().stream().allMatch(active -> active.getId().equals(project.getId()));
        if (!last) return;

        if (!accountRepository.existsByIsActiveTrueAndUserRoles_RoleName("ROLE_ADMIN")) {
            throw new CustomException(ErrorCode.NO_RECOVERY_ADMIN);
        }
        if (!confirmLastActive) {
            throw new CustomException(ErrorCode.LAST_ACTIVE_PROJECT);
        }
        log.warn("마지막 활성 프로젝트 비활성화 : projectId={}, projectName={}", project.getId(), project.getProjectName());
    }

    @Override
    @Transactional
    public void deleteProject(Long selectedId, AccountDto accountDto) {
        Project project = projectRepository.findById(selectedId).orElse(null);

        if(project == null){
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED, "삭제를 할 수 없습니다.");
        }

        if(project.getProjectMember().size() > 1){
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED, "프로젝트 관리자 외의 멤버가 존재하여 삭제가 불가능합니다.");
        }

        if(!project.getAccount().getId().equals(Long.valueOf(accountDto.getId()))){
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED, "프로젝트 관리자 외는 삭제가 불가능합니다.");
        }

        if(project.getId().equals(0L)){
            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED, "기본 프로젝트는 삭제가 불가능합니다.");
        }

        eventPublisher.publishEvent(new ProjectDeletedEvent(project.getId()));
        projectMemberRepository.deleteAll(project.getProjectMember());

        projectRepository.delete(project);
    }

    @Override
    public boolean selectLoginProject(Long projectId, String id) {
        Project project = projectRepository.findById(projectId).orElse(null);

        if(project != null){
            return project.getAccount().getId().equals(Long.parseLong(id));
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isManagerOfMembers(List<ProjectMemberDto> projectMemberDtos, String id) {
        return projectMemberDtos.stream().allMatch(dto -> dto.getId() != null && projectMemberRepository.findById(dto.getId())
                .map(member -> member.getProject().getAccount().getId().equals(Long.parseLong(id)))
                .orElse(false));
    }

    @Override
    @Transactional
    public void saveActiveProject(List<ProjectMemberDto> projectMemberDtos, Boolean isActive) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        for(ProjectMemberDto projectMemberDto : projectMemberDtos){
            ProjectMember projectMember = projectMemberRepository.findById(projectMemberDto.getId()).orElse(null);

            if(projectMember != null){
                projectMember.setIsActive(isActive);
            }
        }
    }

    @Override
    @Transactional
    public void deleteProjectMember(List<ProjectMemberDto> projectMemberDtos, AccountDto accountDto) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        for(ProjectMemberDto projectMemberDto : projectMemberDtos){
            ProjectMember projectMember = projectMemberRepository.findById(projectMemberDto.getId()).orElse(null);

            if(projectMember != null){
                // 프로젝트의 관리자의 경우에는 삭제가 불가능하다.
                if(projectMember.getProject().getAccount().getId().equals(projectMemberDto.getAccountId())){
                    throw new CustomException(ErrorCode.MANAGER_NON_DELETED);
                }

                projectMemberRepository.delete(projectMember);
            }
        }
    }

    @Override
    public List<AccountDto> selectProjectMember(Long selectedId, AccountDto accountDto) {

        List<ProjectMember> projectMembers = projectMemberRepository.findAllByProject_Id(selectedId);

        Set<Long> memberAccountIds = projectMembers.stream()
                .map(pm -> pm.getAccount().getId())
                .collect(Collectors.toSet());

        List<AccountDto> allAccount = accountRepository.findAll().stream().map(AccountDto::fromEntity).toList();

        return allAccount.stream()
                .filter(account -> !memberAccountIds.contains(Long.valueOf(account.getId())))
                .toList();
    }

    @Override
    @Transactional
    public void saveProjectMember(Map<String, String> accountIds, Long projectId) {
//        if(!AuthUtil.isApprovalAvailable()){
//            throw new CustomException(ErrorCode.DATA_CANNOT_BE_DELETED);
//        }

        Project project = projectRepository.findById(projectId).orElse(null);

        Account account = accountRepository.findById(Long.valueOf(accountIds.get("accountId"))).orElse(null);

        ProjectMember projectMember = ProjectMember.builder()
                .account(account)
                .project(project)
                .isActive(true)
                .build();

        if(project != null) project.getProjectMember().add(projectMember);

        projectMemberRepository.save(projectMember);
    }
}
