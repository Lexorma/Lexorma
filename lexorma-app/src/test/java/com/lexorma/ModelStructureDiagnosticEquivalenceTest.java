package com.lexorma;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.core.support.SecurityTestSupport;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticDto;
import com.lexorma.data.physical.diagnostic.ModelStructureDiagnosticService;
import com.lexorma.data.physical.repository.TableIndexRepository;
import com.lexorma.data.physical.service.PhysicalModelService;
import com.lexorma.data.table.repository.TableInfoRepository;
import com.lexorma.data.table.service.TableDesignService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M-DIAG-PERF (docs/completed/physical-diagnostics-performance.md) : 일괄 조회로 바꾼 물리 모델 구조 진단이 예전 구현({@link LegacyModelStructureDiagnostic} — 테이블마다 조회)과
 * 같은 모델에서 결과(건수·코드·심각도·대상 ID·이름·관련 값·승인·순서·요약)가 같은지, 쿼리 수가 테이블 수와 무관한지 본다.
 * 순서 : 테이블 순서와 테이블 안 묶음(참조 컬럼 → 관계 → 인덱스) 순서는 그대로, 예전에 정렬 없이 읽던 테이블 안 컬럼·관계 순서는 ID 순으로 고정했다 ({@link LegacyModelStructureDiagnostic#normalizeOrder}).
 * 데이터는 JDBC 로 직접 넣는다 : 등록 API 가 막는 상태(등록 뒤 키가 바뀐 FK, 같은 정의 인덱스, 대소문자가 섞인 이름 등)도 진단 대상이기 때문이다
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class ModelStructureDiagnosticEquivalenceTest {

    @Autowired ApplicationContext context;
    @Autowired ModelStructureDiagnosticService service;
    @Autowired TableInfoRepository tableInfoRepository;
    @Autowired TableIndexRepository tableIndexRepository;
    @Autowired TableDesignService tableDesignService;
    @Autowired PhysicalModelService physicalModelService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManagerFactory emf;

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    private ModelStructureDiagnosticDto.Result legacy(AccountDto user, Long schemaId) {
        LegacyModelStructureDiagnostic legacy = new LegacyModelStructureDiagnostic(tableInfoRepository, tableIndexRepository, tableDesignService, physicalModelService);
        // 운영의 @Transactional(readOnly = true) 와 같게 (쓰기 트랜잭션이면 조회마다 자동 flush 가 일어난다)
        TransactionTemplate readOnly = new TransactionTemplate(tx.getTransactionManager());
        readOnly.setReadOnly(true);
        return readOnly.execute(status -> legacy.diagnose(user, schemaId));
    }

    private void assertSame(AccountDto user, Long schemaId) {
        ModelStructureDiagnosticDto.Result expected = legacy(user, schemaId);
        ModelStructureDiagnosticDto.Result actual = service.diagnose(user, schemaId);
        assertThat(actual.summary()).as("요약 (schemaId " + schemaId + ")").isEqualTo(expected.summary());
        // 테이블 순서·묶음 순서는 그대로 같아야 하고, 예전 구현이 보장하지 않던 테이블 안 컬럼·관계 순서는 ID 순으로 고정됐다
        assertThat(actual.issues()).as("결과 · 순서 (schemaId " + schemaId + ")").containsExactlyElementsOf(LegacyModelStructureDiagnostic.normalizeOrder(expected.issues()));
        assertThat(actual.issues()).as("새 구현은 이미 그 순서").containsExactlyElementsOf(LegacyModelStructureDiagnostic.normalizeOrder(actual.issues()));
    }

    @Test
    @DisplayName("규칙별 데이터를 섞은 모델 (테이블 200·3 스키마) : 전체·스키마별·다른 스키마 ID 진단이 예전 구현과 같다, 모든 진단 종류가 나온다")
    void sameAsLegacyOnMixedModel() {
        Seeded m = seed(200);
        assertSame(m.user, null);
        for (long schema : m.schemas) assertSame(m.user, schema);
        assertSame(m.user, -1L);
        Set<ModelStructureDiagnosticDto.IssueType> types = new HashSet<>();
        service.diagnose(m.user, null).issues().forEach(i -> types.add(i.type()));
        assertThat(types).as("모든 진단 종류가 실제로 비교됐다").containsExactlyInAnyOrder(ModelStructureDiagnosticDto.IssueType.values());
    }

    @Test
    @DisplayName("경계 사례 : 대소문자 섞인 이름·스키마명.테이블명·공백·모호한 이름의 힌트(같은 이름 다른 컬럼), 쌍 없는 관계, 빈 UNIQUE 제약, 내림차순·INCLUDE·표현식 인덱스, 빈 프로젝트, 다른 프로젝트 격리")
    void sameAsLegacyOnEdgeCases() {
        Seeded m = seed(30);
        long p = m.user.getProjectId();
        long b = m.base + 900_000;
        // 대소문자 섞인 테이블명 : 'Tb_Mixed' 는 대문자 이름과 정확히 같지 않아 찾지 못한다 (예전과 같아야 한다)
        jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (?, ?, ?, 'Tb_Mixed', false)", b, p, m.schemas[0]);
        jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, COL_NM, PK_YN, SORT_ORD) values (?, ?, ?, 'ID', true, 1)", b + 1, p, b);
        long t = m.tables[5];
        Object[][] refs = {{b + 2, "tb_mixed"}, {b + 3, " s2.tb_d_00004 "}, {b + 4, "   "}, {b + 5, "TB_D_DUP"}, {b + 6, "tb_d_dup"}, {b + 7, "S9.TB_D_00001"}, {b + 8, "TB_D_00005"}};
        for (Object[] r : refs) jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, COL_NM, PK_YN, SORT_ORD, RFRNC_TBL_NM, APRV_YN) values (?, ?, ?, ?, false, 90, ?, true)", r[0], p, t, "R" + r[0], r[1]);
        // 같은 이름(TB_D_DUP)을 적은 두 컬럼 중 하나만 확정 힌트가 있다 : 이름 단위 판단(첫 확정 컬럼)이 예전과 같아야 한다
        jdbc.update("insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_TYPE_CD, REL_SRC_CD, DRVN_COL_SN, APRV_YN) values (?, ?, ?, ?, 'FK', 'REF_COLUMN', ?, false)", b + 20, p, t, m.dup[1], b + 6);
        // 쌍이 없는 사용자 정의 관계, 빈 UNIQUE 제약, 구성 요소가 없는 고유 인덱스
        jdbc.update("insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_TYPE_CD, REL_SRC_CD, REL_NM, APRV_YN) values (?, ?, ?, ?, 'FK', 'USER', 'FK_EMPTY', false)", b + 21, p, t, m.tables[6]);
        jdbc.update("insert into TB_TBL_CNSTRT(CNSTRT_SN, PRJ_SN, TBL_SN, CNSTRT_NM, CNSTRT_TYPE_CD) values (?, ?, ?, 'UK_EMPTY', 'UNIQUE')", b + 22, p, m.tables[6]);
        jdbc.update("insert into TB_TBL_IDX(IDX_SN, PRJ_SN, TBL_SN, IDX_NM, UNQ_YN, IDX_SRC_CD) values (?, ?, ?, 'UX_EMPTY', true, 'USER')", b + 23, p, m.tables[6]);
        // 내림차순 키·INCLUDE 가 있는 일반 인덱스 (앞부분 비교에서 빠진다), 같은 정의의 내림차순 인덱스 둘
        for (long ix : new long[]{b + 24, b + 25}) {
            jdbc.update("insert into TB_TBL_IDX(IDX_SN, PRJ_SN, TBL_SN, IDX_NM, UNQ_YN, IDX_SRC_CD) values (?, ?, ?, ?, false, 'USER')", ix, p, t, "IX_DESC_" + ix);
            jdbc.update("insert into TB_TBL_IDX_COL(IDX_COL_SN, IDX_SN, COL_SN, COL_ORD, SORT_CD) values (?, ?, ?, 1, 'DESC')", ix + 1000, ix, m.column(5, 5));
        }
        jdbc.update("insert into TB_TBL_IDX(IDX_SN, PRJ_SN, TBL_SN, IDX_NM, UNQ_YN, IDX_SRC_CD) values (?, ?, ?, 'IX_INC', false, 'USER')", b + 26, p, t);
        jdbc.update("insert into TB_TBL_IDX_COL(IDX_COL_SN, IDX_SN, COL_SN, COL_ORD, SORT_CD) values (?, ?, ?, 1, null), (?, ?, ?, 2, 'INCLUDE')", b + 2026, b + 26, m.column(5, 0), b + 3026, b + 26, m.column(5, 7));
        assertSame(m.user, null);
        assertSame(m.user, m.schemas[1]);

        // 다른 프로젝트 : 같은 구성의 데이터가 섞이지 않는다
        Seeded other = seed(30);
        assertSame(other.user, null);
        assertThat(service.diagnose(other.user, null).issues()).allMatch(i -> Arrays.stream(other.tables).anyMatch(id -> id == i.tableId()) || i.tableId().equals(other.dup[0]) || i.tableId().equals(other.dup[1]));
        assertThat(service.diagnose(m.user, other.schemas[0]).issues()).as("다른 프로젝트 스키마 ID 는 빈 결과").isEmpty();
        // 빈 프로젝트
        GenerationFixture empty = GenerationFixture.create(context, "diag-empty-" + System.nanoTime(), DbType.POSTGRESQL);
        assertSame(empty.user, null);
        assertThat(service.diagnose(empty.user, null).summary()).isEqualTo(new ModelStructureDiagnosticDto.Summary(0, 0, 0));
    }

    @Test
    @DisplayName("쿼리 수는 테이블 수와 무관하고(종류마다 1번) 엔티티·컬렉션을 적재하지 않는다 — 예전 구현은 테이블 수에 비례했다")
    void constantStatements() {
        Seeded small = seed(20);
        Seeded large = seed(200);
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        try {
            long[] statements = new long[2];
            Seeded[] models = {small, large};
            for (int i = 0; i < 2; i++) {
                stats.clear();
                service.diagnose(models[i].user, null);
                statements[i] = stats.getPrepareStatementCount();
                assertThat(stats.getEntityLoadCount() + stats.getCollectionLoadCount()).as("엔티티·컬렉션 적재").isZero();
            }
            assertThat(statements[0]).isEqualTo(statements[1]).isEqualTo(8);
            stats.clear();
            legacy(large.user, null);
            assertThat(stats.getPrepareStatementCount()).as("예전 구현은 테이블마다 여러 번").isGreaterThan(200);
        } finally {
            stats.setStatisticsEnabled(false);
        }
    }

    // ------------------------------------------------------------------ 데이터

    private record Seeded(AccountDto user, long base, long[] schemas, long[] tables, long[] dup, int columns) {
        long column(int table, int k) {
            return base + 100 + tables.length + 10 + (long) table * columns + k;
        }
    }

    /**
     * 스키마 3개, 테이블 n × 컬럼 12 + S2·S3 의 TB_D_DUP (기본 스키마에서 이름만 적으면 모호). 7번째마다 복합 PK, 45번째 PK 없음(0번 포함).
     * 참조 테이블명(COL_3) : 앞 테이블 / 30번째 없는 이름 / 20으로 나눈 나머지 7 은 TB_D_DUP(기본 스키마에서 모호 — 80으로 나눈 나머지 27 은 REF_COLUMN 힌트로 확정) / 60번째 S2.이름.
     * 사용자 FK : 사슬(COL_2 → 앞 COL_0), 10번째 복합, 20번째 허브, 50번째 자기 참조, 33번째 LOGICAL, 25번째 평행(COL_9 → 앞 COL_9), 19번째 UNIQUE(COL_0, COL_1).
     * 인덱스 : COL_5, 3번째 같은 정의, 4번째 COL_0(PK 와 같음), 5번째 COL_6 + (COL_6, COL_7), 6번째 조건부 고유, 8번째 표현식 고유, 9번째 고유 COL_9 INCLUDE COL_10
     */
    private Seeded seed(int n) {
        GenerationFixture f = GenerationFixture.create(context, "diag-eq-" + n + "-" + System.nanoTime(), DbType.POSTGRESQL);
        long project = f.user.getProjectId();
        int c = 12;
        long base = 8_000_000_000_000L + (System.nanoTime() % 1_000_000L) * 1_000_000L;
        long def = context.getBean(com.lexorma.data.schema.service.SchemaService.class).resolveSchema(project, null).getId();
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'S2', false)", base + 1, project);
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'S3', false)", base + 2, project);
        long[] schemas = {def, base + 1, base + 2};
        long tBase = base + 100, cBase = tBase + n + 10, rBase = cBase + (long) (n + 2) * c, rcBase = rBase + 6L * n, iBase = rcBase + 8L * n, icBase = iBase + 4L * n, kBase = icBase + 8L * n, kcBase = kBase + n;
        long[] ids = new long[n + 2];
        for (int i = 0; i < n + 2; i++) {
            ids[i] = tBase + i;
            boolean dup = i >= n;
            jdbc.update("insert into TB_TBL(TBL_SN, PRJ_SN, SCHM_SN, TBL_NM, APRV_YN) values (?, ?, ?, ?, false)", ids[i], project, dup ? schemas[i - n + 1] : schemas[i % 3], dup ? "TB_D_DUP" : "TB_D_" + String.format("%05d", i));
            for (int k = 0; k < c; k++) {
                boolean pk = i % 45 != 0 && (k == 0 || (k == 1 && i % 7 == 0));
                String ref = null;
                if (k == 3 && i > 0 && i < n) ref = i % 30 == 0 ? "TB_NOPE_" + i : i % 20 == 7 ? "TB_D_DUP" : i % 60 == 0 ? "S2.TB_D_" + String.format("%05d", i - 1) : "TB_D_" + String.format("%05d", i - 1);
                jdbc.update("insert into TB_COL(COL_SN, PRJ_SN, TBL_SN, COL_NM, NULL_ALWNC_YN, PK_YN, SORT_ORD, RFRNC_TBL_NM, UNQ_YN, APRV_YN) values (?, ?, ?, ?, ?, ?, ?, ?, false, ?)",
                        cBase + (long) i * c + k, project, ids[i], "COL_" + k, !pk, pk, k + 1, ref, i % 2 == 0);
            }
        }
        long[] r = {rBase}, pr = {rcBase};
        java.util.function.BiConsumer<Object[], long[][]> rel = (head, pairs) -> {
            long id = r[0]++;
            jdbc.update("insert into TB_TBL_REL(REL_SN, PRJ_SN, ORGNL_TBL_SN, TRGT_TBL_SN, REL_TYPE_CD, REL_SRC_CD, DRVN_COL_SN, REL_NM, APRV_YN) values (?, ?, ?, ?, ?, ?, ?, ?, false)",
                    id, project, head[0], head[1], head[2], head[3], head[4], "FK_" + id);
            for (int k = 0; k < pairs.length; k++) jdbc.update("insert into TB_TBL_REL_COL(REL_COL_SN, REL_SN, ORGNL_COL_SN, TRGT_COL_SN, COL_ORD) values (?, ?, ?, ?, ?)", pr[0]++, id, pairs[k][0], pairs[k][1], k + 1);
        };
        java.util.function.BiFunction<Integer, Integer, Long> col = (i, k) -> cBase + (long) i * c + k;
        for (int i = 1; i < n; i++) {
            rel.accept(new Object[]{ids[i], ids[i - 1], "FK", "USER", null}, i % 10 == 0
                    ? new long[][]{{col.apply(i, 2), col.apply(i - 1, 0)}, {col.apply(i, 3), col.apply(i - 1, 1)}} : new long[][]{{col.apply(i, 2), col.apply(i - 1, 0)}});
            if (i % 20 == 0) rel.accept(new Object[]{ids[i], ids[0], "FK", "USER", null}, new long[][]{{col.apply(i, 4), col.apply(0, 0)}});
            if (i % 50 == 0) rel.accept(new Object[]{ids[i], ids[i], "FK", "USER", null}, new long[][]{{col.apply(i, 6), col.apply(i, 0)}});
            if (i % 33 == 0) rel.accept(new Object[]{ids[i], ids[i - 1], "LOGICAL", "USER", null}, new long[][]{{col.apply(i, 7), col.apply(i - 1, 7)}});
            if (i % 25 == 0) rel.accept(new Object[]{ids[i], ids[i - 1], "FK", "USER", null}, new long[][]{{col.apply(i, 9), col.apply(i - 1, 9)}});
            if (i % 30 != 0 && (i % 20 != 7 || i % 80 == 27)) {
                long target = i % 20 == 7 ? ids[n + 1] : ids[i - 1];
                rel.accept(new Object[]{ids[i], target, "FK", "REF_COLUMN", col.apply(i, 3)}, new long[][]{{col.apply(i, 3), col.apply((int) (target - tBase), 0)}});
            }
        }
        long[] ix = {iBase}, ixc = {icBase};
        java.util.function.BiConsumer<Object[], Object[][]> index = (head, members) -> {
            long id = ix[0]++;
            jdbc.update("insert into TB_TBL_IDX(IDX_SN, PRJ_SN, TBL_SN, IDX_NM, UNQ_YN, CND_CN, APRV_YN, IDX_SRC_CD) values (?, ?, ?, ?, ?, ?, ?, 'USER')", id, project, head[0], "IX_" + id, head[1], head[2], head[3]);
            for (int k = 0; k < members.length; k++) jdbc.update("insert into TB_TBL_IDX_COL(IDX_COL_SN, IDX_SN, COL_SN, COL_ORD, EXPR_CN, SORT_CD) values (?, ?, ?, ?, ?, ?)", ixc[0]++, id, members[k][0], k + 1, members[k][1], members[k][2]);
        };
        for (int i = 0; i < n; i++) {
            long tb = ids[i];
            boolean approved = i % 2 == 1;
            index.accept(new Object[]{tb, false, null, approved}, new Object[][]{{col.apply(i, 5), null, null}});
            if (i % 3 == 0) index.accept(new Object[]{tb, false, null, approved}, new Object[][]{{col.apply(i, 5), null, null}});
            if (i % 4 == 0) index.accept(new Object[]{tb, false, null, approved}, new Object[][]{{col.apply(i, 0), null, null}});
            if (i % 5 == 0) {
                index.accept(new Object[]{tb, false, null, approved}, new Object[][]{{col.apply(i, 6), null, null}});
                index.accept(new Object[]{tb, false, null, approved}, new Object[][]{{col.apply(i, 6), null, null}, {col.apply(i, 7), null, "ASC"}});
            }
            if (i % 6 == 0) index.accept(new Object[]{tb, true, "COL_8 > 0", approved}, new Object[][]{{col.apply(i, 8), null, null}});
            if (i % 8 == 0) index.accept(new Object[]{tb, true, null, approved}, new Object[][]{{null, "lower(COL_11)", null}});
            if (i % 9 == 0) index.accept(new Object[]{tb, true, null, approved}, new Object[][]{{col.apply(i, 9), null, null}, {col.apply(i, 10), null, "INCLUDE"}});
            if (i % 20 == 19) {
                long k = kBase + i;
                jdbc.update("insert into TB_TBL_CNSTRT(CNSTRT_SN, PRJ_SN, TBL_SN, CNSTRT_NM, CNSTRT_TYPE_CD) values (?, ?, ?, ?, 'UNIQUE')", k, project, tb, "UK_" + k);
                jdbc.update("insert into TB_TBL_CNSTRT_COL(CNSTRT_COL_SN, CNSTRT_SN, COL_SN, COL_ORD) values (?, ?, ?, 1), (?, ?, ?, 2)", kcBase + 2L * i, k, col.apply(i, 0), kcBase + 2L * i + 1, k, col.apply(i, 1));
            }
        }
        long[] tables = Arrays.copyOf(ids, n);
        return new Seeded(f.user, base, schemas, tables, new long[]{ids[n], ids[n + 1]}, c);
    }
}
