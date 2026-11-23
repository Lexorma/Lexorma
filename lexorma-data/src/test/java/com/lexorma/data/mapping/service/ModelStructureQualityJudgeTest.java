package com.lexorma.data.mapping.service;

import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckType;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Row;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.AttributeColumnRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.ColumnFlagRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.EntityRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.ForeignKeyRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.IdentifierRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.InheritedKeyRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.PairRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.RelationshipRow;
import com.lexorma.data.mapping.repository.ModelStructureQualityQueryRepository.UniqueRow;
import com.lexorma.data.physical.domain.entity.TableRelation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-7 구조 정합성 순수 판정 : 통합 테스트가 만들기 어려운 경우 (후보 FK 여럿, 같은 FK 를 두 관계가 가리킴, 생성 대상 아닌 관계, UNIQUE 후보 여럿·구성 다름, 보조 식별자 = PK,
 * 두 번째 정확한 FK 는 물리 전용). 엔터티 1 = 부모(테이블 10, PK 컬럼 100), 엔터티 2 = 자식(테이블 20)
 */
class ModelStructureQualityJudgeTest {

    private final List<EntityRow> entities = List.of(new EntityRow(1L, "부모", 11L, 10L, "P", 5L, "S"), new EntityRow(2L, "자식", 21L, 20L, "C", 5L, "S"));
    private final List<IdentifierRow> primary = new ArrayList<>(List.of(new IdentifierRow(900L, 1L, "부모 식별자", true, 1000L, "부모 키", 1, 1L)));
    private final List<AttributeColumnRow> columns = new ArrayList<>(List.of(
            new AttributeColumnRow(1000L, "부모 키", 100L, "PK1", 10L),
            new AttributeColumnRow(1001L, "부모 값", 101L, "PV", 10L),
            new AttributeColumnRow(2000L, "자식 참조", 200L, "FK1", 20L),
            new AttributeColumnRow(2001L, "자식 값", 201L, "CV", 20L),
            new AttributeColumnRow(2002L, "자식 값2", 202L, "CV2", 20L)));

    private List<Row> judge(List<RelationshipRow> rels, List<PairRow> pairs, List<IdentifierRow> identifiers, List<ForeignKeyRow> fks, List<UniqueRow> uniques,
                            List<ColumnFlagRow> uniqueColumns, List<ColumnFlagRow> pks) {
        return ModelStructureQualityJudge.judge(new ModelStructureQualityJudge.Input(7L, "M", entities, rels, pairs, identifiers, columns, List.of(), fks, uniques, uniqueColumns, pks,
                id -> null, id -> id, Set.of()));
    }

    private static ForeignKeyRow fk(long id, long childColumn, String childName, long parentTable, long parentColumn, String parentName) {
        return new ForeignKeyRow(id, "FK" + id, TableRelation.Source.USER, 20L, "C", parentTable, parentTable == 10L ? "P" : "X", 1, childColumn, childName, parentColumn, parentName);
    }

    private static final List<ColumnFlagRow> PK = List.of(new ColumnFlagRow(10L, "P", 100L, "PK1"));

    @Test
    @DisplayName("두 FK 가 모두 부모로 가고 어느 것도 짝과 같지 않으면 비교 불가(FK_AMBIGUOUS), 두 FK 모두 물리 전용으로 따로 나오지 않는다")
    void ambiguousForeignKeys() {
        List<Row> rows = judge(List.of(new RelationshipRow(50L, "관계", 1L, 2L)), List.of(new PairRow(50L, 1, 1L, 1000L, "부모 키", 2000L, "자식 참조")), primary,
                List.of(fk(70L, 201L, "CV", 10L, 100L, "PK1"), fk(71L, 202L, "CV2", 10L, 100L, "PK1")), List.of(), List.of(), PK);
        assertThat(rows).filteredOn(r -> r.type() == CheckType.RELATIONSHIP).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
            assertThat(r.structure().code()).isEqualTo("FK_AMBIGUOUS");
            assertThat(r.structure().candidates()).hasSize(2);
        });
        assertThat(rows).noneMatch(r -> r.type() == CheckType.PHYSICAL_FK);
    }

    @Test
    @DisplayName("같은 짝의 관계가 둘이면 FK 하나에 하나만 대응(MATCH), 다른 하나는 FK_SHARED. 같은 구성의 두 번째 FK 는 물리 전용")
    void sharedAndDuplicateForeignKeys() {
        List<PairRow> pairs = List.of(new PairRow(50L, 1, 1L, 1000L, "부모 키", 2000L, "자식 참조"), new PairRow(51L, 1, 2L, 1000L, "부모 키", 2000L, "자식 참조"));
        List<Row> rows = judge(List.of(new RelationshipRow(50L, "관계1", 1L, 2L), new RelationshipRow(51L, "관계2", 1L, 2L)), pairs, primary,
                List.of(fk(70L, 200L, "FK1", 10L, 100L, "PK1")), List.of(), List.of(), PK);
        assertThat(rows).filteredOn(r -> r.type() == CheckType.RELATIONSHIP).extracting(r -> r.structure().code()).containsExactly("FK_MATCH", "FK_SHARED");

        List<Row> twice = judge(List.of(new RelationshipRow(50L, "관계1", 1L, 2L)), pairs.subList(0, 1), primary,
                List.of(fk(70L, 200L, "FK1", 10L, 100L, "PK1"), fk(71L, 200L, "FK1", 10L, 100L, "PK1")), List.of(), List.of(), PK);
        assertThat(twice).filteredOn(r -> r.type() == CheckType.PHYSICAL_FK).singleElement().satisfies(r -> {
            assertThat(r.structure().physicalId()).isEqualTo(71L);
            assertThat(r.structure().code()).isEqualTo("PHYSICAL_ONLY_FK");
        });
    }

    @Test
    @DisplayName("FK 가 없을 때 : 부모 컬럼이 부모 PK 면 FK_MISSING(UNMAPPED), 아니면 생성 대상 아님(비교 불가 FK_NOT_EXPECTED)")
    void missingForeignKey() {
        List<PairRow> pairs = List.of(new PairRow(50L, 1, 1L, 1000L, "부모 키", 2000L, "자식 참조"));
        List<RelationshipRow> rel = List.of(new RelationshipRow(50L, "관계", 1L, 2L));
        assertThat(judge(rel, pairs, primary, List.of(), List.of(), List.of(), PK).getFirst().structure().code()).isEqualTo("FK_MISSING");
        // 부모 테이블 PK 가 다른 컬럼
        List<Row> notExpected = judge(rel, pairs, primary, List.of(), List.of(), List.of(), List.of(new ColumnFlagRow(10L, "P", 101L, "PV")));
        assertThat(notExpected.getFirst().status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(notExpected.getFirst().structure().code()).isEqualTo("FK_NOT_EXPECTED");
    }

    @Test
    @DisplayName("보조 식별자 : 겹치는 UNIQUE 가 여럿이면 비교 불가, 구성이 엇갈리면 UNIQUE_COLUMNS_DIFFER, PK 와 같으면 비교 불가")
    void alternateKeys() {
        List<IdentifierRow> ak = new ArrayList<>(primary);
        ak.add(new IdentifierRow(901L, 2L, "자식 보조", false, 2001L, "자식 값", 1, 2L));
        ak.add(new IdentifierRow(901L, 2L, "자식 보조", false, 2002L, "자식 값2", 2, 3L));
        List<UniqueRow> two = List.of(new UniqueRow(80L, "UX_A", 20L, 1, 201L, "CV"), new UniqueRow(81L, "UX_B", 20L, 1, 202L, "CV2"));
        Row ambiguous = judge(List.of(), List.of(), ak, List.of(), two, List.of(), PK).stream().filter(r -> r.type() == CheckType.ALTERNATE_KEY).findFirst().orElseThrow();
        assertThat(ambiguous.structure().code()).isEqualTo("UNIQUE_AMBIGUOUS");
        List<UniqueRow> differ = List.of(new UniqueRow(80L, "UX_A", 20L, 1, 201L, "CV"), new UniqueRow(80L, "UX_A", 20L, 2, 200L, "FK1"));
        Row d = judge(List.of(), List.of(), ak, List.of(), differ, List.of(), PK).stream().filter(r -> r.type() == CheckType.ALTERNATE_KEY).findFirst().orElseThrow();
        assertThat(d.status()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(d.structure().code()).isEqualTo("UNIQUE_COLUMNS_DIFFER");

        List<IdentifierRow> pkLike = new ArrayList<>(primary);
        pkLike.add(new IdentifierRow(902L, 1L, "부모 보조", false, 1000L, "부모 키", 1, 4L));
        Row same = judge(List.of(), List.of(), pkLike, List.of(), List.of(), List.of(), PK).stream().filter(r -> r.type() == CheckType.ALTERNATE_KEY).findFirst().orElseThrow();
        assertThat(same.structure().code()).isEqualTo("ALTERNATE_KEY_IS_PRIMARY_KEY");
    }

    @Test
    @DisplayName("D-INH-11 : 상속 FK(서브타입의 상속 PK 컬럼 → 직계 슈퍼타입) 는 물리 전용으로 내지 않고, 같은 두 테이블 사이의 다른 일반 FK 는 물리 전용으로 낸다 (I-4 — 예전에는 둘 다 뺐다)")
    void inheritanceForeignKeySkipped() {
        // 자식 테이블 20 의 상속 PK 컬럼 210 (루트 주 식별자 1000 의 상속 PK 매핑)
        List<InheritedKeyRow> inherited = List.of(new InheritedKeyRow(2L, 1000L, "부모 키", 210L, "C_PK", 20L));
        List<ColumnFlagRow> pks = List.of(new ColumnFlagRow(10L, "P", 100L, "PK1"), new ColumnFlagRow(20L, "C", 210L, "C_PK"));
        List<Row> rows = ModelStructureQualityJudge.judge(new ModelStructureQualityJudge.Input(7L, "M", entities, List.of(), List.of(), primary, columns, inherited,
                List.of(fk(70L, 210L, "C_PK", 10L, 100L, "PK1"), fk(71L, 200L, "FK1", 10L, 100L, "PK1")), List.of(), List.of(), pks, id -> id == 2L ? 1L : null,
                id -> id == 2L ? 1L : id, Set.of(1L, 2L)));
        assertThat(rows).filteredOn(r -> r.type() == CheckType.PHYSICAL_FK).singleElement().satisfies(r -> assertThat(r.structure().physicalId()).isEqualTo(71L));
        // 상속 PK 매핑이 없으면 자식 테이블 PK 컬럼 집합으로 상속 FK 후보를 본다 (직계 슈퍼타입 테이블 참조만 — 상속 구조 진단이 매핑 누락을 따로 낸다)
        List<Row> unmapped = ModelStructureQualityJudge.judge(new ModelStructureQualityJudge.Input(7L, "M", entities, List.of(), List.of(), primary, columns, List.of(),
                List.of(fk(70L, 210L, "C_PK", 10L, 100L, "PK1"), fk(71L, 200L, "FK1", 10L, 100L, "PK1")), List.of(), List.of(), pks, id -> id == 2L ? 1L : null,
                id -> id == 2L ? 1L : id, Set.of(1L, 2L)));
        assertThat(unmapped).filteredOn(r -> r.type() == CheckType.PHYSICAL_FK).singleElement().satisfies(r -> assertThat(r.structure().physicalId()).isEqualTo(71L));
    }

    @Test
    @DisplayName("D-INH-11·29.3 : 일반 관계의 기대 쌍이 상속 FK 쌍과 같으면 INHERITANCE_FK_OVERLAP (비교 불가) — 상속 FK 를 그 관계의 일치로 가져가지 않고 물리 전용에도 내지 않는다")
    void overlapWithInheritanceForeignKey() {
        List<InheritedKeyRow> inherited = List.of(new InheritedKeyRow(2L, 1000L, "부모 키", 210L, "C_PK", 20L));
        List<AttributeColumnRow> cols = new ArrayList<>(columns);
        cols.add(new AttributeColumnRow(2003L, "자식 키 사본", 210L, "C_PK", 20L)); // (잘못된 데이터) 자식 속성이 상속 PK 컬럼에 직접 매핑됨
        List<ColumnFlagRow> pks = List.of(new ColumnFlagRow(10L, "P", 100L, "PK1"), new ColumnFlagRow(20L, "C", 210L, "C_PK"));
        for (boolean withFk : new boolean[]{true, false}) {
            List<ForeignKeyRow> fks = withFk ? List.of(fk(70L, 210L, "C_PK", 10L, 100L, "PK1")) : List.of();
            List<Row> rows = ModelStructureQualityJudge.judge(new ModelStructureQualityJudge.Input(7L, "M", entities, List.of(new RelationshipRow(50L, "관계", 1L, 2L)),
                    List.of(new PairRow(50L, 1, 1L, 1000L, "부모 키", 2003L, "자식 키 사본")), primary, cols, inherited, fks, List.of(), List.of(), pks,
                    id -> id == 2L ? 1L : null, id -> id == 2L ? 1L : id, Set.of(1L, 2L)));
            assertThat(rows).filteredOn(r -> r.type() == CheckType.RELATIONSHIP).singleElement().satisfies(r -> {
                assertThat(r.structure().code()).as("FK " + withFk).isEqualTo("INHERITANCE_FK_OVERLAP");
                assertThat(r.status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
            });
            assertThat(rows).noneMatch(r -> r.type() == CheckType.PHYSICAL_FK);
        }
    }

    @Test
    @DisplayName("D-INH-11 순수 함수 : 상속 FK 후보 = 자식 컬럼 집합이 상속 PK 컬럼 집합과 같음 (중복·NULL 없음), 정상 상속 FK = 직계 부모 대상 + 자식·부모 쌍 순서까지 같음")
    void inheritancePureFunctions() {
        assertThat(ModelStructureQualityJudge.isInheritanceCandidate(List.of(1L, 2L), Set.of(2L, 1L))).isTrue();
        assertThat(ModelStructureQualityJudge.isInheritanceCandidate(List.of(1L), Set.of(1L, 2L))).isFalse();
        assertThat(ModelStructureQualityJudge.isInheritanceCandidate(List.of(1L, 1L), Set.of(1L))).isFalse();
        assertThat(ModelStructureQualityJudge.isInheritanceCandidate(java.util.Arrays.asList(1L, null), Set.of(1L))).isFalse();
        assertThat(ModelStructureQualityJudge.isInheritanceCandidate(List.of(), Set.of())).isFalse();
        assertThat(ModelStructureQualityJudge.isExactInheritanceFk(10L, List.of(1L, 2L), List.of(5L, 6L), 10L, List.of(1L, 2L), List.of(5L, 6L))).isTrue();
        assertThat(ModelStructureQualityJudge.isExactInheritanceFk(10L, List.of(2L, 1L), List.of(6L, 5L), 10L, List.of(1L, 2L), List.of(5L, 6L))).as("순서").isFalse();
        assertThat(ModelStructureQualityJudge.isExactInheritanceFk(11L, List.of(1L, 2L), List.of(5L, 6L), 10L, List.of(1L, 2L), List.of(5L, 6L))).as("대상").isFalse();
        assertThat(ModelStructureQualityJudge.isExactInheritanceFk(10L, List.of(1L, 2L), List.of(6L, 5L), 10L, List.of(1L, 2L), List.of(5L, 6L))).as("부모 컬럼").isFalse();
    }
}
