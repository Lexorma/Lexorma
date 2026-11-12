package com.lexorma.home.repository;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.home.repository.impl.DashboardRepositoryImpl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DashboardRepository extends JpaRepository<Project, Long>, DashboardRepositoryCustom {
}
