package com.lexorma;

import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceImportDto.Judgement;
import com.lexorma.data.sourcemapping.domain.dto.SourceMappingDto;
import com.lexorma.data.sourcemapping.domain.dto.SourceRenameDto;
import com.lexorma.data.sourcemapping.service.SourceImportService;
import com.lexorma.data.sourcemapping.service.SourceMappingService;
import com.lexorma.data.sourcemapping.service.SourceRenameService;
import com.lexorma.source.collection.domain.dto.SourceCollectionDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DEC-06 보완 (4차 남은 것) : 실제 PostgreSQL 원본에서 TABLE·컬럼 이름이 바뀐 뒤 이름 변경을 확정하면, 재가져오기(M-4f)의 기준값이 예전 원본 TABLE·컬럼 이름으로 한
 * 가져오기에서 이어진다 (매핑 이력의 RENAME 변경 전 원본 TABLE, 확정한 컬럼 매핑의 모델 컬럼). 기준값이 없으면 NO_BASELINE 으로 보여 바뀐 속성을 고를 수 없었다.
 * 재가져오기는 같은 모델 TABLE 을 고치고(중복 생성 없음), 컬럼 매핑은 그대로 남는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"lexorma.secret.keys=t1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=", "lexorma.secret.active-key-id=t1",
                "lexorma.source.allowed-networks=127.0.0.0/8,::1/128", "lexorma.source.jdbc.query-timeout-seconds=5",
                "lexorma.source.collection.heartbeat-interval=PT0.2S", "lexorma.source.connection-test.per-minute=1000"})
@ActiveProfiles("test")
class SourceRenameReimportJdbcTest extends SourceJdbcTestSupport {

    @Autowired SourceMappingService mappingService;
    @Autowired SourceImportService importService;
    @Autowired SourceRenameService renameService;

    @Test
    @DisplayName("가져오기 → 원본 TABLE·컬럼 이름 변경 + 타입 변경 → 재수집 → 이름 변경 확정 → 재가져오기 기준값이 이어지고(SOURCE_CHANGED) 같은 모델 TABLE 을 고친다")
    void renameKeepsReimportBaseline() throws Exception {
        SourceDb db = source("create schema app", "create table app.customer (id bigint primary key, email varchar(100) not null, name text)");
        Env e = env(db);
        AccountDto dto = e.managerDto();
        SourceCollectionDto.Execution first = collect(e, "FULL");
        Long modelSchema = 970_000_000L + e.project().getId();
        jdbc.update("insert into TB_SCHM(SCHM_SN, PRJ_SN, SCHM_NM, BSC_YN) values (?, ?, 'public', true)", modelSchema, e.project().getId());
        mappingService.setBase(dto, new SourceMappingDto.BaseRequest(e.connection(), true));
        Long sourceSchema = jdbc.queryForObject("select SRC_SCHM_SN from TB_SRC_SCHM where SRC_CNTN_SN = ? and SCHM_NM = 'app'", Long.class, e.connection());
        mappingService.mapSchema(dto, new SourceMappingDto.SchemaRequest(sourceSchema, modelSchema));

        // 1) 처음 가져오기 : TABLE 과 고를 수 있는 항목 전부
        Long customer = sourceTableId(e, "customer");
        List<String> all = importService.candidates(dto, e.connection(), first.reflectedOrder(), "S" + customer).items().stream()
                .filter(SourceImportDto.Item::selectable).map(SourceImportDto.Item::id).toList();
        apply(e, first.reflectedOrder(), "S" + customer, all);
        Long modelTable = jdbc.queryForObject("select TBL_SN from TB_TBL where PRJ_SN = ?", Long.class, e.project().getId());
        Long nameColumn = jdbc.queryForObject("select COL_SN from TB_COL where TBL_SN = ? and lower(COL_NM) = 'name'", Long.class, modelTable);

        // 2) 원본 : TABLE·컬럼 이름 변경, 두 컬럼의 타입 변경 → 재수집
        db.jdbc().execute("alter table app.customer rename to client");
        db.jdbc().execute("alter table app.client rename column name to full_name");
        db.jdbc().execute("alter table app.client alter column email type varchar(200)");
        db.jdbc().execute("alter table app.client alter column full_name type varchar(50)");
        SourceCollectionDto.Execution second = collect(e, "FULL");
        Long client = sourceTableId(e, "client");
        Long mapping = jdbc.queryForObject("select SRC_TBL_MPNG_SN from TB_SRC_TBL_MPNG where PRJ_SN = ?", Long.class, e.project().getId());

        // 3) 이름 변경 확정 (TABLE → 컬럼)
        renameService.confirmTable(dto, new SourceRenameDto.TableRenameRequest(e.connection(), second.reflectedOrder(), mapping, client));
        renameService.confirmColumn(dto, new SourceRenameDto.ColumnRequest(e.connection(), second.reflectedOrder(), mapping, "full_name", nameColumn));

        // 4) 재가져오기 : 예전 TABLE·컬럼 이름으로 한 가져오기가 기준값이다
        SourceImportDto.TableCandidates again = importService.candidates(dto, e.connection(), second.reflectedOrder(), "S" + client);
        assertThat(again.create()).as("같은 모델 TABLE (새로 만들지 않는다)").isFalse();
        assertThat(again.lastImport()).as("이름 변경 전 가져오기가 마지막 가져오기로 보인다").isNotNull();
        Map<String, SourceImportDto.Item> items = new LinkedHashMap<>();
        again.items().forEach(i -> items.put(i.id(), i));
        assertThat(items.get("COLUMN:email:TYPE")).satisfies(i -> {
            assertThat(i.baseline()).as("TABLE 이름 변경 뒤에도 기준값").isNotNull();
            assertThat(i.judgement()).isEqualTo(Judgement.SOURCE_CHANGED);
            assertThat(i.selectable()).isTrue();
        });
        assertThat(items.get("COLUMN:full_name:TYPE")).satisfies(i -> {
            assertThat(i.baseline()).as("컬럼 이름 변경 매핑 뒤에도 기준값 (예전 이름의 가져오기)").isNotNull();
            assertThat(i.judgement()).isEqualTo(Judgement.SOURCE_CHANGED);
        });
        apply(e, second.reflectedOrder(), "S" + client, List.of("COLUMN:email:TYPE", "COLUMN:full_name:TYPE"));

        assertThat(jdbc.queryForObject("select count(*) from TB_TBL where PRJ_SN = ?", Integer.class, e.project().getId())).as("중복 생성 없음").isOne();
        assertThat(jdbc.queryForObject("select MAX_LEN from TB_COL where TBL_SN = ? and lower(COL_NM) = 'email'", Integer.class, modelTable)).isEqualTo(200);
        assertThat(jdbc.queryForObject("select COL_SN from TB_SRC_COL_MPNG where SRC_TBL_MPNG_SN = ? and CMPR_NM = 'full_name'", Long.class, mapping)).isEqualTo(nameColumn);
        assertThat(jdbc.queryForObject("select SRC_TBL_SN from TB_SRC_TBL_MPNG where SRC_TBL_MPNG_SN = ?", Long.class, mapping)).isEqualTo(client);
        // 다시 보면 이번 가져오기가 기준값 : 같아졌으므로 고를 것이 없다
        assertThat(importService.candidates(dto, e.connection(), second.reflectedOrder(), "S" + client).items())
                .filteredOn(i -> i.id().equals("COLUMN:email:TYPE")).allSatisfy(i -> assertThat(i.selectable()).isFalse());
    }

    private void apply(Env e, Integer order, String key, List<String> items) {
        SourceImportDto.Request request = new SourceImportDto.Request(e.connection(), order, List.of(new SourceImportDto.TableSelection(key, null, items)), null, null);
        SourceImportDto.Plan plan = importService.plan(e.managerDto(), request);
        assertThat(plan.applicable()).as("계획 적용 가능").isTrue();
        importService.apply(e.managerDto(), new SourceImportDto.Request(e.connection(), order, request.tables(), plan.planToken(), plan.requestId()));
    }

    private Long sourceTableId(Env e, String name) {
        return jdbc.queryForObject("select t.SRC_TBL_SN from TB_SRC_TBL t join TB_SRC_SCHM s on s.SRC_SCHM_SN = t.SRC_SCHM_SN where t.SRC_CNTN_SN = ? and s.SCHM_NM = 'app'"
                + " and t.CMPR_NM = ? and t.EXST_YN = true", Long.class, e.connection(), name);
    }
}
