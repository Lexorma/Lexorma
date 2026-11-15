package com.lexorma.data.column.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.dto.ColumnTermDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.dto.StandardTermDto;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기존 컬럼의 표준 용어 재연결 (COL-TERM, DEC-01). 컬럼 등록 때만 정하던 용어를 바꾼다.
 * <ul>
 *   <li>연결만 바꾼다 : 컬럼명·데이터 타입·길이·정밀도·NULL·기본값·PK/FK·인덱스·제약·설명·업무 설명은 그대로 (@DynamicUpdate 라 UPDATE 에도 연결 칸만 들어간다).
 *       새 용어의 영문 약어·도메인 타입과 지금 컬럼이 달라도 막지 않는다 — 기존 표준화 규칙은 그 차이를 진단(M-5 품질 진단)으로만 보인다</li>
 *   <li>권한·승인 : 컬럼 수정과 같다 — USER(컨트롤러), 승인된 컬럼은 바꿀 수 없다(승인 취소 뒤). 컬럼에는 변경 요청 절차가 없다.
 *       새 용어는 이 프로젝트의 용어여야 한다. 승인 전 용어도 컬럼 등록 때처럼 쓸 수 있다(화면이 알린다)</li>
 *   <li>동시성 : 컬럼 행을 잠그고 다시 읽은 지금 연결과 화면이 읽은 연결(baseTermId)이 다르면 COLUMN_TERM_CONFLICT. 같은 용어로 바꾸면 아무것도 하지 않는다</li>
 *   <li>이력 : 모델 변경 이력(출처 USER)에 standardTerms 변경이 남는다 — 그 컬럼을 대상·근거로 쓴 AI 설명 제안은 승인·재확인 때 TERM_LINK_CHANGED 로 재검토된다</li>
 *   <li>용어가 없는 컬럼에 처음 연결하는 것과 연결 해제는 이 기능의 범위가 아니다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ColumnTermService {

    private final ColumnInfoRepository columns;
    private final StandardTermRepository terms;
    private final EntityManager entityManager;

    @Transactional
    public ColumnTermDto.Result relink(AccountDto account, Long columnId, ColumnTermDto.Request request) {
        Long projectId = account.getProjectId();
        if (columnId == null || request == null || request.termId() == null || request.baseTermId() == null) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "바꿀 표준 용어와 화면이 읽은 표준 용어가 필요합니다.");
        }
        ColumnInfo column = columns.findForUpdate(columnId, projectId).orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED));
        entityManager.refresh(column);
        if (Boolean.TRUE.equals(column.getIsApproval())) throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        StandardTerm current = column.getStandardTerms();
        if (current == null) throw new CustomException(ErrorCode.NOT_FOUND_TERM_DATA, "표준 용어가 연결되지 않은 컬럼은 표준 용어를 바꿀 수 없습니다.");
        if (!current.getId().equals(request.baseTermId())) throw new CustomException(ErrorCode.COLUMN_TERM_CONFLICT);
        StandardTerm next = terms.findByIdAndProjectId(request.termId(), projectId);
        if (next == null) throw new CustomException(ErrorCode.NOT_FOUND_TERM_DATA);
        StandardTermDto before = StandardTermDto.fromEntity(current);
        if (next.getId().equals(current.getId())) return new ColumnTermDto.Result(column.getId(), false, before, before);
        column.setStandardTerms(next);
        return new ColumnTermDto.Result(column.getId(), true, before, StandardTermDto.fromEntity(next));
    }
}
