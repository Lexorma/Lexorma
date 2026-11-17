package com.lexorma.data.table.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.table.domain.dto.BizDescriptionDto;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
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
 *   <li>모델 변경 이력에 출처 USER 로 남는다 (AI 제안 승인은 AI_APPROVAL — {@link #writeAiApproval})</li>
 *   <li>잠근 뒤 엔터티를 {@code refresh} 하지 않고 업무 설명만 DB 에서 다시 읽는다 (AI-1d 후속) : 테이블 refresh 는 {@code TableInfo.columns}(cascade ALL)를 따라
 *       같은 트랜잭션에서 아직 flush 하지 않은 컬럼 변경까지 되돌렸다. 이 트랜잭션이 먼저 읽어 둔 엔터티가 그 사이 다른 트랜잭션의 커밋으로 낡았으면
 *       (엔터티 값 ≠ DB 값) 더티 체킹·변경 이력의 변경 전 값이 낡은 값을 기준으로 하므로 쓰지 않고 BIZ_DESCRIPTION_CONFLICT 로 거부한다
 *       — API 는 요청마다 새 트랜잭션에서 잠그며 읽으므로 이 경우가 없다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BizDescriptionService {

    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final com.lexorma.data.history.record.ModelChangeRecorder recorder;

    /**
     * AI 설명 제안 승인의 최종 쓰기 (lexorma-ai 의 승인 서비스가 부른다, 8차 모듈 분리) : 모델 데이터의 변경은 이 모듈이 한다.
     * 부르는 쪽이 이미 시작한 쓰기 트랜잭션 안에서, 대상 행을 잠그고 제안의 기준 버전을 확인한 뒤에 부른다 (잠금 순서 제안 → 모델 행은 그쪽 계약).
     * 업무 설명만 쓰고(객체 승인 상태와 무관) 모델 변경 이력의 출처를 AI_APPROVAL 로 남긴다.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void writeAiApproval(TableInfo lockedTable, String text) {
        recorder.markAiApproval();
        lockedTable.setBizDescription(text);
    }

    /** 컬럼 : {@link #writeAiApproval(TableInfo, String)} 와 같다 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void writeAiApproval(ColumnInfo lockedColumn, String text) {
        recorder.markAiApproval();
        lockedColumn.setBizDescription(text);
    }

    @Transactional
    public BizDescriptionDto.Result update(AccountDto account, BizDescriptionDto.Request request) {
        Long projectId = account.getProjectId();
        if (request == null || request.tableId() == null || request.bizDescription() == null || request.base() == null) {
            throw new CustomException(ErrorCode.INVALID_PHYSICAL_MODEL, "대상과 업무 설명, 화면이 읽은 업무 설명이 필요합니다.");
        }
        String next = BizDescriptions.normalize(request.bizDescription());
        if (request.columnId() != null) {
            ColumnInfo column = columns.findForUpdate(request.columnId(), projectId)
                    .filter(c -> c.getTableInfo() != null && c.getTableInfo().getId().equals(request.tableId()))
                    .orElseThrow(() -> new CustomException(ErrorCode.COLUMN_IS_NOT_DEFINED));
            String current = latest(column.getBizDescription(), columns.findBizDescription(column.getId()));
            BizDescriptions.requireLength(request.bizDescription(), current);
            if (BizDescriptions.conflict(current, request.bizDescription(), request.base())) throw new CustomException(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
            if (!Objects.equals(BizDescriptions.normalize(current), next)) column.setBizDescription(next);
            return new BizDescriptionDto.Result(request.tableId(), column.getId(), column.getBizDescription());
        }
        TableInfo table = tables.findForUpdate(request.tableId(), projectId).orElseThrow(() -> new CustomException(ErrorCode.TABLE_IS_NOT_DEFINED));
        String current = latest(table.getBizDescription(), tables.findBizDescription(table.getId()));
        BizDescriptions.requireLength(request.bizDescription(), current);
        if (BizDescriptions.conflict(current, request.bizDescription(), request.base())) throw new CustomException(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        if (!Objects.equals(BizDescriptions.normalize(current), next)) table.setBizDescription(next);
        return new BizDescriptionDto.Result(table.getId(), null, table.getBizDescription());
    }

    /** 잠근 행의 지금 업무 설명 (DB 값). 엔터티 값과 다르면 이 트랜잭션의 엔터티가 낡은 것이라 거부한다 (클래스 설명) */
    private static String latest(String entityValue, String databaseValue) {
        if (!Objects.equals(entityValue, databaseValue)) throw new CustomException(ErrorCode.BIZ_DESCRIPTION_CONFLICT);
        return databaseValue;
    }
}
