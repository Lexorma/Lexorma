package com.lexorma.data.view.domain.dto;

import java.time.LocalDateTime;
import java.util.List;

/** VIEW 관리 (M-15) 요청·응답. 엔티티를 그대로 내보내지 않는다 */
public final class ViewDto {

    private ViewDto() {
    }

    /** 출력 컬럼 하나 (순서 = 목록 순서). 데이터 타입은 사용자가 입력한 모델링 정보이고 비워 둘 수 있다 */
    public record Column(String columnName, String columnKorName, String dataType, String columnDescription) {
    }

    /**
     * 참조 객체 하나. referenceType TABLE / VIEW.
     * 내부 참조 : tableId 또는 viewId (같은 프로젝트의 객체). 응답·변경 요청 정의에는 그 시점의 schemaName·objectName 을 함께 담는다 (표시용 스냅샷).
     * 미확인 참조 : 두 ID 가 비고 schemaName(선택)·objectName 만 (모델에 없는 객체를 이름으로 적어 둔 것)
     */
    public record Reference(String referenceType, Long tableId, Long viewId, String schemaName, String objectName) {
        public boolean internal() {
            return tableId != null || viewId != null;
        }
    }

    /** 등록·수정 요청. schemaId 가 없으면 프로젝트 기본 스키마 */
    public record Save(Long schemaId, String viewName, String viewKorName, String viewDescription, String definition,
                       List<Column> columns, List<Reference> references) {
    }

    /** VIEW 정의 전체 : 상세 응답, 변경 요청의 전후 (승인할 때 이 단위로 한 번에 반영된다) */
    public record Definition(Long schemaId, String schemaName, String viewName, String viewKorName, String viewDescription, String definition,
                             List<Column> columns, List<Reference> references) {
        public Save toSave() {
            return new Save(schemaId, viewName, viewKorName, viewDescription, definition, columns, references);
        }
    }

    /** 목록 (Grid) 한 줄 : isApproval·pendingRequest 는 Y/N */
    public record Row(Long id, Long schemaId, String schemaName, String viewName, String viewKorName, String viewDescription,
                      int columnCount, int referenceCount, String isApproval, String pendingRequest,
                      LocalDateTime created, LocalDateTime updated, String createdBy, String updatedBy) {
    }

    public record ChangeRequest(Long id, Long viewId, String status, Definition before, Definition after,
                                String requestedBy, LocalDateTime requestedAt, String processedBy, LocalDateTime processedAt) {
    }

    /** 상세 : 승인된 (확정) 정의, 변경 요청 이력, 이 VIEW 를 참조하는 다른 VIEW */
    public record Detail(Long id, Boolean isApproval, Definition view, LocalDateTime created, LocalDateTime updated, String createdBy, String updatedBy,
                         List<ChangeRequest> requests, List<String> referencedBy) {
    }

    /** 등록·수정 결과 : applied false 면 변경 요청(승인 대기)을 만들었고 확정 정의는 그대로다 */
    public record SaveResult(Long id, boolean applied, ChangeRequest request) {
    }

    /** 참조 객체 선택 후보 (프로젝트의 테이블·VIEW) */
    public record Target(String referenceType, Long id, Long schemaId, String schemaName, String name, String korName) {
    }

    /** 물리 ERD 의 VIEW 노드 (읽기 전용) */
    public record ErdView(Long id, Long schemaId, String viewName, String viewKorName, Boolean isApproval,
                          List<Column> columns, List<Reference> references) {
    }
}
