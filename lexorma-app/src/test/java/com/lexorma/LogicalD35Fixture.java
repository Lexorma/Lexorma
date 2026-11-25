package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalSubjectAreaDto;
import com.lexorma.data.logical.domain.dto.LogicalSubtypeDto;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.logical.service.LogicalSubjectAreaService;
import com.lexorma.data.logical.service.LogicalSubtypeService;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * D-35 D35-3b 테스트 데이터 : 3단계 상속(당사자 → 고객 → 개인고객·법인고객, 형제 거래처) + 복합 주 식별자(구분 → 번호) + 상속·자체 보조 식별자 + 관계(속성 짝) +
 * 3단계 주제 영역 + 유효 속성 105개·설명 300자 이상인 말단(대형). 운영·예제 데이터를 쓰지 않는다 (새 프로젝트)
 */
final class LogicalD35Fixture {

    final GenerationFixture f;
    long party, customer, vendor, person, corp, order;
    long partyNo, partyKind, email, birth, bizNo;
    long partyPk, emailAk, bizAk;
    long top, mid, leaf, relationship, partyGroup, customerGroup;
    /** 대형 : 루트(40) → 중간(30) → 말단(35) = 유효 105, 설명 480자 */
    long bigRoot, bigMiddle, bigLeaf;
    final List<Long> bigLeafAttributes = new ArrayList<>();

    private final LogicalModelService logical;
    private final LogicalSubtypeService subtypes;
    private final LogicalSubjectAreaService areas;
    private final JdbcTemplate jdbc;

    private LogicalD35Fixture(ApplicationContext context, String name) {
        this.f = GenerationFixture.create(context, name + "-" + System.nanoTime(), DbType.POSTGRESQL);
        this.logical = context.getBean(LogicalModelService.class);
        this.subtypes = context.getBean(LogicalSubtypeService.class);
        this.areas = context.getBean(LogicalSubjectAreaService.class);
        this.jdbc = context.getBean(JdbcTemplate.class);
    }

    static LogicalD35Fixture create(ApplicationContext context, String name, boolean big) {
        LogicalD35Fixture x = new LogicalD35Fixture(context, name);
        x.party = x.entity("당사자", "거래 상대 전체");
        x.customer = x.entity("고객", null);
        x.vendor = x.entity("거래처", null);
        x.person = x.entity("개인고객", null);
        x.corp = x.entity("법인고객", null);
        x.order = x.entity("주문", null);
        x.partyNo = x.attr(x.party, "당사자번호", "당사자를 구별하는 번호", true);
        x.partyKind = x.attr(x.party, "당사자구분", null, true);
        x.attr(x.party, "등록일자", null, false);
        x.attr(x.customer, "고객등급", null, false);
        x.email = x.attr(x.customer, "고객이메일", null, false);
        x.attr(x.vendor, "거래처등급", null, false);
        x.birth = x.attr(x.person, "생년월일", null, false);
        x.bizNo = x.attr(x.corp, "사업자번호", null, false);
        long orderNo = x.attr(x.order, "주문번호", null, true);
        long orderKind = x.attr(x.order, "주문당사자구분", null, true);
        long orderPartyNo = x.attr(x.order, "주문당사자번호", null, true);
        x.partyPk = x.identifier(x.party, "당사자 PK", true, x.partyKind, x.partyNo);
        x.identifier(x.order, "주문 PK", true, orderNo);
        x.emailAk = x.identifier(x.customer, "고객 이메일 AK", false, x.email);
        x.bizAk = x.identifier(x.corp, "사업자번호 AK", false, x.bizNo);
        x.partyGroup = x.subtypes.saveGroup(x.f.user, new LogicalSubtypeDto.Save(null, x.f.modelId, x.party, "당사자 구분", null, false, true, x.partyKind,
                List.of(new LogicalSubtypeDto.MemberInput(x.customer, "C"), new LogicalSubtypeDto.MemberInput(x.vendor, "V")), null)).id();
        x.customerGroup = x.subtypes.saveGroup(x.f.user, new LogicalSubtypeDto.Save(null, x.f.modelId, x.customer, "고객 유형", null, false, false, null,
                List.of(new LogicalSubtypeDto.MemberInput(x.person, null), new LogicalSubtypeDto.MemberInput(x.corp, null)), null)).id();
        LogicalModelDto.Relationship r = x.f.rel("고객 주문", x.customer, x.order, false, 1);
        x.f.pairs(r, new long[]{x.partyKind, orderKind}, new long[]{x.partyNo, orderPartyNo});
        x.relationship = r.id();
        x.top = x.area(null, "고객 관리");
        x.mid = x.area(x.top, "개인 고객 관리");
        x.leaf = x.area(x.mid, "우수 고객");
        x.assign(x.top, x.party, x.customer);
        x.assign(x.mid, x.person);
        x.assign(x.leaf, x.corp);
        if (big) {
            String text = "업무 설명 ".repeat(80);
            x.bigRoot = x.entity("대형루트", null);
            List<Long> key = new ArrayList<>();
            for (int a = 0; a < 40; a++) {
                long id = x.attr(x.bigRoot, "루트속성" + a, text, a < 2);
                if (a < 2) key.add(id);
            }
            x.identifier(x.bigRoot, "대형 PK", true, key.get(1), key.get(0));
            x.bigMiddle = x.entity("대형중간", null);
            for (int a = 0; a < 30; a++) x.attr(x.bigMiddle, "중간속성" + a, text, false);
            x.bigLeaf = x.entity("대형말단", null);
            for (int a = 0; a < 35; a++) x.attr(x.bigLeaf, "말단속성" + a, text, false);
            long other = x.entity("대형형제", null);
            long other2 = x.entity("대형형제2", null);
            x.subtypes.saveGroup(x.f.user, new LogicalSubtypeDto.Save(null, x.f.modelId, x.bigRoot, "대형 그룹", null, false, false, null,
                    List.of(new LogicalSubtypeDto.MemberInput(x.bigMiddle, null), new LogicalSubtypeDto.MemberInput(other, null)), null));
            x.subtypes.saveGroup(x.f.user, new LogicalSubtypeDto.Save(null, x.f.modelId, x.bigMiddle, "대형 하위 그룹", null, false, false, null,
                    List.of(new LogicalSubtypeDto.MemberInput(x.bigLeaf, null), new LogicalSubtypeDto.MemberInput(other2, null)), null));
            x.subtypes.selectInheritanceView(x.f.user.getProjectId(), x.bigLeaf).attributes().forEach(a -> x.bigLeafAttributes.add(a.attributeId()));
        }
        x.f.member(); // 관리자를 프로젝트 구성원으로 (실행 컨텍스트 재검증 — 내부 AI·MCP 경계)
        // 루트만 승인 (상속 속성의 승인 상태는 정의한 엔터티의 것)
        x.jdbc.update("update TB_LGCL_ENTT set APRV_YN = true where LGCL_ENTT_SN = ?", x.party);
        return x;
    }

    Long project() {
        return f.user.getProjectId();
    }

    private long entity(String name, String description) {
        long id = logical.saveEntity(f.user, new LogicalModelDto.Entity(null, f.modelId, name, description, null, null, null)).id();
        jdbc.update("update TB_LGCL_ENTT set APRV_YN = false where LGCL_ENTT_SN = ?", id);
        return id;
    }

    private long attr(long entity, String name, String description, boolean required) {
        return logical.saveAttribute(f.user, new LogicalModelDto.Attribute(null, entity, name, description, "문자", required, null, null, null, null, null)).id();
    }

    private long identifier(long entity, String name, boolean primary, long... attributes) {
        List<Long> ids = new ArrayList<>();
        for (long a : attributes) ids.add(a);
        return logical.saveIdentifier(f.user, new LogicalModelDto.Identifier(null, entity, name, primary, ids, null)).id();
    }

    private long area(Long parent, String name) {
        return areas.save(f.user, new LogicalSubjectAreaDto.Save(null, f.modelId, parent, name, name + " 영역", null, null)).id();
    }

    private void assign(long area, long... entities) {
        List<LogicalSubjectAreaDto.AssignmentItem> items = new ArrayList<>();
        for (long e : entities) items.add(new LogicalSubjectAreaDto.AssignmentItem(e, JsonNodeFactory.instance.nullNode()));
        areas.assign(f.user, f.modelId, new LogicalSubjectAreaDto.Assignment(JsonNodeFactory.instance.numberNode(area), items));
    }
}
