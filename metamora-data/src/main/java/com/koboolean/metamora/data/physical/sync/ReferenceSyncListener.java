package com.koboolean.metamora.data.physical.sync;

import com.koboolean.metamora.data.column.domain.entity.ColumnInfo;
import com.koboolean.metamora.data.table.domain.entity.TableInfo;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * 테이블·컬럼이 저장·수정·삭제되면(flush 시점) 그 테이블을 "참조 관계를 다시 맞출 대상" 으로 표시한다.
 * 실제 동기화는 {@link ReferenceSyncBoundary} 가 같은 트랜잭션의 커밋 직전에 한다.
 * 저장 경로(화면·일괄·엑셀·가져오기·ERD 편집)가 동기화를 직접 부르지 않아도 빠지지 않게 하는 것이 목적이다.
 * JPQL/네이티브 일괄 UPDATE·DELETE 는 엔티티 콜백이 없으므로 그런 경로는 syncReferenceRelations 를 직접 불러야 한다.
 */
public class ReferenceSyncListener {

    @PostPersist
    @PostUpdate
    @PostRemove
    public void changed(Object entity) {
        if (entity instanceof ColumnInfo column) {
            // 컬럼의 참조 테이블명·PK 여부·이름이 바뀌면 그 테이블의 컬럼과 그 테이블을 참조하는 컬럼을 다시 본다
            if (column.getTableInfo() != null) ReferenceSyncBoundary.mark(column.getProjectId(), column.getTableInfo().getId());
        } else if (entity instanceof TableInfo table) {
            // 테이블명이 바뀌면 참조 대상이 달라진다 (새 이름을 가리키던 미해결 참조, 옛 이름을 가리키던 관계)
            ReferenceSyncBoundary.mark(table.getProjectId(), table.getId());
        }
    }
}
