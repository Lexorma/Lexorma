package com.lexorma.data.column.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

/**
 * 물리 컬럼명 변경 전용 내부 경로 (M-7 물리명 추천 일괄 적용 2차, P-14). 기존 컬럼 저장 API(등록·일괄 저장·용어 재연결)는 기존 컬럼의 이름을 바꾸지 않으며 그 의미는 그대로 둔다.
 * <p>
 * 바꾸는 것은 물리 컬럼명({@code COL_NM}) 하나다 — 논리 속성·표준 용어·도메인·컬럼 설명·업무 설명·승인 상태·매핑 대상 ID 는 그대로다 (2차 지시 28).
 * PK·FK·UNIQUE·인덱스 구성 컬럼, 논리·물리 매핑, 상속 PK 매핑, 원본 확정 컬럼 매핑은 모두 컬럼 ID 를 참조하므로 고칠 것이 없다.
 * 변경 이력은 일반 쓰기처럼 Hibernate 이벤트로 남는다 ({@code ModelChangeRecorder} — 컬럼명이 기록 필드다).
 * <p>
 * 부르는 쪽(PhysicalNameRenameService)이 프로젝트 행 배타 → 테이블 행 → 컬럼 행 순으로 잠그고 승인·용어·참조·이름 충돌을 판정한 뒤 부른다.
 * 여기서는 그 판정과 같은 불변식(미승인 컬럼·미승인 테이블·같은 테이블의 이름 중복 없음)을 한 번 더 확인해, 다른 곳에서 잘못 불러도 규칙을 어기지 않게 한다.
 */
@Service
public class ColumnRenameService {

    /** 잠근 컬럼의 이름을 newName(대문자)으로 바꾼다. 이름이 같으면 아무것도 하지 않는다. 부르는 쪽의 쓰기 트랜잭션 안에서만 부른다 (관리되는 엔티티를 바꾸고 커밋 때 반영된다) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void renameLockedColumn(Long projectId, ColumnInfo column, String newName) {
        // 빈 이름 (M7-U05 — DB CK_COL_01 과 같은 기준) 은 입력 오류
        if (ColumnNameKey.isBlankName(newName)) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "바꿀 컬럼명이 비어 있습니다.");
        String name = newName.trim().toUpperCase(Locale.ROOT);
        if (!Objects.equals(projectId, column.getProjectId()) || column.getTableInfo() == null) throw new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED);
        if (name.equals(column.getColumnName())) return;
        // 승인된 컬럼·승인된 테이블의 컬럼은 수정할 수 없다 (기존 승인 정책 — P-04·P-10)
        if (Boolean.TRUE.equals(column.getIsApproval()) || Boolean.TRUE.equals(column.getTableInfo().getIsApproval())) {
            throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        }
        // 같은 테이블 안에서만 컬럼명이 하나다 (컬럼 등록과 같은 기준 M7-U01 : 앞뒤 공백 제거 + 대문자 — DB 는 UX_COL_01). 자기 자신은 빼므로 대소문자만 바꾸는 것은 된다.
        // 이름 맞바꾸기·연쇄 변경(A → B, B → A)은 대상 이름을 지금 다른 컬럼이 쓰므로 여기서도 거절된다 (지원하지 않는다 — RenameSafety)
        boolean duplicated = column.getTableInfo().getColumns().stream()
                .anyMatch(other -> !other.getId().equals(column.getId()) && ColumnNameKey.same(other.getColumnName(), name));
        if (duplicated) throw new CustomException(ErrorCode.SAVED_DATA_EXISTS, column.getTableInfo().getTableName() + "." + name);
        column.setColumnName(name);
    }
}
