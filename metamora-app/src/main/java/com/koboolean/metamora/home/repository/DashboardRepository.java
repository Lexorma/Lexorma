package com.koboolean.metamora.home.repository;

import com.koboolean.metamora.core.project.domain.entity.Project;
import com.koboolean.metamora.home.repository.impl.DashboardRepositoryImpl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DashboardRepository extends JpaRepository<Project, Long>, DashboardRepositoryCustom {
}
