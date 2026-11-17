package com.lexorma.source.catalog.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.crypto.SecretCipher;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.project.service.ProjectAuthority;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.domain.dto.SourceCatalogDto;
import com.lexorma.source.catalog.domain.entity.SourceTable;
import com.lexorma.source.catalog.domain.entity.SourceViewDefinition;
import com.lexorma.source.catalog.domain.entity.SourceViewDefinitionAccess;
import com.lexorma.source.catalog.domain.enums.SourceTableType;
import com.lexorma.source.catalog.domain.enums.SourceViewDefinitionStatus;
import com.lexorma.source.catalog.repository.SourceTableRepository;
import com.lexorma.source.catalog.repository.SourceViewDefinitionAccessRepository;
import com.lexorma.source.catalog.repository.SourceViewDefinitionRepository;
import com.lexorma.source.collection.repository.SourceCollectionExecutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 원본 VIEW 정의 (DEC-16, docs/completed/source-view-definition-dec16.md). 정의 원문은 업무 규칙·리터럴·민감한 이름을 담을 수 있어 <b>마스킹에 기대지 않고 경로를 막는다</b> :
 * <ul>
 *   <li>원문을 돌려주는 곳은 {@link #read} 하나다 : 지금의 DB 로 프로젝트 관리자·ADMIN 인지 다시 확인하고(세션 역할만 믿지 않는다), 확인 이력을 남긴 뒤 돌려준다</li>
 *   <li>일반 카탈로그 API·카탈로그 버전·변경 이력에는 상태·해시까지만 ({@link #summary}). 원본 비교·가져오기·AI(SourceCatalogReader)·모델 Export·MCP 는 이 테이블을 읽지 않는다</li>
 *   <li>요청 로그는 /api/source/** 라 본문을 남기지 않는다 (LogBodyPolicy). 이 서비스는 원문을 기록하지 않는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SourceViewDefinitionService {

    private final SourceTableRepository tableRepository;
    private final SourceViewDefinitionRepository definitionRepository;
    private final SourceViewDefinitionAccessRepository accessRepository;
    private final SourceCollectionExecutionRepository executionRepository;
    private final ExecutionContextVerifier verifier;
    private final ProjectAuthority projectAuthority;
    private final SecretCipher secretCipher;

    /** VIEW 정의의 상태 (원문 없음). TABLE 이면 null */
    @Transactional(readOnly = true)
    public SourceCatalogDto.ViewDefinition summary(AccountDto accountDto, SourceTable table) {
        if (table.getType() != SourceTableType.VIEW) return null;
        boolean canRead = accountDto.getId() != null && projectAuthority.canManage(table.getProjectId(), Long.valueOf(accountDto.getId()));
        SourceViewDefinition definition = definitionRepository.findByTableIdAndProjectId(table.getId(), table.getProjectId()).orElse(null);
        if (definition == null) return new SourceCatalogDto.ViewDefinition(null, false, null, null, null, SourceCatalogDto.DefinitionChange.UNKNOWN, canRead);
        boolean available = definition.getHash() != null;
        Integer latest = executionRepository.maxReflectedOrder(table.getConnectionId());
        SourceCatalogDto.DefinitionChange change = !available ? SourceCatalogDto.DefinitionChange.UNKNOWN
                : !Objects.equals(definition.getChangedOrder(), latest) ? SourceCatalogDto.DefinitionChange.SAME
                : definition.getPreviousHash() == null ? SourceCatalogDto.DefinitionChange.NEW : SourceCatalogDto.DefinitionChange.CHANGED;
        return new SourceCatalogDto.ViewDefinition(definition.getStatus().name(), available, definition.getLength(), definition.getCollectedAt(), definition.getChangedOrder(),
                change, canRead);
    }

    /** 정의 원문 : 프로젝트 관리자·ADMIN 만, 볼 때마다 확인 이력(누가·언제·그때의 해시)을 남긴다 */
    @Transactional
    public SourceCatalogDto.ViewDefinitionText read(AccountDto accountDto, Long tableId) {
        Long projectId = verifier.verifyProjectManager(ExecutionContext.user(accountDto)).projectId();
        SourceTable table = tableRepository.findByIdAndProjectId(tableId, projectId).filter(found -> found.getType() == SourceTableType.VIEW)
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        SourceViewDefinition definition = definitionRepository.findByTableIdAndProjectId(tableId, projectId).filter(found -> found.getHash() != null)
                .orElseThrow(() -> new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND, "수집된 VIEW 정의가 없습니다."));
        String text = Boolean.TRUE.equals(definition.getEncrypted())
                ? secretCipher.decrypt(definition.getContent(), SourceViewDefinitionCipher.scope(projectId, definition.getId())) : definition.getContent();
        accessRepository.save(SourceViewDefinitionAccess.builder().projectId(projectId).connectionId(table.getConnectionId()).tableId(tableId).hash(definition.getHash())
                .accessedAt(LocalDateTime.now()).build());
        SourceViewDefinitionStatus status = definition.getStatus();
        return new SourceCatalogDto.ViewDefinitionText(tableId, table.getSchema().getSchemaName(), table.getName(), text, status.name(), definition.getLength(),
                definition.getCollectedAt());
    }

    /** 정의 원문 확인 이력 (최근 순, 원문 없음) : 프로젝트 관리자·ADMIN 만 */
    @Transactional(readOnly = true)
    public List<SourceCatalogDto.ViewDefinitionAccess> accesses(AccountDto accountDto, Long tableId, int size) {
        Long projectId = verifier.verifyProjectManager(ExecutionContext.user(accountDto)).projectId();
        tableRepository.findByIdAndProjectId(tableId, projectId).orElseThrow(() -> new CustomException(ErrorCode.SOURCE_OBJECT_NOT_FOUND));
        return accessRepository.findAllByProjectIdAndTableIdOrderByIdDesc(projectId, tableId, PageRequest.of(0, Math.max(1, Math.min(size, 100)))).stream()
                .map(access -> new SourceCatalogDto.ViewDefinitionAccess(access.getId(), access.accountId(), access.getAccessedAt())).toList();
    }
}
