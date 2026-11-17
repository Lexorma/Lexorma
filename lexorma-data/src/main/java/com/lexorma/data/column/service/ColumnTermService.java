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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 기존 컬럼의 표준 용어 재연결 (COL-TERM, DEC-01). 컬럼 등록 때만 정하던 용어를 바꾼다.
 * <ul>
 *   <li>연결만 바꾼다 : 컬럼명·데이터 타입·길이·정밀도·NULL·기본값·PK/FK·인덱스·제약·설명·업무 설명은 그대로 (@DynamicUpdate 라 UPDATE 에도 연결 칸만 들어간다).
 *       새 용어의 영문 약어·도메인 타입과 지금 컬럼이 달라도 막지 않는다 — 기존 표준화 규칙은 그 차이를 진단(M-5 품질 진단)으로만 보인다</li>
 *   <li>권한·승인 : 컬럼 수정과 같다 — USER(컨트롤러), 승인된 컬럼은 바꿀 수 없다(승인 취소 뒤). 컬럼에는 변경 요청 절차가 없다.
 *       새 용어는 이 프로젝트의 용어여야 한다. 승인 전 용어도 컬럼 등록 때처럼 쓸 수 있다(화면이 알린다)</li>
 *   <li>동시성 : 컬럼 행을 잠그고 다시 읽은 지금 연결과 화면이 읽은 연결(baseTermId)이 다르면 COLUMN_TERM_CONFLICT. 같은 용어로 바꾸면 아무것도 하지 않는다</li>
 *   <li>이력 : 모델 변경 이력(출처 USER)에 standardTerms 변경이 남는다 — 그 컬럼을 대상·근거로 쓴 AI 설명 제안은 승인·재확인 때 TERM_LINK_CHANGED 로 재검토된다</li>
 *   <li>DEC-26 (2026-11-16) : 용어가 없는 미승인 컬럼에 처음 연결(baseTermId 없음), 연결 해제(unlink), 여러 컬럼 일괄 연결·변경·해제({@link #bulk} — 컬럼 ID 순서로 잠그고,
 *       하나라도 거부되면 전체를 되돌린다)를 같은 규칙(승인 컬럼 거부·기준값 충돌·이력·AI 재검증)으로 한다</li>
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
        if (columnId == null || request == null) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "바꿀 표준 용어와 화면이 읽은 표준 용어가 필요합니다.");
        return apply(account.getProjectId(), columnId, request.termId(), request.baseTermId(), Boolean.TRUE.equals(request.unlink()));
    }

    /** 일괄 (DEC-26) : 같은 트랜잭션에서 컬럼 ID 순서로 잠그고 적용한다. 항목 하나라도 거부되면 예외로 전체를 되돌린다 (어느 컬럼인지 문구에 넣는다) */
    @Transactional
    public List<ColumnTermDto.Result> bulk(AccountDto account, ColumnTermDto.BulkRequest request) {
        List<ColumnTermDto.BulkItem> items = request == null || request.items() == null ? List.of() : request.items();
        if (items.isEmpty()) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "바꿀 컬럼을 고르세요.");
        if (items.size() > BULK_MAX) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "한 번에 {0}개 컬럼까지 바꿀 수 있습니다.", BULK_MAX);
        if (items.stream().map(ColumnTermDto.BulkItem::columnId).distinct().count() != items.size() || items.stream().anyMatch(i -> i.columnId() == null)) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "같은 컬럼이 두 번 들어 있습니다.");
        }
        Long projectId = account.getProjectId();
        List<ColumnTermDto.Result> results = new ArrayList<>();
        for (ColumnTermDto.BulkItem item : items.stream().sorted(Comparator.comparing(ColumnTermDto.BulkItem::columnId)).toList()) {
            try {
                results.add(apply(projectId, item.columnId(), item.termId(), item.baseTermId(), Boolean.TRUE.equals(item.unlink())));
            } catch (CustomException e) {
                ColumnInfo column = columns.findByProjectIdAndId(projectId, item.columnId());
                String name = column == null ? String.valueOf(item.columnId()) : column.getTableName() + "." + column.getColumnName();
                throw new CustomException(e.getErrorCode(), "{0} : {1}", name, e.getCustomMessage());
            }
        }
        return results;
    }

    private ColumnTermDto.Result apply(Long projectId, Long columnId, Long termId, Long baseTermId, boolean unlink) {
        if (unlink == (termId != null)) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "바꿀 표준 용어와 화면이 읽은 표준 용어가 필요합니다.");
        ColumnInfo column = columns.findForUpdate(columnId, projectId).orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED));
        entityManager.refresh(column);
        if (Boolean.TRUE.equals(column.getIsApproval())) throw new CustomException(ErrorCode.APPROVED_DATA_CANNOT_BE_UPDATE);
        StandardTerm current = column.getStandardTerms();
        // 화면이 읽은 연결(없었으면 null)과 지금 연결이 다르면 그 사이 다른 사용자가 바꿨다
        if (!Objects.equals(current == null ? null : current.getId(), baseTermId)) throw new CustomException(ErrorCode.COLUMN_TERM_CONFLICT);
        StandardTermDto before = current == null ? null : StandardTermDto.fromEntity(current);
        if (unlink) {
            if (current == null) return new ColumnTermDto.Result(column.getId(), false, null, null);
            column.setStandardTerms(null);
            return new ColumnTermDto.Result(column.getId(), true, before, null);
        }
        StandardTerm next = terms.findByIdAndProjectId(termId, projectId);
        if (next == null) throw new CustomException(ErrorCode.NOT_FOUND_TERM_DATA);
        if (current != null && next.getId().equals(current.getId())) return new ColumnTermDto.Result(column.getId(), false, before, before);
        column.setStandardTerms(next);
        return new ColumnTermDto.Result(column.getId(), true, before, StandardTermDto.fromEntity(next));
    }

    private static final int BULK_MAX = 500;
}
