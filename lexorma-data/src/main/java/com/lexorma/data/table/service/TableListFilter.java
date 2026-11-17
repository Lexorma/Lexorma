package com.lexorma.data.table.service;

import com.lexorma.data.table.domain.entity.TableInfo;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 테이블 관리 목록의 조회 조건 : 프로젝트 + 스키마 + 검색 열/검색어. 목록 조회와 등록 데이터 엑셀 다운로드(M-11)가 함께 써서 같은 대상을 본다.
 */
public final class TableListFilter {

    /** 검색할 수 있는 열 (그 밖의 값은 무시) */
    public static final Set<String> SEARCH_COLUMNS = Set.of("tableName", "tableDescription", "tableKorName");

    private TableListFilter() {
    }

    public static Specification<TableInfo> of(Long projectId, String searchColumn, String searchQuery, Long schemaId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (schemaId != null) {
                // 다른 프로젝트의 스키마 ID 는 projectId 조건 때문에 결과가 없다
                predicates.add(builder.equal(root.get("schema").get("id"), schemaId));
            }
            if (searchQuery != null && !searchQuery.trim().isEmpty() && searchColumn != null && SEARCH_COLUMNS.contains(searchColumn)) {
                predicates.add(builder.like(root.get(searchColumn), "%" + searchQuery + "%"));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
