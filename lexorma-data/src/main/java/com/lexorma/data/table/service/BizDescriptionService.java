package com.lexorma.data.table.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.table.domain.dto.BizDescriptionDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 업무 설명(TASK_EXPLN) 직접 작성·수정·삭제 (AI-5 후속, AI 없이 동작). 테이블·컬럼 같은 규칙.
 * <ul>
 *   <li>행을 잠그고 다시 읽은 지금 값과 화면이 읽은 값(base, 필수)을 비교한다 ({@link BizDescriptions}) : 그 사이 다른 사용자나 AI 제안 승인이 바꿨으면
 *       BIZ_DESCRIPTION_CONFLICT — 같은 값으로 바꾸는 것은 충돌이 아니다. AI 제안 승인도 대상 행을 잠그고 생성 때 값과 비교하므로 오래된 제안이 이 값을 덮어쓰지 못한다</li>
 *   <li>업무 설명만 바꾼다 (@DynamicUpdate). 객체 승인 여부와 무관하다 (AI 제안 승인과 같은 결정 — 다른 속성의 승인 잠금은 그대로)</li>
 *   <li>모델 변경 이력에 출처 USER 로 남는다 (AI 제안 승인은 AI_APPROVAL)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BizDescriptionService {

    static final int MAX = 4000;

    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final EntityManager entityManager;

    @Transactional
    public BizDescriptionDto.Result update(AccountDto account, BizDescriptionDto.Request request) {
        Long projectId = account.getProjectId();
        if (request == null || request.tableId() == null || request.bizDescription() == null || request.base() == null) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "대상과 업무 설명, 화면이 읽은 업무 설명이 필요합니다.");
        }
        String next = BizDescriptions.normalize(request.bizDescription());
        if (next != null && next.length() > MAX) throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "업무 설명은 {0}자까지 입력할 수 있습니다.", MAX);
        if (request.columnId() != null) {
            ColumnInfo column = columns.findForUpdate(request.columnId(), projectId)
                    .filter(c -> c.getTableInfo() != null && c.getTableInfo().getId().equals(request.tableId()))
                    .orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED));
            entityManager.refresh(column);
            if (BizDescriptions.conflict(column.getBizDescription(), request.bizDescription(), request.base())) throw new CustomException(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
            if (!Objects.equals(BizDescriptions.normalize(column.getBizDescription()), next)) column.setBizDescription(next);
            return new BizDescriptionDto.Result(request.tableId(), column.getId(), column.getBizDescription());
        }
        TableInfo table = tables.findForUpdate(request.tableId(), projectId).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
        entityManager.refresh(table);
        if (BizDescriptions.conflict(table.getBizDescription(), request.bizDescription(), request.base())) throw new CustomException(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        if (!Objects.equals(BizDescriptions.normalize(table.getBizDescription()), next)) table.setBizDescription(next);
        return new BizDescriptionDto.Result(table.getId(), null, table.getBizDescription());
    }
}
