package com.lexorma.source.collection.domain.enums;

/**
 * 버전·변경 이력의 변경 유형. BASE 는 버전에만 쓴다 : 처음 수집한 이름 공간은 기준 상태라 변경 이력을 남기지 않는다.
 * REMOVED 버전은 삭제 표시(Tombstone)다 : 그 시점에 없던 것으로 본다.
 */
public enum SourceChangeType {
    BASE, ADDED, MODIFIED, REMOVED
}
