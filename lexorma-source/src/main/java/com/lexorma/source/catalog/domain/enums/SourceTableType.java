package com.lexorma.source.catalog.domain.enums;

/** 원본 객체 유형. 뷰는 객체와 컬럼만 저장한다 (정의 SQL 은 수집·저장하지 않는다) */
public enum SourceTableType {
    TABLE, VIEW
}
