package com.lexorma.ai.evidence;

import com.lexorma.common.jpa.BaseEntity;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardTerm;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardTermRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.history.domain.entity.ModelChangeHistory;
import com.lexorma.data.history.repository.ModelChangeHistoryRepository;
import com.lexorma.data.logical.domain.entity.LogicalAttribute;
import com.lexorma.data.logical.domain.entity.LogicalEntity;
import com.lexorma.data.logical.domain.entity.LogicalIdentifier;
import com.lexorma.data.logical.domain.entity.LogicalModel;
import com.lexorma.data.logical.domain.entity.LogicalRelationship;
import com.lexorma.data.logical.domain.entity.LogicalSubjectArea;
import com.lexorma.data.logical.domain.entity.LogicalSubtypeGroup;
import com.lexorma.data.logical.repository.LogicalAttributeRepository;
import com.lexorma.data.logical.repository.LogicalEntityRepository;
import com.lexorma.data.logical.repository.LogicalIdentifierRepository;
import com.lexorma.data.logical.repository.LogicalModelRepository;
import com.lexorma.data.logical.repository.LogicalRelationshipRepository;
import com.lexorma.data.logical.repository.LogicalSubjectAreaRepository;
import com.lexorma.data.logical.repository.LogicalSubtypeGroupRepository;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 근거가 될 수 있는 메타데이터의 스냅숏 (AI-4 후속). Tool 이 결과를 돌려줄 때(그 시점의 상태)와 근거를 검증할 때(지금 상태) 같은 방식으로 만든다.
 * <ul>
 *   <li>조회는 기존 저장소의 ID 조회(findAllById) 한 번씩 — 종류별로 묶어 N+1 이 없다. 쓰지 않는다</li>
 *   <li>변경 이력(HISTORY)은 추가만 하는 기록이라 기록 시각이 버전이다</li>
 *   <li>버전 : 행의 마지막 수정 일시(MDFCN_DT, {@code @LastModifiedDate}). 이 테이블들은 JPA 로만 쓰고(벌크 UPDATE 없음) 수정 때마다 바뀐다.
 *       한계 — DB 를 직접 고친 변경은 보이지 않고, 테이블의 버전은 그 행만이다(컬럼·관계는 각자의 버전으로 본다)</li>
 *   <li>논리 모델 (D35-3b) : 모델·주제 영역·엔터티·속성·식별자·관계·서브타입 그룹도 같은 방식 (ID 조회 한 번씩, 버전 = MDFCN_DT). 속성·식별자의 프로젝트·소속은
 *       그 행을 정의한 엔터티 (엔터티는 배치 지연 로딩으로 묶어 읽는다 — default_batch_fetch_size). 상속 속성도 원본 행이라 정의 엔터티 소속이다</li>
 *   <li>프로젝트는 스냅숏에 담아 비교만 한다. 다른 프로젝트 행의 이름 등 내용은 밖으로 내지 않는다 ({@link EvidenceVerifier})</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class EvidenceCatalog {

    /**
     * @param labels   이 근거를 부를 수 있는 이름 (대문자·공백→_ 로 정규화). AI 가 준 이름이 이 중 하나와 같아야 한다
     * @param parentId 컬럼이 속한 테이블 (컬럼만)
     * @param kind     관계 종류 FK / LOGICAL (관계만)
     * @param version  마지막 수정 일시 (없으면 버전을 확인할 수 없다)
     */
    public record Snapshot(EvidenceType type, Long id, Long projectId, Set<String> labels, Long parentId, String kind, LocalDateTime version) {
    }

    private final SchemaInfoRepository schemas;
    private final TableInfoRepository tables;
    private final ColumnInfoRepository columns;
    private final TableRelationRepository relations;
    private final StandardTermRepository terms;
    private final StandardWordRepository words;
    private final StandardDomainRepository domains;
    private final ModelChangeHistoryRepository histories;
    private final LogicalModelRepository logicalModels;
    private final LogicalSubjectAreaRepository subjectAreas;
    private final LogicalEntityRepository logicalEntities;
    private final LogicalAttributeRepository logicalAttributes;
    private final LogicalIdentifierRepository logicalIdentifiers;
    private final LogicalRelationshipRepository logicalRelationships;
    private final LogicalSubtypeGroupRepository subtypeGroups;

    /** ID 로 지금 상태를 읽는다 (없는 ID 는 결과에 없다). 호출하는 쪽의 트랜잭션 안에서 부른다 */
    public Map<Long, Snapshot> load(EvidenceType type, Collection<Long> ids) {
        Set<Long> distinct = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Snapshot> found = new LinkedHashMap<>();
        if (distinct.isEmpty()) return found;
        switch (type) {
            case SCHEMA -> schemas.findAllById(distinct).forEach(s -> put(found, snapshot(s)));
            case TABLE -> tables.findAllById(distinct).forEach(t -> put(found, snapshot(t)));
            case COLUMN -> columns.findAllById(distinct).forEach(c -> put(found, snapshot(c)));
            case RELATION -> relations.findAllById(distinct).forEach(r -> put(found, snapshot(r)));
            case TERM -> terms.findAllById(distinct).forEach(t -> put(found, snapshot(t)));
            case WORD -> words.findAllById(distinct).forEach(w -> put(found, snapshot(w)));
            case DOMAIN -> domains.findAllById(distinct).forEach(d -> put(found, snapshot(d)));
            case HISTORY -> histories.findAllById(distinct).forEach(h -> put(found, snapshot(h)));
            case LOGICAL_MODEL -> logicalModels.findAllById(distinct).forEach(m -> put(found, snapshot(m)));
            case SUBJECT_AREA -> subjectAreas.findAllById(distinct).forEach(a -> put(found, snapshot(a)));
            case LOGICAL_ENTITY -> logicalEntities.findAllById(distinct).forEach(e -> put(found, snapshot(e)));
            case LOGICAL_ATTRIBUTE -> logicalAttributes.findAllById(distinct).forEach(a -> put(found, snapshot(a)));
            case LOGICAL_IDENTIFIER -> logicalIdentifiers.findAllById(distinct).forEach(i -> put(found, snapshot(i)));
            case LOGICAL_RELATIONSHIP -> logicalRelationships.findAllById(distinct).forEach(r -> put(found, snapshot(r)));
            case SUBTYPE_GROUP -> subtypeGroups.findAllById(distinct).forEach(g -> put(found, snapshot(g)));
        }
        return found;
    }

    /** 이름 비교용 정규화 : 앞뒤 공백 제거, 대문자, 공백·_ 를 같게 */
    public static String normalize(String name) {
        return name == null ? "" : name.strip().replaceAll("\\s+", "_").toUpperCase(Locale.ROOT);
    }

    private static void put(Map<Long, Snapshot> found, Snapshot snapshot) {
        found.put(snapshot.id(), snapshot);
    }

    private static Snapshot snapshot(SchemaInfo s) {
        return new Snapshot(EvidenceType.SCHEMA, s.getId(), s.getProjectId(), labels(s.getSchemaName()), null, null, version(s.audit()));
    }

    private static Snapshot snapshot(TableInfo t) {
        return new Snapshot(EvidenceType.TABLE, t.getId(), t.getProjectId(), labels(t.getTableName(), t.getTableKorName()), null, null, version(t.audit()));
    }

    private static Snapshot snapshot(ColumnInfo c) {
        return new Snapshot(EvidenceType.COLUMN, c.getId(), c.getProjectId(), labels(c.getColumnName(), c.getColumnDesc()),
                c.getTableInfo() == null ? null : c.getTableInfo().getId(), null, version(c.audit()));
    }

    private static Snapshot snapshot(TableRelation r) {
        return new Snapshot(EvidenceType.RELATION, r.getId(), r.getProjectId(), labels(r.getRelationName()), null,
                r.getRelationType() == null ? null : r.getRelationType().name(), version(r.audit()));
    }

    private static Snapshot snapshot(StandardTerm t) {
        return new Snapshot(EvidenceType.TERM, t.getId(), t.getProjectId(), labels(t.getCommonStandardTermName(), t.getCommonStandardTermAbbreviation()),
                null, null, version(t.audit()));
    }

    private static Snapshot snapshot(StandardWord w) {
        return new Snapshot(EvidenceType.WORD, w.getId(), w.getProjectId(),
                labels(w.getCommonStandardWordName(), w.getCommonStandardWordAbbreviation(), w.getCommonStandardWordEnglishName()), null, null, version(w.audit()));
    }

    private static Snapshot snapshot(StandardDomain d) {
        return new Snapshot(EvidenceType.DOMAIN, d.getId(), d.getProjectId(), labels(d.getCommonStandardDomainName()), null, null, version(d.audit()));
    }

    /**
     * 변경 이력 한 건 : 이름은 변경 시점의 대상 이름(컬럼은 "테이블.컬럼" 과 컬럼명), 소속은 대상 ID, 종류는 대상 종류.
     * 이력은 추가만 하고 고치지 않으므로(수정·삭제 API·벌크 쓰기 없음) 버전은 기록 시각이다
     */
    private static Snapshot snapshot(ModelChangeHistory h) {
        String name = h.getTargetName();
        String shortName = name != null && h.getTargetType() == ModelChangeHistory.TargetType.PHYSICAL_COLUMN && name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : null;
        return new Snapshot(EvidenceType.HISTORY, h.getId(), h.getProjectId(), labels(name, shortName), h.getTargetId(), h.getTargetType().name(), h.getChangedAt());
    }

    // ------------------------------------------------------------------ 논리 모델 (D35-3b). parentId : 영역·엔터티·그룹 = 모델, 속성·식별자 = 정의한 엔터티

    private static Snapshot snapshot(LogicalModel m) {
        return new Snapshot(EvidenceType.LOGICAL_MODEL, m.getId(), m.getProjectId(), labels(m.getModelName()), null, null, m.modifiedAt());
    }

    private static Snapshot snapshot(LogicalSubjectArea a) {
        return new Snapshot(EvidenceType.SUBJECT_AREA, a.getId(), a.getProjectId(), labels(a.getAreaName()), a.getModel().getId(), null, a.modifiedAt());
    }

    private static Snapshot snapshot(LogicalEntity e) {
        return new Snapshot(EvidenceType.LOGICAL_ENTITY, e.getId(), e.getProjectId(), labels(e.getEntityName()), e.getModel().getId(), null, e.modifiedAt());
    }

    private static Snapshot snapshot(LogicalAttribute a) {
        LogicalEntity owner = a.getEntity();
        return new Snapshot(EvidenceType.LOGICAL_ATTRIBUTE, a.getId(), owner.getProjectId(), labels(a.getAttributeName()), owner.getId(), null, a.modifiedAt());
    }

    private static Snapshot snapshot(LogicalIdentifier i) {
        LogicalEntity owner = i.getEntity();
        return new Snapshot(EvidenceType.LOGICAL_IDENTIFIER, i.getId(), owner.getProjectId(), labels(i.getIdentifierName()), owner.getId(), null, i.modifiedAt());
    }

    /** 관계 이름은 비어 있을 수 있다 : 이름이 없으면 양 끝 엔터티 이름으로 부를 수 있다 */
    private static Snapshot snapshot(LogicalRelationship r) {
        Set<String> names = r.getRelationshipName() == null || r.getRelationshipName().isBlank()
                ? labels(r.getSourceEntity().getEntityName(), r.getTargetEntity().getEntityName()) : labels(r.getRelationshipName());
        return new Snapshot(EvidenceType.LOGICAL_RELATIONSHIP, r.getId(), r.getProjectId(), names, r.getModel().getId(), null, r.modifiedAt());
    }

    private static Snapshot snapshot(LogicalSubtypeGroup g) {
        return new Snapshot(EvidenceType.SUBTYPE_GROUP, g.getId(), g.getProjectId(), labels(g.getGroupName()), g.getModel().getId(), null, g.modifiedAt());
    }

    private static Set<String> labels(String... names) {
        return Arrays.stream(names).filter(n -> n != null && !n.isBlank()).map(EvidenceCatalog::normalize).collect(Collectors.toSet());
    }

    private static LocalDateTime version(BaseEntity.Audit audit) {
        return audit == null ? null : audit.updated();
    }
}
