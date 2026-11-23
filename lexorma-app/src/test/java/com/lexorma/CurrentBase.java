package com.lexorma;

import com.lexorma.common.jpa.BaseEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 테스트용 : 화면이 "지금" 읽은 기준값 (M8-C2 — 삭제·승인·매핑 HTTP 요청이 기준값 필수가 되어, 컨트롤러를 직접 부르는 기존 테스트가 지금 값을 싣는다).
 * 동시성·충돌 자체를 검사하는 테스트는 이것을 쓰지 않고 자기가 읽은 값을 쓴다 (ConcurrentEditC2IntegrationTest)
 */
@Component
public class CurrentBase {

    @PersistenceContext
    private EntityManager entityManager;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lexorma.data.logical.service.LogicalModelService logical;

    @org.springframework.beans.factory.annotation.Autowired
    private com.lexorma.data.table.service.TableDesignService tables;

    /** 엔터티 삭제 확인의 지금 구성 기준값 (없는 엔터티·다른 프로젝트면 임의의 값 — 서비스가 대상 없음으로 끝낸다) */
    public String entityComposition(com.lexorma.core.security.domain.dto.AccountDto account, Long entityId) {
        try {
            return logical.selectEntityDeletion(account, entityId).composition();
        } catch (RuntimeException e) {
            return "v1:none";
        }
    }

    public String modelComposition(com.lexorma.core.security.domain.dto.AccountDto account, Long modelId) {
        try {
            return logical.selectModelDeletion(account, modelId).composition();
        } catch (RuntimeException e) {
            return "v1:none";
        }
    }

    /** 테이블 삭제·승인 요청 행 : 지금 수정 일시 + 삭제 확인 구성 */
    public com.lexorma.data.table.domain.dto.TableInfoDto table(Long tableId) {
        var account = (com.lexorma.core.security.domain.dto.AccountDto) org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        var dto = com.lexorma.data.table.domain.dto.TableInfoDto.builder().id(tableId).build();
        dto.setModifiedAt(of(com.lexorma.data.table.domain.entity.TableInfo.class, tableId));
        var deletion = tables.selectTableDeletions(java.util.List.of(tableId), account);
        dto.setComposition(deletion.isEmpty() ? "v1:none" : deletion.getFirst().composition());
        return dto;
    }

    /** 행의 지금 MDFCN_DT (없으면 임의의 값 — 대상 없음 시나리오는 그대로 대상 없음으로 끝난다) */
    @Transactional(readOnly = true)
    public LocalDateTime of(Class<? extends BaseEntity> type, Long id) {
        if (id == null) return null;
        BaseEntity entity = entityManager.find(type, id);
        return entity == null ? LocalDateTime.of(2000, 1, 1, 0, 0) : entity.modifiedAt(); // 없는 대상 : 기준값은 있고 서비스가 대상 없음으로 끝낸다
    }
}
