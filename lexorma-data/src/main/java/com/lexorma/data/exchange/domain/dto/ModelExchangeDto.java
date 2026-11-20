package com.lexorma.data.exchange.domain.dto;

import java.util.List;
import java.util.Map;

/**
 * 모델 교환 파일 (DEC-22, docs/completed/model-exchange-dec22.md). 버전 있는 JSON : {@code format = "lexorma-model"}, {@code formatVersion = 2}.
 * 내용은 모델 버전·변경 이력과 같은 필드 목록(ModelChangeRecorder)으로 만든 종류별 객체 목록이다. 파일 안의 id 는 이 파일 안에서만 쓰는 참조 번호다 (가져올 때 모두 새로 받는다).
 * 버전 2 (7차) : 모델 VIEW(PHYSICAL_VIEW — 정의 SQL·출력 컬럼·참조 객체)와 내보낸 사용자의 저장된 ERD 배치({@link Layouts})를 더했다.
 * 버전 3 (M-7 보강 3차) : 논리 관계 state 에 속성 짝 attributes(부모 속성 → 자식 속성, 순서 — 파일 안의 속성 번호로 잇고 가져올 때 새 속성에 다시 잇는다).
 * 버전 4 (M-6 후속 4차) : 논리 모델의 주제 영역(LOGICAL_SUBJECT_AREA — 상위 영역 parentId 로 계층)과 엔터티 소속(SUBJECT_AREA_ENTITY), 내보낸 사용자의 주제 영역별 배치
 * ({@link Layouts#subjectAreas()}). 가져올 때 새 ID 로 다시 잇는다.
 * 버전 5 (M-6 후속 5차) : 서브타입 그룹(SUBTYPE_GROUP — 슈퍼타입·배타성·완전성·분류 속성, state 의 subtypes 배열에 구성원·분류 값·순서). 상속 속성·식별자는 넣지 않는다
 * (원본 속성·식별자와 그룹으로 다시 만든다). 서브타입이 부모인 관계의 속성 짝은 루트의 주 식별자 속성 번호를 가리킨다.
 * 버전 6 (M-7 3차, M7-D43) : 상속 PK 매핑(SUBTYPE_KEY_MAPPING — 서브타입 엔터티 매핑 · 루트 주 식별자 속성 · 서브타입 테이블 컬럼 번호). 가져올 때 새 매핑·속성·컬럼에 다시 잇고,
 * 파일 안에서 참조·중복·속성 매핑과의 교차 중복을 확인한다 (어기면 파일 전체를 거절한다). 형식 1~5 파일에는 들어갈 수 없다.
 * 버전 1·2·3·4 파일도 그대로 가져온다 (VIEW·배치·속성 짝·주제 영역·서브타입이 없을 뿐이다 — 그 버전에 없는 내용이 들어 있으면 거절한다. 주제 영역·서브타입을 자동으로 만들지 않는다).
 * 넣지 않는 것 : 승인 상태, 원본 DB 연결·원본 카탈로그·원본 DB 에서 수집한 VIEW 정의(DEC-16), 비밀번호·키·토큰, 감사 컬럼·사용자·감사 로그.
 */
public final class ModelExchangeDto {

    public static final String FORMAT = "lexorma-model";
    public static final int FORMAT_VERSION = 6;
    /** 가져올 수 있는 형식 버전 : 1 (VIEW·배치 없음), 2 (관계 속성 짝 없음), 3 (주제 영역 없음), 4 (서브타입 없음), 5 (상속 PK 매핑 없음), 6 */
    public static final java.util.Set<Integer> SUPPORTED_VERSIONS = java.util.Set.of(1, 2, 3, 4, 5, 6);

    private ModelExchangeDto() {
    }

    /** 파일 안의 객체 하나 (id 는 파일 안 참조용) */
    public record Item(Long id, String name, Map<String, Object> state) {
    }

    /**
     * @param dbType 내보낸 프로젝트의 DBMS (가져올 프로젝트와 다르면 경고만 한다)
     * @param model  종류(SCHEMA, PHYSICAL_TABLE …) → 객체 목록
     * @param layouts 저장된 ERD 배치 (버전 2, 없으면 null — 가져온 뒤 자동 배치)
     */
    public record Document(String format, Integer formatVersion, String exportedAt, String dbType, Map<String, Integer> counts, Map<String, List<Item>> model,
                           Layouts layouts) {
    }

    /**
     * 내보낸 사용자가 서버에 저장한 ERD 배치 (화면에서 임시로 움직인 좌표가 아니다). 좌표의 키는 파일 안의 객체 번호다.
     *
     * @param physical 물리 ERD : PHYSICAL_TABLE 번호 → 좌표
     * @param logical  논리 ERD : 논리 모델별, LOGICAL_ENTITY 번호 → 좌표
     * @param subjectAreas 주제 영역별 논리 ERD (버전 4) : LOGICAL_SUBJECT_AREA 번호별, LOGICAL_ENTITY 번호 → 좌표
     */
    public record Layouts(PhysicalLayout physical, List<LogicalLayout> logical, List<SubjectAreaLayout> subjectAreas) {
        public Layouts(PhysicalLayout physical, List<LogicalLayout> logical) {
            this(physical, logical, null);
        }
    }

    /** @param subjectAreaId LOGICAL_SUBJECT_AREA 번호 (파일 안) */
    public record SubjectAreaLayout(Long subjectAreaId, Map<Long, Point> nodes, Viewport viewport) {
    }

    public record PhysicalLayout(Map<Long, Point> nodes, Viewport viewport) {
    }

    /** @param modelId LOGICAL_MODEL 번호 (파일 안) */
    public record LogicalLayout(Long modelId, Map<Long, Point> nodes, Viewport viewport) {
    }

    public record Point(Double x, Double y) {
    }

    public record Viewport(Double x, Double y, Double zoom) {
    }

    /**
     * 가져올 범위. null 이면 파일의 전부.
     *
     * @param tables        가져올 TABLE 키 ("스키마.테이블")
     * @param logicalModels 가져올 논리 모델 이름
     * @param views         가져올 VIEW 키 ("스키마.VIEW", null 이면 전부 — 예전 화면·클라이언트)
     */
    public record Selection(List<String> tables, List<String> logicalModels, List<String> views) {
    }

    /** @param targetProjectId 가져올 프로젝트 (null 이면 로그인 프로젝트). 그 프로젝트의 관리자·ADMIN 이어야 한다 */
    public record PlanRequest(Long targetProjectId, Document document, Selection selection) {
    }

    public record ApplyRequest(Long targetProjectId, Document document, Selection selection, String planToken, String requestId) {
    }

    /** NEW 새로 만든다 / CONFLICT 같은 이름이 이미 있어 가져오지 않는다 (덮어쓰지 않는다) / EXCLUDED 고르지 않았거나 가져올 수 없다 */
    public enum Status { NEW, CONFLICT, EXCLUDED }

    /** 가져오지 않는 이유. LIMIT_EXCEEDED : 배치를 더하면 저장 한도(물리 ERD 5,000개)를 넘는다 */
    public enum Reason { NAME_EXISTS, NOT_SELECTED, INVALID, UNSAFE_TEXT, RELATED_NOT_IMPORTED, LIMIT_EXCEEDED }

    /**
     * 계획의 한 줄 : TABLE(컬럼·인덱스·제약조건 포함) · 관계 · 논리 모델(엔터티·속성·식별자·관계 포함) · 매핑.
     *
     * @param kind   SCHEMA · TABLE · VIEW · RELATION · LOGICAL_MODEL · TABLE_MAPPING · ERD_LAYOUT (key PHYSICAL 또는 LOGICAL:모델명)
     * @param counts 함께 오는 것의 수 (TABLE : columns·indexes·constraints·unsafe(안전하지 않은 식이라 빼는 인덱스·제약조건), 논리 모델 : entities·relationships·subjectAreas,
     *               VIEW : columns·references·linked(대상 프로젝트의 기존 객체에 잇는 참조)·unresolved(모델 밖 참조로 남기는 참조), ERD_LAYOUT : nodes·ignored). 없으면 null
     */
    public record PlanItem(String kind, String key, String name, Status status, Reason reason, Map<String, Integer> counts) {
    }

    /**
     * @param warnings       경고 코드 : DB_TYPE_DIFFERS, TERM_NOT_FOUND:<n> (대상 프로젝트에 같은 이름의 용어가 없는 표준 용어 연결 n 개 — 연결 없이 가져온다),
     *                       LAYOUT_IGNORED:<n> (파일에 없는 객체를 가리키는 배치 좌표 n 개 — 무시한다)
     * @param counts         새로 만들 객체 수 (종류별)
     */
    public record Plan(Long targetProjectId, String targetProjectName, String planToken, String requestId, boolean applicable, List<PlanItem> items,
                       Map<String, Integer> counts, List<String> warnings) {
    }

    public record Result(Long targetProjectId, String changeGroupId, String modelVersionName, Map<String, Integer> created) {
    }

    /** 가져올 수 있는 프로젝트 (관리자·ADMIN 이고 사용 중인 것) */
    public record TargetProject(Long id, String name, String dbType, boolean current) {
    }
}
