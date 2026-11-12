package com.lexorma.core.project.repository;

import com.lexorma.core.project.domain.entity.Project;

import java.util.List;

public interface ProjectRepositoryCustom {
    List<Project> findProjectsByUsername(String username);

    /**
     * 로그인할 때와 같은 기준(활성 프로젝트 + 승인된 멤버)으로 지금도 접근할 수 있는 프로젝트인지 확인한다.
     */
    boolean isAccessibleProject(String username, Long projectId);
}
