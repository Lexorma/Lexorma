package com.lexorma;

import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.domain.entity.ProjectMember;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.repository.ProjectMemberRepository;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.PairInput;
import com.lexorma.data.logical.domain.dto.LogicalModelDto.PairSave;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.mapping.domain.dto.PhysicalGenerationDto;
import com.lexorma.data.mapping.repository.EntityTableMappingRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 논리 → 물리 생성 통합 테스트용 준비 데이터 (M-7 보강 5차) : 새 프로젝트(관리자 로그인) + 표준 단어 + 논리 모델, 속성마다 전용 용어(약어 = 컬럼명)·공용 도메인.
 * 엔터티 이름은 등록한 단어로만 지어야 테이블명이 정해진다 (예 : 고객 → CUST, 주문 이력 → ORD_HSTRY).
 */
final class GenerationFixture {

    private static final AtomicLong IDS = new AtomicLong(875_000_000L);
    private static final String[][] WORDS = {{"고객", "CUST"}, {"주문", "ORD"}, {"직원", "EMP"}, {"부서", "DEPT"}, {"팀", "TEAM"}, {"공항", "ARPT"},
            {"항공편", "FLT"}, {"이력", "HSTRY"}, {"상품", "PRDT"}, {"배송", "DLVR"}, {"결제", "STLM"}, {"지점", "BRNCH"}};

    final AccountDto user;
    final Long modelId;
    private final LogicalModelService logical;
    private final JdbcTemplate jdbc;
    private final EntityTableMappingRepository entityMappings;
    private final Map<String, Long> domains = new HashMap<>();
    private ApplicationContext context;

    private GenerationFixture(AccountDto user, Long modelId, LogicalModelService logical, JdbcTemplate jdbc, EntityTableMappingRepository entityMappings) {
        this.user = user;
        this.modelId = modelId;
        this.logical = logical;
        this.jdbc = jdbc;
        this.entityMappings = entityMappings;
    }

    static long nextId() {
        return IDS.incrementAndGet();
    }

    static GenerationFixture create(ApplicationContext context, String name, DbType dbType) {
        ProjectRepository projects = context.getBean(ProjectRepository.class);
        JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
        LogicalModelService logical = context.getBean(LogicalModelService.class);
        Account admin = context.getBean(AccountRepository.class).findAll().stream().filter(a -> "admin".equals(a.getUsername())).findFirst().orElseThrow();
        Project project = projects.save(Project.builder().projectName(name).isActive(true).account(admin).dbType(dbType).build());
        AccountDto user = SecurityTestSupport.loginAs(projects, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        for (String[] w : WORDS) {
            jdbc.update("insert into TB_STD_WORD(STD_WORD_SN, PRJ_SN, ENCT_CYCL, WORD_NM, WORD_ENG_ABBR_NM, WORD_ENG_NM, WORD_EXPLN, FRMT_WORD_YN, DMN_CLSF_NM, ABBR_USE_YN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, ?, ?, ?, ?, false, null, true, true, current_timestamp, current_timestamp, 'test', 'test')", nextId(), project.getId(), w[0], w[1], w[1], w[0]);
        }
        LogicalModelDto.Model model = logical.saveModel(user, new LogicalModelDto.Model(null, name, null));
        GenerationFixture fixture = new GenerationFixture(user, model.id(), logical, jdbc, context.getBean(EntityTableMappingRepository.class));
        fixture.context = context;
        return fixture;
    }

    /** 프로젝트 구성원으로 등록한 로그인 (모델 버전·교환 대상처럼 구성원 검사를 하는 기능용) */
    AccountDto member() {
        ProjectRepository projects = context.getBean(ProjectRepository.class);
        if (count("select count(*) from TB_PRJ_MBR where PRJ_SN = ? and ACNT_SN = ?", user.getProjectId(), Long.valueOf(user.getId())) == 0) {
            context.getBean(ProjectMemberRepository.class).save(ProjectMember.builder().project(projects.findById(user.getProjectId()).orElseThrow())
                    .account(context.getBean(AccountRepository.class).findById(Long.valueOf(user.getId())).orElseThrow()).isActive(true).build());
        }
        return SecurityTestSupport.loginAs(projects, user.getProjectId(), user.getId(), "ROLE_ADMIN");
    }

    /** 같은 프로젝트의 다른 논리 모델 */
    GenerationFixture otherModel(String name) {
        GenerationFixture other = new GenerationFixture(user, logical.saveModel(user, new LogicalModelDto.Model(null, name, null)).id(), logical, jdbc, entityMappings);
        other.domains.putAll(domains);
        other.context = context;
        return other;
    }

    PhysicalGenerationDto.Request request(List<Long> entities) {
        return new PhysicalGenerationDto.Request(entities, null, null);
    }

    PhysicalGenerationDto.Request request(List<Long> entities, String token) {
        return new PhysicalGenerationDto.Request(entities, null, token);
    }

    long entity(String name) {
        return logical.saveEntity(user, new LogicalModelDto.Entity(null, modelId, name, null, null, null, null)).id();
    }

    long attr(long entity, String name, boolean required, String abbreviation) {
        return attrTyped(entity, name, required, abbreviation, "VARCHAR", 20);
    }

    long attrTyped(long entity, String name, boolean required, String abbreviation, String type, int length) {
        return logical.saveAttribute(user, new LogicalModelDto.Attribute(null, entity, name, null, null, required, null, term(abbreviation, type, length), null, null, null)).id();
    }

    /** 속성 하나에 쓸 표준 용어 (약어 = 컬럼명) */
    long term(String abbreviation, String type, int length) {
        long domain = domains.computeIfAbsent(type + length, key -> {
            long id = nextId();
            jdbc.update("insert into TB_STD_DMN(STD_DMN_SN, PRJ_SN, ENCT_CYCL, DMN_GROUP_NM, DMN_CLSF_NM, DMN_NM, DMN_EXPLN, DATA_TYPE_NM, DATA_LEN, DATA_DCPT_LEN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                    + " values (?, ?, 1, '테스트', '테스트', ?, ?, ?, ?, null, true, current_timestamp, current_timestamp, 'test', 'test')", id, user.getProjectId(), key, key, type, length);
            return id;
        });
        long term = nextId();
        jdbc.update("insert into TB_STD_TRM(STD_TRM_SN, PRJ_SN, ENCT_CYCL, TRM_NM, TRM_EXPLN, TRM_ENG_ABBR_NM, STD_DMN_SN, APRV_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, 1, ?, ?, ?, ?, true, current_timestamp, current_timestamp, 'test', 'test')", term, user.getProjectId(), abbreviation + "_" + term, abbreviation, abbreviation, domain);
        return term;
    }

    long attrNoTerm(long entity, String name) {
        return logical.saveAttribute(user, new LogicalModelDto.Attribute(null, entity, name, null, "문자", false, null, null, null, null, null)).id();
    }

    void pk(long entity, long... attributes) {
        List<Long> ids = new ArrayList<>();
        for (long a : attributes) ids.add(a);
        logical.saveIdentifier(user, new LogicalModelDto.Identifier(null, entity, "식별자", true, ids, null));
    }

    LogicalModelDto.Relationship rel(String name, long parent, long child, boolean identifying, int sourceMin) {
        LogicalModelDto.Relationship saved = logical.saveRelationship(user, new LogicalModelDto.Relationship(null, modelId, name, null, parent, null, child, null,
                identifying, sourceMin, 1, 0, null, null, null));
        jdbc.update("update TB_LGCL_REL set APRV_YN = false where LGCL_REL_SN = ?", saved.id());
        return saved;
    }

    void pairs(LogicalModelDto.Relationship relationship, long[]... pairs) {
        List<PairInput> inputs = new ArrayList<>();
        for (long[] p : pairs) inputs.add(new PairInput(p[0], p[1]));
        logical.saveRelationshipPairs(user, relationship.id(), new PairSave(logical.selectRelationshipPairs(user, relationship.id()).modifiedAt(), inputs));
    }

    long table(long entity) {
        return entityMappings.findByEntity_Id(entity).orElseThrow().getTable().getId();
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    /** 프로젝트의 물리 FK 관계 수 (REF_COLUMN 동기화 + 사용자 관계) */
    int foreignKeys() {
        return count("select count(*) from TB_TBL_REL where PRJ_SN = ? and REL_TYPE_CD = 'FK'", user.getProjectId());
    }

    int versions() {
        return count("select count(*) from TB_MDL_VER where PRJ_SN = ?", user.getProjectId());
    }
}
