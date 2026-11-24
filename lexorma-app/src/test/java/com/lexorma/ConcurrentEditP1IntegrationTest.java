package com.lexorma;

import com.lexorma.common.exception.CustomException;
import com.lexorma.core.project.domain.entity.Project;
import com.lexorma.core.project.repository.ProjectRepository;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.security.domain.entity.Account;
import com.lexorma.core.security.repository.AccountRepository;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.column.domain.dto.ColumnBulkSaveDto;
import com.lexorma.data.column.domain.dto.ColumnInfoDto;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.column.repository.ColumnInfoRepository;
import com.lexorma.data.column.service.ColumnManageService;
import com.lexorma.data.dictionary.domain.dto.StandardDomainDto;
import com.lexorma.data.dictionary.domain.dto.StandardWordDto;
import com.lexorma.data.dictionary.domain.entity.StandardDomain;
import com.lexorma.data.dictionary.domain.entity.StandardWord;
import com.lexorma.data.dictionary.repository.StandardDomainRepository;
import com.lexorma.data.dictionary.repository.StandardWordRepository;
import com.lexorma.data.dictionary.service.DataDictionaryService;
import com.lexorma.data.logical.domain.dto.LogicalModelDto;
import com.lexorma.data.logical.repository.LogicalRelationshipRepository;
import com.lexorma.data.logical.service.LogicalModelService;
import com.lexorma.data.physical.domain.dto.PhysicalModelDto;
import com.lexorma.data.physical.domain.entity.TableConstraint;
import com.lexorma.data.physical.domain.entity.TableRelation;
import com.lexorma.data.physical.repository.TableConstraintRepository;
import com.lexorma.data.physical.repository.TableRelationRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.schema.domain.dto.SchemaDto;
import com.lexorma.data.schema.domain.entity.SchemaInfo;
import com.lexorma.data.schema.repository.SchemaInfoRepository;
import com.lexorma.data.schema.service.SchemaService;
import com.lexorma.data.table.domain.entity.TableInfo;
import com.lexorma.data.table.repository.TableInfoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8-C1 동시 편집 P1 회귀 (M-8 분석 L-01 · D-01 · D-02 · P-01 · P-02, docs/ongoing/modeling-concurrency-m8.md). 각 P1 의 첫 동시 시나리오는 수정 전 코드로
 * 실제 손실을 재현했던 것이다 (H2 · PostgreSQL 모두 6건 실패 — 문서 기록) — 지금은 같은 실행 순서에서 손실 없이 거절·대기된다.
 * 두 트랜잭션을 실제로 엇갈린다 : A 가 트랜잭션 안에서 할 일을 마친 뒤 커밋 전에 B 를 다른 연결(다른 스레드)에서 실행하고, B 가 끝났거나 A 의 잠금을 기다리기
 * 시작한 것을 DB 세션 정보로 확인한 뒤(시간 대기 없음) A 를 커밋한다. 불변식 : B 가 성공했다고 답했으면 B 의 값이 남아 있어야 한다 (조용한 손실 없음).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class ConcurrentEditP1IntegrationTest {

    @Autowired ProjectRepository projectRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired TableInfoRepository tableRepository;
    @Autowired ColumnInfoRepository columnRepository;
    @Autowired SchemaInfoRepository schemaRepository;
    @Autowired SchemaService schemaService;
    @Autowired ColumnManageService columns;
    @Autowired LogicalModelService logical;
    @Autowired LogicalRelationshipRepository relationships;
    @Autowired DataDictionaryService dictionary;
    @Autowired StandardDomainRepository domains;
    @Autowired StandardWordRepository words;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired EntityManager entityManager;
    @Autowired com.lexorma.data.logical.controller.LogicalModelRestController logicalApi;
    @Autowired com.lexorma.data.dictionary.controller.DataDictionaryRestController dictionaryApi;
    @Autowired com.lexorma.data.column.controller.ColumnManageRestController columnApi;
    @Autowired com.lexorma.data.table.service.TableDesignService tableDesign;
    @Autowired PhysicalModelService physical;
    @Autowired TableRelationRepository relationRepository;
    @Autowired TableConstraintRepository constraintRepository;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    // ------------------------------------------------------------------ L-01 논리 관계 승인

    @Test
    @DisplayName("L-01 동시 : 관계 승인이 모델·관계를 잠근 채 커밋 전에 들어온 관계 수정은 승인 뒤의 상태를 보고 거절된다 — 조용한 손실 없음")
    void relationshipApprovalKeepsConcurrentEdit() throws Exception {
        AccountDto admin = login("m8c1-rel");
        LogicalModelDto.Relationship r = relationship(admin);
        String b = interleave(admin,
                () -> logical.updateRelationshipApproval(admin, r.id(), true, r.modifiedAt()),
                () -> logical.saveRelationship(admin, renamed(r, "B 수정")));
        var saved = tx.execute(s -> relationships.findById(r.id()).orElseThrow());
        if ("OK".equals(b)) assertThat(saved.getRelationshipName()).as("B 는 성공했다고 답했다 (B=%s)", b).isEqualTo("B 수정");
        assertThat(b).isIn("APPROVED_DATA_CANNOT_BE_UPDATE", "MODEL_CONFLICT");
        assertThat(saved.getIsApproval()).isTrue();
        assertThat(saved.getRelationshipName()).isEqualTo("원래");
    }

    @Test
    @DisplayName("L-01 기준값 : 오래된 기준값 409(관계·승인 그대로) · 기준값 없는 HTTP 400 · 지금 기준값 승인 · 이미 그 상태면 아무것도 하지 않음 · 다른 프로젝트 거절")
    void relationshipApprovalBase() {
        AccountDto admin = login("m8c1-rel2");
        LogicalModelDto.Relationship r = relationship(admin);
        logical.saveRelationship(admin, renamed(r, "다른 사용자 수정")); // 승인자가 본 뒤 바뀐다
        assertCode(() -> logical.updateRelationshipApproval(admin, r.id(), true, r.modifiedAt()), "MODEL_CONFLICT");
        var kept = tx.execute(s -> relationships.findById(r.id()).orElseThrow());
        assertThat(kept.getIsApproval()).isFalse();
        assertThat(kept.getRelationshipName()).isEqualTo("다른 사용자 수정");
        assertCode(() -> logicalApi.updateRelationshipApproval(admin, r.id(), true, null), "MODEL_BASE_REQUIRED");

        logical.updateRelationshipApproval(admin, r.id(), true, kept.modifiedAt());
        var approved = tx.execute(s -> relationships.findById(r.id()).orElseThrow());
        assertThat(approved.getIsApproval()).isTrue();
        assertThat(approved.getRelationshipName()).isEqualTo("다른 사용자 수정");
        // 이미 승인됨 : 예전 기준값이어도 바꾸는 것이 없다 (표준 용어 승인과 같다)
        logical.updateRelationshipApproval(admin, r.id(), true, r.modifiedAt());
        // 승인 취소도 같은 계약 : 오래된 기준값 409, 지금 기준값이면 취소
        assertCode(() -> logical.updateRelationshipApproval(admin, r.id(), false, kept.modifiedAt()), "MODEL_CONFLICT");
        logical.updateRelationshipApproval(admin, r.id(), false, approved.modifiedAt());
        assertThat(inTx(() -> relationships.findById(r.id()).orElseThrow().getIsApproval())).isFalse();

        AccountDto other = login("m8c1-rel3");
        assertCode(() -> logical.updateRelationshipApproval(other, r.id(), true, approved.modifiedAt()), "LOGICAL_MODEL_IS_NOT_DEFINED");
    }

    // ------------------------------------------------------------------ D-01 표준 도메인

    @Test
    @DisplayName("D-01 동시 : 도메인 승인이 행을 잠근 채 커밋 전에 들어온 수정은 승인된 도메인이라 거절된다 — 조용한 손실 없음")
    void domainApprovalKeepsConcurrentEdit() throws Exception {
        AccountDto admin = login("m8c1-dmn");
        StandardDomain d = domain(admin.getProjectId());
        StandardDomainDto row = domainEdit(d, 10);
        String b = interleave(admin,
                () -> dictionary.approvalStandardDomains(List.of(row), admin, true),
                () -> dictionary.updateStandardDomain(admin, domainEdit(d, 99, row.getModifiedAt())));
        StandardDomain saved = tx.execute(s -> domains.findById(d.getId()).orElseThrow());
        if ("OK".equals(b)) assertThat(saved.getDataLength()).as("B 는 성공했다고 답했다").isEqualTo(99);
        assertThat(b).isIn("APPROVED_DATA_CANNOT_BE_UPDATE", "MODEL_CONFLICT");
        assertThat(saved.getIsApproval()).isTrue();
        assertThat(saved.getDataLength()).isEqualTo(10);
    }

    @Test
    @DisplayName("D-01 동시 : 같은 기준값의 두 도메인 수정이 동시에 저장되면 하나만 반영되고 다른 하나는 409")
    void domainConcurrentUpdates() throws Exception {
        AccountDto admin = login("m8c1-dmn2");
        StandardDomain d = domain(admin.getProjectId());
        StandardDomainDto a = domainEdit(d, 11);
        StandardDomainDto bEdit = domainEdit(d, 22);
        String b = interleave(admin, () -> dictionary.updateStandardDomain(admin, a), () -> dictionary.updateStandardDomain(admin, bEdit));
        Integer length = tx.execute(s -> domains.findById(d.getId()).orElseThrow().getDataLength());
        if ("OK".equals(b)) assertThat(length).as("B 는 성공했다고 답했다").isEqualTo(22);
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(length).isEqualTo(11);
    }

    @Test
    @DisplayName("D-01 · D-03 : 승인된 도메인 수정 거절 → 승인 취소 → 수정 · 오래된 기준값 409 · 기준값 없는 HTTP 400(수정·승인) · 다른 프로젝트 · 승인도 오래된 기준값 409")
    void domainContract() {
        AccountDto admin = login("m8c1-dmn3");
        StandardDomain d = domain(admin.getProjectId());
        StandardDomainDto seen = domainEdit(d, 10);
        dictionary.approvalStandardDomains(List.of(seen), admin, true);
        assertCode(() -> dictionary.updateStandardDomain(admin, domainEdit(d, 30)), "APPROVED_DATA_CANNOT_BE_UPDATE");
        dictionary.approvalStandardDomains(List.of(domainEdit(d, 10)), admin, false); // 승인 취소 (지금 기준값)
        dictionary.updateStandardDomain(admin, domainEdit(d, 30));
        assertThat(inTx(() -> domains.findById(d.getId()).orElseThrow().getDataLength())).isEqualTo(30);
        // 오래된 기준값 (처음 본 화면) : 수정·승인 모두 409, 값 그대로
        assertCode(() -> dictionary.updateStandardDomain(admin, domainEdit(d, 40, seen.getModifiedAt())), "MODEL_CONFLICT");
        assertCode(() -> dictionary.approvalStandardDomains(List.of(seen), admin, true), "MODEL_CONFLICT");
        StandardDomain kept = tx.execute(s -> domains.findById(d.getId()).orElseThrow());
        assertThat(kept.getDataLength()).isEqualTo(30);
        assertThat(kept.getIsApproval()).isFalse();
        // HTTP : 기준값 없으면 400
        StandardDomainDto noBase = domainEdit(d, 50);
        noBase.setModifiedAt(null);
        assertCode(() -> dictionaryApi.updateStandardDomains(admin, noBase), "MODEL_BASE_REQUIRED");
        assertCode(() -> dictionaryApi.approvalStandardDomains(List.of(noBase), admin, true), "MODEL_BASE_REQUIRED");
        // 다른 프로젝트 : 없는 도메인
        AccountDto other = login("m8c1-dmn4");
        assertCode(() -> dictionary.updateStandardDomain(other, domainEdit(d, 60)), "NOT_FOUND_DOMAIN_DATA");
        dictionary.approvalStandardDomains(List.of(domainEdit(d, 30)), other, true); // 다른 프로젝트 행은 찾지 않는다 (예전처럼 건너뜀)
        assertThat(inTx(() -> domains.findById(d.getId()).orElseThrow().getIsApproval())).isFalse();
        // 조회 응답은 기준값을 싣는다
        assertThat(seen.getModifiedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ D-02 표준 단어

    @Test
    @DisplayName("D-02 동시 : 단어 승인이 행을 잠근 채 커밋 전에 들어온 수정은 승인 뒤 기준값이 달라 409 — 조용한 손실 없음")
    void wordApprovalKeepsConcurrentEdit() throws Exception {
        AccountDto admin = login("m8c1-word");
        StandardWord w = word(admin.getProjectId());
        StandardWordDto row = tx.execute(s -> StandardWordDto.fromEntity(words.findById(w.getId()).orElseThrow()));
        StandardWordDto edit = wordEdit(w, "B 설명");
        String b = interleave(admin,
                () -> dictionary.approvalStandardWords(List.of(row), admin, true),
                () -> dictionary.updateStandardWords(admin, edit));
        StandardWord saved = tx.execute(s -> words.findById(w.getId()).orElseThrow());
        if ("OK".equals(b)) assertThat(saved.getCommonStandardWordDescription()).as("B 는 성공했다고 답했다").isEqualTo("B 설명");
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(saved.getIsApproval()).isTrue();
        assertThat(saved.getCommonStandardWordDescription()).isEqualTo("처음");
    }

    @Test
    @DisplayName("D-02 : 같은 기준값 두 수정 동시 → 하나만 · 오래된 기준값 수정·승인 409 · 기준값 없는 HTTP 400 · 금칙어만 바꿔도 기준값이 움직인다 · 다른 프로젝트")
    void wordContract() throws Exception {
        AccountDto admin = login("m8c1-word2");
        StandardWord w = word(admin.getProjectId());
        StandardWordDto a = wordEdit(w, "A 설명");
        StandardWordDto bEdit = wordEdit(w, "B 설명");
        String b = interleave(admin, () -> dictionary.updateStandardWords(admin, a), () -> dictionary.updateStandardWords(admin, bEdit));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> words.findById(w.getId()).orElseThrow().getCommonStandardWordDescription())).isEqualTo("A 설명");
        assertCode(() -> dictionary.updateStandardWords(admin, bEdit), "MODEL_CONFLICT");
        assertCode(() -> dictionary.approvalStandardWords(List.of(bEdit), admin, true), "MODEL_CONFLICT");

        StandardWordDto restricted = wordEdit(w, "A 설명");
        restricted.setRestrictedWords("금칙");
        dictionary.updateStandardWords(admin, restricted);
        assertCode(() -> dictionary.updateStandardWords(admin, restricted), "MODEL_CONFLICT"); // 금칙어만 바뀐 저장도 기준값을 옮겼다

        StandardWordDto noBase = wordEdit(w, "C 설명");
        noBase.setModifiedAt(null);
        assertCode(() -> dictionaryApi.updateStandardWords(admin, noBase), "MODEL_BASE_REQUIRED");
        assertCode(() -> dictionaryApi.approvalStandardWords(List.of(noBase), admin, true), "MODEL_BASE_REQUIRED");
        AccountDto other = login("m8c1-word3");
        assertCode(() -> dictionary.updateStandardWords(other, wordEdit(w, "D 설명")), "NOT_FOUND_WORD_DATA");
        dictionary.approvalStandardWords(List.of(wordEdit(w, "A 설명")), admin, true);
        assertThat(inTx(() -> words.findById(w.getId()).orElseThrow().getIsApproval())).isTrue();
    }

    // ------------------------------------------------------------------ P-01 컬럼 순서

    @Test
    @DisplayName("P-01 : 오래된 순서 팝업은 409 · 다른 사용자가 맨 앞으로 옮긴 순서 그대로 · 다시 연 팝업이면 저장")
    void staleSortOrder() {
        AccountDto admin = login("m8c1-ord");
        TableInfo table = table(admin.getProjectId(), "TB_ORD", false);
        List<ColumnInfo> cols = List.of(column(table, "A_COL", 1, null), column(table, "B_COL", 2, null), column(table, "C_COL", 3, null));
        // 다른 사용자 : C 를 맨 앞으로 (A1 B2 C3 → C1 A2 B3)
        columns.updateSortOrder(admin, List.of(order(cols.get(2), 1, 3), order(cols.get(0), 2, 1), order(cols.get(1), 3, 2)));
        // 나 : 처음 연 팝업(A1 B2 C3)에서 A·B 만 바꿔 저장 → 409, 아무것도 바뀌지 않는다
        assertCode(() -> columns.updateSortOrder(admin, List.of(order(cols.get(1), 1, 2), order(cols.get(0), 2, 1), order(cols.get(2), 3, 3))), "MODEL_CONFLICT");
        assertThat(orders(cols)).containsExactly(2, 3, 1);
        // 다시 연 팝업 (C1 A2 B3) 에서 A·B 를 바꾼다
        columns.updateSortOrder(admin, List.of(order(cols.get(2), 1, 1), order(cols.get(1), 2, 3), order(cols.get(0), 3, 2)));
        assertThat(orders(cols)).containsExactly(3, 2, 1);
        // 기준값 없는 HTTP 요청 400
        assertCode(() -> columnApi.updateSortOrder(List.of(ColumnInfoDto.builder().id(cols.get(0).getId()).sortOrder(1).build()), admin), "MODEL_BASE_REQUIRED");
    }

    @Test
    @DisplayName("P-01 동시 : 같은 팝업에서 연 두 순서 저장이 동시에 들어오면 하나만 반영되고 다른 하나는 409 · 다른 테이블의 순서 저장은 서로 막지 않는다")
    void concurrentSortOrder() throws Exception {
        AccountDto admin = login("m8c1-ord2");
        TableInfo table = table(admin.getProjectId(), "TB_ORD", false);
        List<ColumnInfo> cols = List.of(column(table, "A_COL", 1, null), column(table, "B_COL", 2, null));
        TableInfo other = table(admin.getProjectId(), "TB_ORD_OTHER", false);
        List<ColumnInfo> others = List.of(column(other, "X_COL", 1, null), column(other, "Y_COL", 2, null));
        String b = interleave(admin,
                () -> columns.updateSortOrder(admin, List.of(order(cols.get(1), 1, 2), order(cols.get(0), 2, 1))),
                () -> columns.updateSortOrder(admin, List.of(order(cols.get(0), 1, 1), order(cols.get(1), 2, 2))));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(orders(cols)).containsExactly(2, 1);
        String parallel = interleave(admin,
                () -> columns.updateSortOrder(admin, List.of(order(cols.get(0), 1, 2), order(cols.get(1), 2, 1))),
                () -> columns.updateSortOrder(admin, List.of(order(others.get(1), 1, 2), order(others.get(0), 2, 1))));
        assertThat(parallel).isEqualTo("OK");
        assertThat(orders(cols)).containsExactly(1, 2);
        assertThat(orders(others)).containsExactly(2, 1);
    }

    @Test
    @DisplayName("P-01 구성 : 팝업을 연 뒤 컬럼이 더해지거나 지워졌으면 409(순서 그대로) · 한 컬럼만 순서가 달라도 전체를 되돌린다 · 다른 테이블 컬럼을 섞으면 400 · 승인된 컬럼 400")
    void sortOrderComposition() {
        AccountDto admin = login("m8c1-ord3");
        TableInfo table = table(admin.getProjectId(), "TB_ORD", false);
        List<ColumnInfo> cols = List.of(column(table, "A_COL", 1, null), column(table, "B_COL", 2, null));
        ColumnInfo added = column(table, "C_COL", 3, null);
        assertCode(() -> columns.updateSortOrder(admin, List.of(order(cols.get(1), 1, 2), order(cols.get(0), 2, 1))), "MODEL_CONFLICT");
        tx.executeWithoutResult(s -> columnRepository.deleteById(added.getId()));
        assertCode(() -> columns.updateSortOrder(admin, List.of(order(cols.get(1), 1, 2), order(cols.get(0), 2, 1), order(added, 3, 3))), "MODEL_CONFLICT");
        // 한 행만 기준값이 다르다 (B 는 2 였다고 보냈지만 지금은 2 — A 가 5 였다고 보냄) : 아무것도 바꾸지 않는다
        assertCode(() -> columns.updateSortOrder(admin, List.of(order(cols.get(1), 1, 2), order(cols.get(0), 2, 5))), "MODEL_CONFLICT");
        assertThat(orders(cols)).containsExactly(1, 2);
        TableInfo other = table(admin.getProjectId(), "TB_ORD_OTHER", false);
        ColumnInfo x = column(other, "X_COL", 1, null);
        assertCode(() -> columns.updateSortOrder(admin, List.of(order(cols.get(0), 1, 1), order(cols.get(1), 2, 2), order(x, 3, 1))), "INVALID_PHYSICAL_MODEL");
        tx.executeWithoutResult(s -> columnRepository.findById(cols.get(0).getId()).orElseThrow().setIsApproval(true));
        assertCode(() -> columns.updateSortOrder(admin, List.of(order(cols.get(1), 1, 2), order(cols.get(0), 2, 1))), "APPROVED_DATA_CANNOT_BE_UPDATE");
        assertThat(orders(cols)).containsExactly(1, 2);
    }

    // ------------------------------------------------------------------ P-02 스키마 이름 변경

    @Test
    @DisplayName("P-02 동시 : 스키마 이름 변경이 참조 컬럼을 잠근 채 커밋 전에 들어온 참조 수정은 기다렸다 반영된다 — 덮어쓰지 않는다")
    void schemaRenameKeepsConcurrentReference() throws Exception {
        AccountDto admin = login("m8c1-sch");
        SchemaInfo sales = schema(admin, "SALES");
        tx.executeWithoutResult(s -> tableRepository.save(TableInfo.builder().projectId(admin.getProjectId()).schema(schemaRepository.getReferenceById(sales.getId())).tableName("TB_TGT").isApproval(false).build()));
        TableInfo owner = table(admin.getProjectId(), "TB_OWN", true);
        ColumnInfo ref = column(owner, "TGT_ID", 1, "SALES.TB_TGT");
        String b = interleave(admin,
                () -> schemaService.saveSchema(admin, rename(sales, "MART")),
                () -> tx.executeWithoutResult(s -> entityManager.find(ColumnInfo.class, ref.getId(), LockModeType.PESSIMISTIC_WRITE).setRefTableName("TB_OWN")));
        assertThat(b).isEqualTo("OK");
        assertThat(inTx(() -> columnRepository.findById(ref.getId()).orElseThrow().getRefTableName())).isEqualTo("TB_OWN");
        assertThat(inTx(() -> schemaRepository.findById(sales.getId()).orElseThrow().getSchemaName())).isEqualTo("MART");
    }

    @Test
    @DisplayName("P-02 동시 : 다른 쓰기가 참조 컬럼을 먼저 잡고 있으면 이름 변경은 기다리지 않고 409 — 스키마 이름·모든 참조 그대로 (전체 롤백)")
    void schemaRenameBusyColumn() throws Exception {
        AccountDto admin = login("m8c1-sch2");
        SchemaInfo sales = schema(admin, "SALES");
        TableInfo owner = table(admin.getProjectId(), "TB_OWN", true);
        ColumnInfo busy = column(owner, "TGT_ID", 1, "SALES.TB_TGT");
        ColumnInfo free = column(owner, "TGT2_ID", 2, "SALES.TB_TGT2");
        String b = interleave(admin,
                () -> entityManager.find(ColumnInfo.class, busy.getId(), LockModeType.PESSIMISTIC_WRITE).setRefTableName("TB_OWN"),
                () -> schemaService.saveSchema(admin, rename(sales, "MART")));
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> schemaRepository.findById(sales.getId()).orElseThrow().getSchemaName())).isEqualTo("SALES");
        assertThat(inTx(() -> columnRepository.findById(busy.getId()).orElseThrow().getRefTableName())).isEqualTo("TB_OWN");
        assertThat(inTx(() -> columnRepository.findById(free.getId()).orElseThrow().getRefTableName())).isEqualTo("SALES.TB_TGT2");
    }

    @Test
    @DisplayName("P-02 동시 : 이름 변경 중 들어온 컬럼 일괄 저장(예전 화면)은 이름 변경 뒤 기준값이 달라 409 — 이름 변경이 고친 참조 그대로")
    void schemaRenameWithBulkSave() throws Exception {
        AccountDto admin = login("m8c1-sch3");
        SchemaInfo sales = schema(admin, "SALES");
        TableInfo owner = table(admin.getProjectId(), "TB_OWN", true);
        ColumnInfo ref = column(owner, "TGT_ID", 1, "SALES.TB_TGT");
        ColumnBulkSaveDto.Row row = tx.execute(s -> bulkRow(ColumnInfoDto.fromEntity(columnRepository.findById(ref.getId()).orElseThrow())));
        row.setRefTableName(null);
        String b = interleave(admin,
                () -> schemaService.saveSchema(admin, rename(sales, "MART")),
                () -> {
                    ColumnBulkSaveDto.Result result = columns.saveColumnsBulk(admin, ColumnBulkSaveDto.builder().tableInfoId(owner.getId()).rows(List.of(row)).build());
                    if (!result.result()) throw new IllegalStateException(String.valueOf(result.errors()));
                });
        assertThat(b).isEqualTo("MODEL_CONFLICT");
        assertThat(inTx(() -> columnRepository.findById(ref.getId()).orElseThrow().getRefTableName())).isEqualTo("MART.TB_TGT");
    }

    @Test
    @DisplayName("P-02 : 이름 변경은 이 스키마를 가리키는 컬럼만(여러 테이블) 고친다 — 다른 참조로 바뀐 컬럼·다른 프로젝트의 같은 스키마명 그대로, 설명만 바꾸면 참조를 건드리지 않는다")
    void schemaRenameScope() {
        AccountDto admin = login("m8c1-sch4");
        SchemaInfo sales = schema(admin, "SALES");
        TableInfo one = table(admin.getProjectId(), "TB_ONE", true);
        TableInfo two = table(admin.getProjectId(), "TB_TWO", true);
        ColumnInfo a = column(one, "A_ID", 1, "SALES.TB_TGT");
        ColumnInfo b = column(two, "B_ID", 1, "SALES.TB_TGT");
        ColumnInfo changed = column(two, "C_ID", 2, "SALESX.TB_TGT");
        AccountDto otherAccount = login("m8c1-sch5");
        schema(otherAccount, "SALES");
        TableInfo foreign = table(otherAccount.getProjectId(), "TB_FOREIGN", true);
        ColumnInfo outside = column(foreign, "F_ID", 1, "SALES.TB_TGT");
        SecurityTestSupport.loginAs(projectRepository, admin.getProjectId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
        schemaService.saveSchema(admin, new SchemaDto(sales.getId(), "SALES", "설명만", false, null, null, base(sales), null, null));
        assertThat(refs(a, b, changed, outside)).containsExactly("SALES.TB_TGT", "SALES.TB_TGT", "SALESX.TB_TGT", "SALES.TB_TGT");
        // 화면은 저장 뒤 목록을 다시 불러와 그 updated 를 기준값으로 쓴다 (저장 응답의 updated 는 반영 전 값이다 — 기존 동작)
        schemaService.saveSchema(admin, new SchemaDto(sales.getId(), "MART", "설명만", false, null, null, base(sales), null, null));
        assertThat(refs(a, b, changed, outside)).containsExactly("MART.TB_TGT", "MART.TB_TGT", "SALESX.TB_TGT", "SALES.TB_TGT");
    }

    // ------------------------------------------------------------------ 동시 삭제 뒤 500

    @Test
    @DisplayName("동시 삭제 뒤 : 없는 테이블 삭제·없는 컬럼 삭제·없는 컬럼 승인은 대상 없음 400 (예전 NullPointerException 500)")
    void deletedTargets() {
        AccountDto admin = login("m8c1-del");
        TableInfo table = table(admin.getProjectId(), "TB_DEL", false);
        ColumnInfo column = column(table, "DEL_ID", 1, null);
        tx.executeWithoutResult(s -> columnRepository.deleteById(column.getId()));
        assertCode(() -> columns.deleteColumn(admin, List.of(ColumnInfoDto.builder().id(column.getId()).build())), "COLUMN_IS_NOT_DEFINED");
        assertCode(() -> columns.updateColumnApproval(admin, List.of(ColumnInfoDto.builder().id(column.getId()).build()), "true"), "COLUMN_IS_NOT_DEFINED");
        tx.executeWithoutResult(s -> tableRepository.deleteById(table.getId()));
        assertCode(() -> tableDesign.deleteTable(List.of(com.lexorma.data.table.domain.dto.TableInfoDto.builder().id(table.getId()).build()), admin), "TABLE_IS_NOT_DEFINED");
    }

    @Test
    @DisplayName("동시 삭제 뒤 : 화면이 읽은 뒤(같은 트랜잭션에 올라와 있던) 다른 사용자가 지운 관계의 규칙 수정·삭제, 제약조건 삭제는 대상 없음 400 (예전 500)")
    void deletedWhileLoaded() {
        AccountDto admin = login("m8c1-del2");
        TableInfo parent = table(admin.getProjectId(), "TB_PARENT", false);
        TableInfo child = table(admin.getProjectId(), "TB_CHILD", false);
        Long relationId = tx.execute(s -> relationRepository.save(TableRelation.builder().projectId(admin.getProjectId()).sourceTable(tableRepository.getReferenceById(child.getId()))
                .targetTable(tableRepository.getReferenceById(parent.getId())).relationName("FK_M8C1").relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER)
                .isApproval(false).build()).getId());
        Long constraintId = tx.execute(s -> constraintRepository.save(TableConstraint.builder().projectId(admin.getProjectId()).tableInfo(tableRepository.getReferenceById(child.getId()))
                .constraintName("CK_M8C1").constraintType(TableConstraint.Type.CHECK).checkExpression("1 = 1").isApproval(false).build()).getId());
        var base = tx.execute(s -> relationRepository.findById(relationId).orElseThrow().modifiedAt());
        assertThat(deletedMeanwhile(relationRepository::findById, relationId, () -> relationRepository.deleteById(relationId),
                () -> physical.updateRelationRules(admin, relationId, new PhysicalModelDto.RelationRules("CASCADE", null, base)))).isEqualTo("INVALID_PHYSICAL_MODEL");
        Long relation2 = tx.execute(s -> relationRepository.save(TableRelation.builder().projectId(admin.getProjectId()).sourceTable(tableRepository.getReferenceById(child.getId()))
                .targetTable(tableRepository.getReferenceById(parent.getId())).relationName("FK_M8C1_2").relationType(TableRelation.Type.FK).relationSource(TableRelation.Source.USER)
                .isApproval(false).build()).getId());
        assertThat(deletedMeanwhile(relationRepository::findById, relation2, () -> relationRepository.deleteById(relation2),
                () -> physical.delete(admin, PhysicalModelService.Kind.RELATION, relation2, null))).isEqualTo("INVALID_PHYSICAL_MODEL");
        assertThat(deletedMeanwhile(constraintRepository::findById, constraintId, () -> constraintRepository.deleteById(constraintId),
                () -> physical.delete(admin, PhysicalModelService.Kind.CONSTRAINT, constraintId, null))).isEqualTo("INVALID_PHYSICAL_MODEL");
    }

    /** 한 트랜잭션에서 대상을 먼저 읽어 두고(화면 요청이 같은 행을 이미 올려 둔 상태), 다른 트랜잭션이 지우고 커밋한 뒤 같은 트랜잭션에서 작업을 부른다 */
    private <T> String deletedMeanwhile(java.util.function.Function<Long, java.util.Optional<T>> load, Long id, Runnable delete, Runnable work) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            return tx.execute(s -> {
                load.apply(id).orElseThrow();
                try {
                    pool.submit(() -> tx.executeWithoutResult(x -> delete.run())).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                String result = outcome(work);
                s.setRollbackOnly();
                return result;
            });
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ 동시 실행 도구

    /**
     * A 를 한 트랜잭션에서 실행하고, 커밋 전에 B 를 다른 스레드(다른 DB 연결)에서 실행한다. B 가 끝났거나 잠금을 기다리기 시작하면 A 를 커밋한다.
     * 결과는 B 의 결과 ("OK" 또는 오류 코드·예외 이름). A 의 결과는 버리지 않고 B 결과 뒤에 붙이지 않는다 (A 는 대부분 성공)
     */
    private String interleave(AccountDto account, Runnable a, Runnable b) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> other = tx.execute(s -> {
                a.run();
                Future<String> f = pool.submit(() -> {
                    SecurityTestSupport.loginAs(projectRepository, account.getProjectId(), String.valueOf(account.getId()), "ROLE_ADMIN");
                    try {
                        return outcome(b);
                    } finally {
                        SecurityTestSupport.clear();
                    }
                });
                awaitDoneOrLockWait(f);
                return f;
            });
            return other.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String outcome(Runnable call) {
        try {
            call.run();
            return "OK";
        } catch (CustomException e) {
            return e.getErrorCode().name();
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        }
    }

    /** 작업이 끝났거나 다른 트랜잭션의 잠금을 기다리기 시작할 때까지 (최대 20초) */
    private void awaitDoneOrLockWait(Future<?> work) {
        String sql = postgres() ? "select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'"
                : "select count(*) from INFORMATION_SCHEMA.SESSIONS where BLOCKER_ID is not null";
        long started = System.nanoTime();
        long deadline = started + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (work.isDone()) return;
            if (jdbc.queryForObject(sql, Integer.class) > 0) return;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // 진단 (M8-C4a) : 스레드·Hikari·DB 세션·AuthUtil 컨텍스트 — 시간 제한은 그대로 두고 원인을 남긴다
        throw new IllegalStateException("B 가 끝나지도, 잠금을 기다리지도 않았다"
                + ConcurrencyDiagnostics.describe("B 대기", started, jdbc, dataSource, projectRepository));
    }

    private boolean postgres() {
        try {
            return jdbc.queryForObject("select version()", String.class).contains("PostgreSQL");
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 준비

    private AccountDto login(String prefix) {
        Account admin = accountRepository.findAll().stream().filter(account -> "admin".equals(account.getUsername())).findFirst().orElseThrow();
        Project project = projectRepository.save(Project.builder().projectName(prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).isActive(true).account(admin).build());
        return SecurityTestSupport.loginAs(projectRepository, project.getId(), String.valueOf(admin.getId()), "ROLE_ADMIN");
    }

    /** 미승인 관계 (관리자가 만들면 승인 상태라 승인을 푼다) */
    private LogicalModelDto.Relationship relationship(AccountDto admin) {
        var model = logical.saveModel(admin, new LogicalModelDto.Model(null, "m8c1", null));
        var source = logical.saveEntity(admin, new LogicalModelDto.Entity(null, model.id(), "고객", null, null, null, null));
        var target = logical.saveEntity(admin, new LogicalModelDto.Entity(null, model.id(), "주문", null, null, null, null));
        var r = logical.saveRelationship(admin, new LogicalModelDto.Relationship(null, model.id(), "원래", null, source.id(), null, target.id(), null,
                false, 1, 1, 0, null, null, null));
        tx.executeWithoutResult(s -> relationships.findById(r.id()).orElseThrow().setIsApproval(false));
        return tx.execute(s -> LogicalModelDto.Relationship.fromEntity(relationships.findById(r.id()).orElseThrow()));
    }

    private static LogicalModelDto.Relationship renamed(LogicalModelDto.Relationship r, String name) {
        return new LogicalModelDto.Relationship(r.id(), r.modelId(), name, r.relationshipDescription(), r.sourceEntityId(), r.sourceEntityName(), r.targetEntityId(),
                r.targetEntityName(), r.isIdentifying(), r.sourceMin(), r.sourceMax(), r.targetMin(), r.targetMax(), r.cardinality(), r.isApproval(), r.modifiedAt(), r.attributePairs());
    }

    private StandardDomain domain(Long projectId) {
        return tx.execute(s -> domains.save(StandardDomain.fromEntity(StandardDomainDto.builder().projectId(projectId).revisionNumber(1).commonStandardDomainGroupName("문자")
                .commonStandardDomainCategory("명").commonStandardDomainName("명V" + UUID.randomUUID().toString().substring(0, 4)).commonStandardDomainDescription("처음")
                .dataType("VARCHAR").dataLength(10).dataDecimalLength(0).isApprovalYn("N").build())));
    }

    /** 지금 행을 읽은 화면의 수정 요청 (기준값 = 지금 MDFCN_DT) */
    private StandardDomainDto domainEdit(StandardDomain d, int length) {
        StandardDomainDto dto = tx.execute(s -> StandardDomainDto.fromEntity(domains.findById(d.getId()).orElseThrow()));
        dto.setDataLength(length);
        return dto;
    }

    private StandardDomainDto domainEdit(StandardDomain d, int length, java.time.LocalDateTime base) {
        StandardDomainDto dto = domainEdit(d, length);
        dto.setModifiedAt(base);
        return dto;
    }

    private StandardWord word(Long projectId) {
        return tx.execute(s -> words.save(StandardWord.fromEntity(StandardWordDto.builder().projectId(projectId).revisionNumber(1).commonStandardWordName("동시" + UUID.randomUUID().toString().substring(0, 4))
                .commonStandardWordAbbreviation("CNCR").commonStandardWordEnglishName("Concurrent").commonStandardWordDescription("처음").useAbbreviation("Y").isFormatWord("N")
                .synonyms("").restrictedWords("").isApprovalYn("N").build())));
    }

    private StandardWordDto wordEdit(StandardWord w, String description) {
        StandardWordDto dto = tx.execute(s -> StandardWordDto.fromEntity(words.findById(w.getId()).orElseThrow()));
        dto.setCommonStandardWordDescription(description);
        return dto;
    }

    private TableInfo table(long projectId, String name, boolean approved) {
        return inTx(() -> tableRepository.save(TableInfo.builder().projectId(projectId).tableName(name).isApproval(approved)
                .schema(schemaService.resolveSchema(projectId, null)).build()));
    }

    private ColumnInfo column(TableInfo table, String name, int order, String ref) {
        return inTx(() -> columnRepository.save(ColumnInfo.builder().projectId(table.getProjectId()).tableName(table.getTableName())
                .tableInfo(tableRepository.getReferenceById(table.getId())).columnName(name).columnDesc(name).sortOrder(order).refTableName(ref)
                .dataType("BIGINT").isPk(false).isNullable(true).isApproval(false).build()));
    }

    /** 순서 팝업의 한 행 : 새 순서 + 팝업을 연 때의 순서 (기준값) */
    private static ColumnInfoDto order(ColumnInfo column, int sortOrder, int seen) {
        return ColumnInfoDto.builder().id(column.getId()).sortOrder(sortOrder).baseSortOrder(seen).build();
    }

    private List<Integer> orders(List<ColumnInfo> cols) {
        return tx.execute(s -> cols.stream().map(c -> columnRepository.findById(c.getId()).orElseThrow().getSortOrder()).toList());
    }

    private List<String> refs(ColumnInfo... cols) {
        return tx.execute(s -> java.util.Arrays.stream(cols).map(c -> columnRepository.findById(c.getId()).orElseThrow().getRefTableName()).toList());
    }

    private SchemaInfo schema(AccountDto account, String name) {
        return tx.execute(s -> schemaRepository.save(SchemaInfo.builder().projectId(account.getProjectId()).schemaName(name).isDefault(false).build()));
    }

    private java.time.LocalDateTime base(SchemaInfo schema) {
        return tx.execute(s -> schemaRepository.findById(schema.getId()).orElseThrow().modifiedAt());
    }

    private SchemaDto rename(SchemaInfo schema, String name) {
        return new SchemaDto(schema.getId(), name, null, false, null, null, base(schema), null, null);
    }

    private static void assertCode(Runnable call, String code) {
        assertThat(outcome(call)).isEqualTo(code);
    }

    private static ColumnBulkSaveDto.Row bulkRow(ColumnInfoDto c) {
        return ColumnBulkSaveDto.Row.builder().key(String.valueOf(c.getId())).id(c.getId()).columnDesc(c.getColumnDesc()).dataType(c.getDataType()).maxLength(c.getMaxLength())
                .precision(c.getPrecision()).scale(c.getScale()).defaultValue(c.getDefaultValue()).identity(c.getIdentity()).refTableName(c.getRefTableName())
                .example(c.getExample()).isPk(c.getIsPk()).isNullable(c.getIsNullable()).isRequired(c.getIsRequired()).isUnique(c.getIsUnique()).isIndex(c.getIsIndex())
                .isMasterData(c.getIsMasterData()).isSensitive(c.getIsSensitive()).isEncrypted(c.getIsEncrypted()).baseModifiedAt(c.getModifiedAt()).build();
    }

    private <T> T inTx(Supplier<T> supplier) {
        return tx.execute(s -> supplier.get());
    }
}
