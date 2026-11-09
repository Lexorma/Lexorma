package com.koboolean.metamora.core.project.repository;

import com.koboolean.metamora.core.project.domain.entity.Project;

import java.util.List;

public interface ProjectRepositoryCustom {
    List<Project> findProjectsByUsername(String username);
}
