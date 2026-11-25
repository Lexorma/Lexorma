package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeSuggestion;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeSuggestions;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.ColumnSuggestion;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.EntityOverlay;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.Level;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.LinkKind;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.OverlayStatus;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableOverlay;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.TableSuggestions;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.AttributeLink;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.Issue;
import com.lexorma.data.mapping.domain.dto.MappingSuggestionDto.MappingStatus;
import com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.CheckStatus;
import com.lexorma.data.mapping.service.ModelMappingQualityService;
import com.lexorma.data.mapping.domain.dto.ModelMappingDto;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.service.MappingSuggestionService;
import com.lexorma.data.mapping.service.ModelMappingService;
import com.lexorma.data.mapping.service.PhysicalGenerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M-7 자동 매핑 후보·ERD 매핑 표시. 모델은 논리 → 물리 생성으로 만든 뒤 매핑을 JDBC 로 지워 "매핑하지 않은 상태" 를 만든다.
 * 추천·표시는 조회 전용이고, 저장은 기존 매핑 저장 서비스로만 한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class MappingSuggestionIntegrationTest {

    @Autowired ApplicationContext context;
    @Autowired PhysicalGenerationService generation;
    @Autowired MappingSuggestionService suggestions;
    @Autowired ModelMappingService mappings;
    @Autowired ModelMappingQualityService quality;
    @Autowired LogicalSubtypeService subtypes;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    /** 고객(고객 번호 PK · 고객 명 · 고객 메일) · 주문(주문 번호 PK · 주문 고객 번호) 을 물리 생성한 뒤 두 엔터티의 매핑을 모두 지운다 */
    private record Fx(GenerationFixture f, long customer, long order, long custNo, long custName, long custMail, long ordNo, long custTable, long orderTable) {
    }

    private Fx fixture(String name) {
        GenerationFixture f = GenerationFixture.create(context, "msg-" + name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        long custNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        long custName = f.attr(customer, "고객 명", false, "CUST_NM");
        long custMail = f.attr(customer, "고객 메일", false, "CUST_EML");
        f.pk(customer, custNo);
        long order = f.entity("주문");
        long ordNo = f.attr(order, "주문 번호", true, "ORD_NO");
        f.attr(order, "주문 고객 번호", false, "ORD_CUST_NO");
        f.pk(order, ordNo);
        List<Long> chosen = List.of(customer, order);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        long custTable = f.table(customer);
        long orderTable = f.table(order);
        unmap(customer);
        unmap(order);
        return new Fx(f, customer, order, custNo, custName, custMail, ordNo, custTable, orderTable);
    }

    private void unmap(long entity) {
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN in (select LGCL_ATRB_SN from TB_LGCL_ATRB where LGCL_ENTT_SN = ?)", entity);
        jdbc.update("delete from TB_SBTYP_IDNTFR_COL_MPNG where ENTT_TBL_MPNG_SN in (select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?)", entity);
        jdbc.update("delete from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", entity);
    }

    private long column(long table, String name) {
        return jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and COL_NM = ?", Long.class, table, name);
    }

    private long termOf(long attribute) {
        return jdbc.queryForObject("select STD_TRM_SN from TB_LGCL_ATRB where LGCL_ATRB_SN = ?", Long.class, attribute);
    }

    /** 추천·표시 전후로 바뀌면 안 되는 것 : 매핑 수, 승인 상태, 모델 변경 이력 */
    private List<Object> snapshot(long projectId) {
        List<Object> s = new ArrayList<>();
        s.add(jdbc.queryForList("select ENTT_TBL_MPNG_SN, LGCL_ENTT_SN, TBL_SN from TB_ENTT_TBL_MPNG where PRJ_SN = ? order by 1", projectId));
        s.add(jdbc.queryForList("select m.ATRB_COL_MPNG_SN, m.LGCL_ATRB_SN, m.COL_SN from TB_ATRB_COL_MPNG m join TB_ENTT_TBL_MPNG p on p.ENTT_TBL_MPNG_SN = m.ENTT_TBL_MPNG_SN where p.PRJ_SN = ? order by 1", projectId));
        s.add(jdbc.queryForList("select TBL_SN, APRV_YN, MDFCN_DT from TB_TBL where PRJ_SN = ? order by 1", projectId));
        s.add(jdbc.queryForList("select LGCL_ENTT_SN, APRV_YN, MDFCN_DT from TB_LGCL_ENTT where PRJ_SN = ? order by 1", projectId));
        s.add(jdbc.queryForObject("select count(*) from TB_MDL_CHG_HSTRY where PRJ_SN = ?", Integer.class, projectId));
        return s;
    }

    @Test
    @DisplayName("테이블 후보 : 주 식별자 용어 = PK 용어(STANDARD) 가 먼저, 코멘트만 같은 테이블은 참고 근거, 매핑된 엔터티·테이블은 제외, 스키마 범위, 조회는 아무것도 바꾸지 않는다")
    void tableSuggestions() {
        Fx x = fixture("table");
        long projectId = x.f().user.getProjectId();
        // 주문 테이블의 코멘트를 '고객' 으로 : 이름(코멘트)만 같은 후보
        jdbc.update("update TB_TBL set TBL_KORN_NM = '고객' where TBL_SN = ?", x.orderTable());
        List<Object> before = snapshot(projectId);

        TableSuggestions result = suggestions.tableSuggestions(x.f().user, x.customer(), null);
        assertThat(result.mapped()).isFalse();
        assertThat(result.available()).isTrue();
        assertThat(result.ambiguous()).as("후보 둘 — 자동으로 고르지 않는다").isTrue();
        assertThat(result.recommendedTableName()).isNotBlank();
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates().get(0).tableId()).isEqualTo(x.custTable());
        assertThat(result.candidates().get(0).reasons()).startsWith("KEY_TERMS").contains("TABLE_NAME", "TABLE_COMMENT");
        assertThat(result.candidates().get(0).level()).isEqualTo(Level.STANDARD);
        assertThat(result.candidates().get(0).matchedTerms()).isEqualTo(3);
        assertThat(result.candidates().get(1).tableId()).isEqualTo(x.orderTable());
        assertThat(result.candidates().get(1).reasons()).containsExactly("TABLE_COMMENT");
        assertThat(result.candidates().get(1).level()).as("이름(코멘트)만 같은 후보는 참고 근거").isEqualTo(Level.REFERENCE);

        // 스키마 범위 : 다른 스키마로 좁히면 후보 없음
        TableSuggestions other = suggestions.tableSuggestions(x.f().user, x.customer(), -1L);
        assertThat(other.available()).isFalse();
        assertThat(other.unavailableReason()).isEqualTo("NO_CANDIDATE");
        assertThat(other.candidates()).isEmpty();
        assertThat(snapshot(projectId)).as("후보 조회는 조회 전용").isEqualTo(before);

        // 사용자가 고른 후보는 기존 저장 API 로 저장한다. 매핑된 테이블은 다른 엔터티의 후보에서 빠지고, 매핑된 엔터티는 후보를 내지 않는다
        mappings.saveEntityMapping(x.f().user, new ModelMappingDto.EntityMapping(null, x.order(), x.orderTable()));
        assertThat(suggestions.tableSuggestions(x.f().user, x.customer(), null).candidates()).extracting(c -> c.tableId()).containsExactly(x.custTable());
        TableSuggestions mapped = suggestions.tableSuggestions(x.f().user, x.order(), null);
        assertThat(mapped.mapped()).isTrue();
        assertThat(mapped.unavailableReason()).isEqualTo("ALREADY_MAPPED");
        assertThat(mapped.candidates()).isEmpty();

        // 승인 상태와 무관 : 승인된 테이블·엔터티도 후보다 (매핑은 승인 상태를 바꾸지 않는다)
        jdbc.update("update TB_TBL set APRV_YN = true where TBL_SN = ?", x.custTable());
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_ENTT_SN = ?", x.customer());
        assertThat(suggestions.tableSuggestions(x.f().user, x.customer(), null).candidates()).extracting(c -> c.tableId()).containsExactly(x.custTable());
    }

    @Test
    @DisplayName("컬럼 후보 : 같은 표준 용어(승인) = STANDARD 가 먼저, 미승인 용어·다른 용어·용어 없음은 참고 근거, 같은 용어 후보 여럿은 충돌, 매핑된 속성·컬럼 제외, 예상 불일치")
    void attributeSuggestions() {
        Fx x = fixture("attribute");
        long projectId = x.f().user.getProjectId();
        ModelMappingDto.EntityMapping saved = mappings.saveEntityMapping(x.f().user, new ModelMappingDto.EntityMapping(null, x.customer(), x.custTable()));
        long noTerm = x.f().attrNoTerm(x.customer(), "고객 비고");
        long custNoCol = column(x.custTable(), "CUST_NO");
        long custNameCol = column(x.custTable(), "CUST_NM");
        long custMailCol = column(x.custTable(), "CUST_EML");
        // 고객 명 컬럼에 고객 번호의 용어를 붙인다 : 고객 번호의 같은 용어 후보가 둘(충돌), 고객 명 ↔ CUST_NM 은 용어가 달라 이름 근거만
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_SN = ?", termOf(x.custNo()), custNameCol);
        // 고객 메일 용어는 미승인
        jdbc.update("update TB_STD_TRM set APRV_YN = false where STD_TRM_SN = ?", termOf(x.custMail()));
        // 고객 메일 컬럼 타입을 바꿔 예상 불일치
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'INTEGER', MAX_LEN = null where COL_SN = ?", custMailCol);
        List<Object> before = snapshot(projectId);

        AttributeSuggestions result = suggestions.attributeSuggestions(x.f().user, saved.id());
        AttributeSuggestion no = find(result, x.custNo());
        assertThat(no.available()).isTrue();
        assertThat(no.candidates()).extracting(ColumnSuggestion::columnId).startsWith(custNoCol).contains(custNameCol);
        assertThat(no.candidates().get(0).reasons()).containsExactly("STANDARD_TERM", "COLUMN_NAME", "LOGICAL_NAME");
        assertThat(no.candidates().get(0).level()).isEqualTo(Level.STANDARD);
        assertThat(no.candidates().get(0).differences()).isEmpty();
        assertThat(no.conflicts()).contains("MULTIPLE_STANDARD");
        ColumnSuggestion sharedColumn = no.candidates().stream().filter(c -> c.columnId() == custNameCol).findFirst().orElseThrow();
        assertThat(sharedColumn.sharedWith()).containsExactly("고객 명");

        AttributeSuggestion name = find(result, x.custName());
        assertThat(name.candidates()).extracting(ColumnSuggestion::columnId).containsExactly(custNameCol);
        assertThat(name.candidates().get(0).reasons()).as("용어가 다르면 이름 근거만").containsExactly("COLUMN_NAME", "LOGICAL_NAME");
        assertThat(name.candidates().get(0).level()).isEqualTo(Level.REFERENCE);
        assertThat(name.candidates().get(0).differences()).extracting(d -> d.type()).contains("TERM");
        assertThat(name.candidates().get(0).sharedWith()).containsExactly("고객 번호");

        AttributeSuggestion mail = find(result, x.custMail());
        assertThat(mail.notes()).containsExactly("TERM_NOT_APPROVED");
        assertThat(mail.candidates().get(0).reasons()).startsWith("STANDARD_TERM_UNAPPROVED");
        assertThat(mail.candidates().get(0).level()).as("미승인 용어는 참고 근거").isEqualTo(Level.REFERENCE);
        assertThat(mail.candidates().get(0).differences()).extracting(d -> d.type()).contains("DATA_TYPE");

        AttributeSuggestion none = find(result, noTerm);
        assertThat(none.notes()).containsExactly("NO_TERM");
        assertThat(none.available()).isFalse();
        assertThat(none.unavailableReason()).isEqualTo("NO_CANDIDATE");
        assertThat(snapshot(projectId)).as("후보 조회는 조회 전용").isEqualTo(before);

        // 고른 후보 저장 = 기존 저장 API. 매핑된 속성은 후보를 내지 않고, 매핑된 컬럼은 다른 속성의 후보에서 빠진다
        mappings.saveAttributeMapping(x.f().user, new ModelMappingDto.AttributeMapping(null, saved.id(), x.custNo(), custNoCol));
        AttributeSuggestions after = suggestions.attributeSuggestions(x.f().user, saved.id());
        assertThat(find(after, x.custNo()).mapped()).isTrue();
        assertThat(find(after, x.custNo()).unavailableReason()).isEqualTo("ALREADY_MAPPED");
        assertThat(find(after, x.custNo()).candidates()).isEmpty();
        assertThat(after.attributes()).flatExtracting(AttributeSuggestion::candidates).extracting(ColumnSuggestion::columnId).doesNotContain(custNoCol);
    }

    @Test
    @DisplayName("후보 조회와 저장 사이에 다른 사용자가 그 컬럼을 매핑하면 저장은 기존 계약(SAVED_DATA_EXISTS)으로 거절되고 다른 후보로 바꾸지 않는다")
    void concurrentChange() {
        Fx x = fixture("concurrent");
        ModelMappingDto.EntityMapping saved = mappings.saveEntityMapping(x.f().user, new ModelMappingDto.EntityMapping(null, x.customer(), x.custTable()));
        long custNameCol = column(x.custTable(), "CUST_NM");
        jdbc.update("update TB_COL set STD_TRM_SN = ? where COL_SN = ?", termOf(x.custNo()), custNameCol);
        AttributeSuggestion seen = find(suggestions.attributeSuggestions(x.f().user, saved.id()), x.custNo());
        assertThat(seen.candidates()).extracting(ColumnSuggestion::columnId).contains(custNameCol);

        // 다른 사용자가 먼저 CUST_NM 을 고객 명에 매핑
        mappings.saveAttributeMapping(x.f().user, new ModelMappingDto.AttributeMapping(null, saved.id(), x.custName(), custNameCol));
        // M7-F01 : 기존 계약 400 SAVED_DATA_EXISTS 그대로 (409 로 바꾸지 않는다), 먼저 저장된 매핑은 그대로
        assertThatThrownBy(() -> mappings.saveAttributeMapping(x.f().user, new ModelMappingDto.AttributeMapping(null, saved.id(), x.custNo(), custNameCol)))
                .isInstanceOf(CustomException.class).hasMessageContaining("CUST_NM")
                .satisfies(e -> assertSavedDataExists((CustomException) e));
        assertThat(jdbc.queryForObject("select LGCL_ATRB_SN from TB_ATRB_COL_MPNG where COL_SN = ?", Long.class, custNameCol)).as("먼저 저장된 매핑 보존").isEqualTo(x.custName());
        assertThat(jdbc.queryForObject("select count(*) from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", Integer.class, x.custNo())).as("다른 컬럼으로 대신 저장하지 않는다").isZero();

        // 엔터티 매핑도 같다 : 조회 뒤 다른 엔터티가 그 테이블을 가져가면 거절
        TableSuggestions tables = suggestions.tableSuggestions(x.f().user, x.order(), null);
        assertThat(tables.candidates()).extracting(c -> c.tableId()).contains(x.orderTable());
        long other = x.f().entity("다른 주문");
        mappings.saveEntityMapping(x.f().user, new ModelMappingDto.EntityMapping(null, other, x.orderTable()));
        assertThatThrownBy(() -> mappings.saveEntityMapping(x.f().user, new ModelMappingDto.EntityMapping(null, x.order(), x.orderTable())))
                .isInstanceOf(CustomException.class).satisfies(e -> assertSavedDataExists((CustomException) e));
        assertThat(jdbc.queryForObject("select LGCL_ENTT_SN from TB_ENTT_TBL_MPNG where TBL_SN = ?", Long.class, x.orderTable())).as("먼저 저장된 매핑 보존").isEqualTo(other);
        assertThat(jdbc.queryForObject("select count(*) from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Integer.class, x.order())).as("다른 후보로 대신 저장하지 않는다").isZero();
    }

    private static void assertSavedDataExists(CustomException e) {
        assertThat(e.getErrorCode()).isEqualTo(com.lexorma.common.exception.domain.ErrorCode.SAVED_DATA_EXISTS);
        assertThat(e.getErrorCode().getHttpStatus().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("M7-F02 ERD 상태 분리 : 매핑 상태(MAPPED·UNMAPPED)는 저장된 매핑으로, 정합성(MATCH·MISMATCH·NOT_COMPARABLE·UNMAPPED)은 기존 판정 그대로 — 매핑됨 + 비교 불가도 매핑됨이다")
    void statusSeparation() {
        GenerationFixture f = GenerationFixture.create(context, "msg-sep-" + System.nanoTime(), DbType.POSTGRESQL);
        long customer = f.entity("고객");
        long custNo = f.attr(customer, "고객 번호", true, "CUST_NO");
        long custName = f.attr(customer, "고객 명", false, "CUST_NM");
        long grade = f.attrTyped(customer, "고객 등급", false, "CUST_GRD", "BIGINT", 0); // 도메인 길이 없음 → 길이 비교 불가
        long memo = f.attr(customer, "고객 메모", false, "CUST_MEMO");
        f.pk(customer, custNo);
        long order = f.entity("주문");
        f.pk(order, f.attr(order, "주문 번호", true, "ORD_NO"));
        List<Long> chosen = List.of(customer, order);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));
        long custTable = f.table(customer);
        long orderTable = f.table(order);
        // 고객 메모 : 속성 매핑만 해제 (엔터티·테이블 매핑은 그대로 — 일부 미매핑), 고객 명 : 컬럼 타입을 바꿔 불일치
        jdbc.update("delete from TB_ATRB_COL_MPNG where LGCL_ATRB_SN = ?", memo);
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'INTEGER', MAX_LEN = null where COL_SN = ?", column(custTable, "CUST_NM"));
        unmap(order);
        // 같은 프로젝트의 다른 모델 엔터티 (모델 범위 격리)
        GenerationFixture otherModel = f.otherModel("다른 모델");
        long otherEntity = otherModel.entity("다른 엔터티");

        List<EntityOverlay> overlays = suggestions.entityOverlays(f.user, f.modelId, List.of(customer, order, otherEntity));
        assertThat(overlays).extracting(EntityOverlay::entityId).as("다른 모델 엔터티는 빠진다").containsExactlyInAnyOrder(customer, order);
        EntityOverlay c = overlays.stream().filter(e -> e.entityId() == customer).findFirst().orElseThrow();
        assertThat(c.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
        assertThat(c.qualityStatus()).isEqualTo(CheckStatus.MISMATCH);
        assertThat(c.mappedAttributeCount()).as("일부 속성 미매핑").isEqualTo(3);
        assertThat(c.attributeCount()).isEqualTo(4);
        AttributeLink no = attribute(c, custNo);
        AttributeLink name = attribute(c, custName);
        AttributeLink gradeLink = attribute(c, grade);
        assertThat(no.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
        assertThat(no.qualityStatus()).as("매핑됨 + 일치").isEqualTo(CheckStatus.MATCH);
        assertThat(no.issues()).isEmpty();
        assertThat(name.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
        assertThat(name.qualityStatus()).as("매핑됨 + 불일치").isEqualTo(CheckStatus.MISMATCH);
        assertThat(name.issues()).extracting(Issue::type).contains("DATA_TYPE");
        assertThat(gradeLink.mappingStatus()).as("비교 불가여도 매핑됨").isEqualTo(MappingStatus.MAPPED);
        assertThat(gradeLink.qualityStatus()).as("매핑됨 + 비교 불가 — 판정 그대로").isEqualTo(CheckStatus.NOT_COMPARABLE);
        assertThat(gradeLink.status()).as("예전 필드의 뜻은 그대로").isEqualTo(OverlayStatus.NOT_COMPARABLE);
        assertThat(gradeLink.issues()).singleElement().satisfies(i -> {
            assertThat(i.type()).isEqualTo("LENGTH");
            assertThat(i.status()).isEqualTo(CheckStatus.NOT_COMPARABLE);
            assertThat(i.reason()).as("기존 판정의 사유 그대로").contains("길이");
        });
        assertThat(c.attributes()).extracting(AttributeLink::attributeId).as("미매핑 속성은 링크가 없다").doesNotContain(memo);
        EntityOverlay o = overlays.stream().filter(e -> e.entityId() == order).findFirst().orElseThrow();
        assertThat(o.mappingStatus()).isEqualTo(MappingStatus.UNMAPPED);
        assertThat(o.qualityStatus()).as("미매핑 + 미매핑").isEqualTo(CheckStatus.UNMAPPED);

        // 정합성 판정 원본은 그대로 : 같은 속성의 정합성 비교 행이 비교 불가다
        assertThat(quality.selectRows(f.user, new com.lexorma.data.mapping.domain.dto.ModelMappingQualityDto.Search(f.modelId, customer, null, null, null, null),
                org.springframework.data.domain.PageRequest.of(0, 200)).getContent())
                .filteredOn(r -> java.util.Objects.equals(r.attributeId(), grade) && r.type().name().equals("LENGTH")).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(CheckStatus.NOT_COMPARABLE));

        // 물리 쪽 : 테이블 일부 컬럼 미매핑, 컬럼 링크는 매핑 상태 + 정합성
        TableOverlay t = suggestions.tableOverlays(f.user, List.of(custTable, orderTable)).stream().filter(x -> x.tableId() == custTable).findFirst().orElseThrow();
        assertThat(t.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
        assertThat(t.mappedColumnCount()).isLessThan(t.columnCount());
        assertThat(t.columns()).filteredOn(l -> l.attributeId() == grade).singleElement().satisfies(l -> {
            assertThat(l.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
            assertThat(l.qualityStatus()).isEqualTo(CheckStatus.NOT_COMPARABLE);
        });
        TableOverlay ot = suggestions.tableOverlays(f.user, List.of(orderTable)).get(0);
        assertThat(ot.mappingStatus()).isEqualTo(MappingStatus.UNMAPPED);
        assertThat(ot.qualityStatus()).isEqualTo(CheckStatus.UNMAPPED);
    }

    private static AttributeLink attribute(EntityOverlay overlay, long attributeId) {
        return overlay.attributes().stream().filter(a -> a.attributeId() == attributeId).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("프로젝트 격리 : 다른 프로젝트의 엔터티·매핑·모델로는 조회되지 않고, 다른 프로젝트의 테이블은 후보·표시에 나오지 않는다")
    void isolation() {
        Fx a = fixture("iso-a");
        Fx b = fixture("iso-b"); // 같은 이름·용어 모양의 다른 프로젝트 (테이블도 매핑하지 않은 상태)
        ModelMappingDto.EntityMapping saved = mappings.saveEntityMapping(a.f().user, new ModelMappingDto.EntityMapping(null, a.customer(), a.custTable()));

        assertThat(suggestions.tableSuggestions(a.f().user, a.customer(), null).candidates()).extracting(c -> c.tableId())
                .doesNotContain(b.custTable(), b.orderTable());
        assertThatThrownBy(() -> suggestions.tableSuggestions(b.f().user, a.customer(), null)).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> suggestions.attributeSuggestions(b.f().user, saved.id())).isInstanceOf(CustomException.class);
        assertThatThrownBy(() -> suggestions.entityOverlays(b.f().user, a.f().modelId, List.of(a.customer()))).isInstanceOf(CustomException.class);
        assertThat(suggestions.entityOverlays(a.f().user, a.f().modelId, List.of(a.customer(), b.customer()))).extracting(EntityOverlay::entityId).containsExactly(a.customer());
        assertThat(suggestions.tableOverlays(b.f().user, List.of(a.custTable(), a.orderTable()))).as("다른 프로젝트 테이블 정보는 나오지 않는다").isEmpty();
        assertThatThrownBy(() -> suggestions.tableOverlays(a.f().user, LongStream.rangeClosed(1, 301).boxed().toList())).isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("ERD 매핑 표시 : 엔터티·테이블·속성·컬럼 매핑, 미매핑, 정합성 불일치, 매핑 해제·삭제 뒤의 표시")
    void overlays() {
        Fx x = fixture("overlay");
        long projectId = x.f().user.getProjectId();
        ModelMappingDto.EntityMapping saved = mappings.saveEntityMapping(x.f().user, new ModelMappingDto.EntityMapping(null, x.customer(), x.custTable()));
        long custNoCol = column(x.custTable(), "CUST_NO");
        long custMailCol = column(x.custTable(), "CUST_EML");
        mappings.saveAttributeMapping(x.f().user, new ModelMappingDto.AttributeMapping(null, saved.id(), x.custNo(), custNoCol));
        mappings.saveAttributeMapping(x.f().user, new ModelMappingDto.AttributeMapping(null, saved.id(), x.custMail(), custMailCol));
        List<Object> before = snapshot(projectId);

        List<EntityOverlay> entities = suggestions.entityOverlays(x.f().user, x.f().modelId, List.of(x.customer(), x.order()));
        EntityOverlay customer = entities.stream().filter(e -> e.entityId() == x.customer()).findFirst().orElseThrow();
        assertThat(customer.mapped()).isTrue();
        assertThat(customer.tableId()).isEqualTo(x.custTable());
        assertThat(customer.attributeCount()).isEqualTo(3);
        assertThat(customer.mappedAttributeCount()).isEqualTo(2);
        assertThat(customer.attributes()).extracting(a -> a.columnId()).containsExactlyInAnyOrder(custNoCol, custMailCol);
        assertThat(customer.attributes()).allSatisfy(a -> assertThat(a.kind()).isEqualTo(LinkKind.DIRECT));
        assertThat(customer.status()).as("미매핑 속성은 매핑된 엔터티의 상태를 바꾸지 않는다").isNotEqualTo(OverlayStatus.UNMAPPED);
        EntityOverlay order = entities.stream().filter(e -> e.entityId() == x.order()).findFirst().orElseThrow();
        assertThat(order.mapped()).isFalse();
        assertThat(order.status()).isEqualTo(OverlayStatus.UNMAPPED);
        assertThat(order.tableId()).isNull();

        List<TableOverlay> tables = suggestions.tableOverlays(x.f().user, List.of(x.custTable(), x.orderTable()));
        TableOverlay custTable = tables.stream().filter(t -> t.tableId() == x.custTable()).findFirst().orElseThrow();
        assertThat(custTable.entityId()).isEqualTo(x.customer());
        assertThat(custTable.modelId()).isEqualTo(x.f().modelId);
        assertThat(custTable.mappedColumnCount()).isEqualTo(2);
        assertThat(custTable.columns()).extracting(c -> c.attributeId()).containsExactlyInAnyOrder(x.custNo(), x.custMail());
        assertThat(tables.stream().filter(t -> t.tableId() == x.orderTable()).findFirst().orElseThrow().status()).isEqualTo(OverlayStatus.UNMAPPED);
        assertThat(snapshot(projectId)).as("표시는 조회 전용").isEqualTo(before);

        // 정합성 불일치 : 고객 메일 컬럼 타입을 바꾼다 → 속성·엔터티·테이블 모두 MISMATCH (정합성 비교와 같은 판정)
        jdbc.update("update TB_COL set DATA_TYPE_NM = 'INTEGER', MAX_LEN = null where COL_SN = ?", custMailCol);
        EntityOverlay mismatched = suggestions.entityOverlays(x.f().user, x.f().modelId, List.of(x.customer())).get(0);
        assertThat(mismatched.status()).isEqualTo(OverlayStatus.MISMATCH);
        assertThat(mismatched.mismatches()).isPositive();
        assertThat(mismatched.attributes()).filteredOn(a -> a.attributeId() == x.custMail()).singleElement()
                .satisfies(a -> assertThat(a.status()).isEqualTo(OverlayStatus.MISMATCH));
        assertThat(mismatched.attributes()).filteredOn(a -> a.attributeId() == x.custNo()).singleElement()
                .satisfies(a -> assertThat(a.status()).isNotEqualTo(OverlayStatus.MISMATCH));
        assertThat(suggestions.tableOverlays(x.f().user, List.of(x.custTable())).get(0).status()).isEqualTo(OverlayStatus.MISMATCH);

        // 매핑 해제 뒤 : 미매핑 (이동 대상이 없다), 삭제된 엔터티는 결과에서 빠진다
        unmap(x.customer());
        assertThat(suggestions.entityOverlays(x.f().user, x.f().modelId, List.of(x.customer())).get(0).mapped()).isFalse();
        assertThat(suggestions.tableOverlays(x.f().user, List.of(x.custTable())).get(0).mapped()).isFalse();
        assertThat(suggestions.entityOverlays(x.f().user, x.f().modelId, List.of(987_654_321L))).isEmpty();
    }

    @Test
    @DisplayName("상속 PK 매핑 표시 : 서브타입 엔터티·테이블은 상속 PK 매핑을 직접 매핑과 구분해 보인다")
    void inheritedKeys() {
        GenerationFixture f = SubtypePhysicalGenerationIntegrationTest.fixture(context, "msg-inh");
        long party = f.entity("당사자");
        long partyNo = f.attr(party, "당사자 번호", true, "PTY_NO");
        f.pk(party, partyNo);
        long org = f.entity("조직");
        long person = f.entity("사람");
        for (long id : new long[]{party, org, person}) jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        subtypes.saveGroup(f.user, new LogicalSubtypeDto.Save(null, f.modelId, party, "당사자 구분", null, true, false, null,
                List.of(new LogicalSubtypeDto.MemberInput(org, null), new LogicalSubtypeDto.MemberInput(person, null)), null));
        List<Long> chosen = List.of(party, org, person);
        PhysicalGenerationDto.Plan plan = generation.preview(f.user, f.request(chosen));
        generation.apply(f.user, f.request(chosen, plan.planToken()));

        EntityOverlay orgOverlay = suggestions.entityOverlays(f.user, f.modelId, List.of(org)).get(0);
        assertThat(orgOverlay.mapped()).isTrue();
        assertThat(orgOverlay.inheritedKeys()).singleElement().satisfies(k -> {
            assertThat(k.kind()).isEqualTo(LinkKind.INHERITED_KEY);
            assertThat(k.attributeId()).isEqualTo(partyNo);
            assertThat(k.status()).isEqualTo(OverlayStatus.MAPPED);
            assertThat(k.mappingStatus()).isEqualTo(MappingStatus.MAPPED);
            assertThat(k.qualityStatus()).isEqualTo(CheckStatus.MATCH);
        });

        // M7-F01 : 상속 PK 매핑 충돌도 기존 400 SAVED_DATA_EXISTS (같은 속성 두 번, 상속 PK 컬럼을 속성 매핑에)
        long orgMapping = jdbc.queryForObject("select ENTT_TBL_MPNG_SN from TB_ENTT_TBL_MPNG where LGCL_ENTT_SN = ?", Long.class, org);
        long keyColumn = orgOverlay.inheritedKeys().get(0).columnId();
        assertThatThrownBy(() -> mappings.saveSubtypeKeyMapping(f.user, new ModelMappingDto.SubtypeKeyMapping(null, orgMapping, partyNo, keyColumn)))
                .isInstanceOf(CustomException.class).satisfies(e -> assertSavedDataExists((CustomException) e));
        long orgAttribute = f.attr(org, "조직 명", false, "ORG_NM");
        assertThatThrownBy(() -> mappings.saveAttributeMapping(f.user, new ModelMappingDto.AttributeMapping(null, orgMapping, orgAttribute, keyColumn)))
                .isInstanceOf(CustomException.class).satisfies(e -> assertSavedDataExists((CustomException) e));
        assertThat(jdbc.queryForObject("select count(*) from TB_SBTYP_IDNTFR_COL_MPNG where COL_SN = ?", Integer.class, keyColumn)).as("기존 상속 PK 매핑 보존").isEqualTo(1);
        TableOverlay orgTable = suggestions.tableOverlays(f.user, List.of(f.table(org))).get(0);
        assertThat(orgTable.columns()).filteredOn(c -> c.kind() == LinkKind.INHERITED_KEY).singleElement()
                .satisfies(c -> assertThat(c.attributeName()).isEqualTo("당사자 번호"));
        TableOverlay partyTable = suggestions.tableOverlays(f.user, List.of(f.table(party))).get(0);
        assertThat(partyTable.columns()).allSatisfy(c -> assertThat(c.kind()).isEqualTo(LinkKind.DIRECT));
    }

    private static AttributeSuggestion find(AttributeSuggestions result, long attributeId) {
        return result.attributes().stream().filter(a -> a.attributeId() == attributeId).findFirst().orElseThrow();
    }
}
