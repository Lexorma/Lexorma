package com.koboolean.metamora.design.rule.repository;

import com.koboolean.metamora.design.rule.domain.dto.TemplateDto;
import com.koboolean.metamora.design.rule.domain.entity.Template;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateRepository extends JpaRepository<Template, Long> {
    List<Template> findAllByProjectId(Long projectId);

    void deleteByProjectId(Long projectId);

    List<Template> findAllByProjectIdAndTemplateName(Long projectId, String templateName);

    Template findByIdAndProjectId(Long id, Long projectId);
}
