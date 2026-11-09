package com.koboolean.metamora.core.project.repository.impl;

import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.core.project.domain.entity.QProject;
import com.koboolean.metamora.core.project.domain.entity.QProjectMember;
import com.koboolean.metamora.core.project.repository.ProjectRepositoryCustom;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class ProjectRepositoryImpl implements ProjectRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public List<Project> findProjectsByUsername(String username) {
        QProject project = QProject.project;
        QProjectMember projectMember = QProjectMember.projectMember;

        return queryFactory
                .selectFrom(project)
                .join(projectMember).on(project.id.eq(projectMember.project.id))
                .where(projectMember.account.username.eq(username))
                .where(project.isActive.eq(true))
                .where(projectMember.isActive.eq(true))
                .fetch();
    }

    @Override
    public boolean isAccessibleProject(String username, Long projectId) {
        QProject project = QProject.project;
        QProjectMember projectMember = QProjectMember.projectMember;

        return queryFactory
                .selectOne()
                .from(project)
                .join(projectMember).on(project.id.eq(projectMember.project.id))
                .where(project.id.eq(projectId))
                .where(projectMember.account.username.eq(username))
                .where(project.isActive.eq(true))
                .where(projectMember.isActive.eq(true))
                .fetchFirst() != null;
    }
}
