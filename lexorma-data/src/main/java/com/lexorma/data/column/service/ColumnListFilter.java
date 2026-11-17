package com.lexorma.data.column.service;

import com.lexorma.data.column.domain.entity.ColumnInfo;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;

/**
 * 컬럼 관리 목록의 조회 조건 : 프로젝트 + 테이블 ID + 검색 열/검색어. 목록 조회와 등록 데이터 엑셀 다운로드(M-11)가 함께 써서 같은 대상을 본다.
 */
public final class ColumnListFilter {

    private ColumnListFilter() {
    }

    public static Specification<ColumnInfo> of(Long projectId, String searchColumn, String searchQuery, Long tableId) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (tableId != null) {
                // 테이블 ID 로 정확히 한 테이블의 컬럼만 (테이블명 검색은 부분 일치라 이름이 겹치는 테이블이 섞인다)
                predicates.add(builder.equal(root.get("tableInfo").get("id"), tableId));
            }
            if (searchQuery != null && !searchQuery.trim().isEmpty() && searchColumn != null) {
                String keyword = "%" + searchQuery + "%";
                switch (searchColumn) {
                    case "tableName" -> predicates.add(builder.like(root.get("tableName"), keyword.toUpperCase()));
                    case "columnName" -> predicates.add(builder.like(builder.upper(root.get("columnName")), keyword.toUpperCase()));
                    case "columnDesc", "dataType" -> predicates.add(builder.like(root.get(searchColumn), keyword));
                    default -> { } // 모르는 검색 컬럼은 무시
                }
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }
}
