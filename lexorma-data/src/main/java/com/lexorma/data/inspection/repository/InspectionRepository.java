package com.lexorma.data.inspection.repository;

import com.lexorma.data.dictionary.quality.InspectionItem;
import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.HistoryType;
import com.lexorma.data.inspection.domain.InspectionCodes.RuleRunStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.RunChange;
import com.lexorma.data.inspection.domain.InspectionCodes.RunStatus;
import com.lexorma.data.inspection.service.InspectionLifecycle.Existing;
import com.lexorma.data.inspection.service.StoredItem;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 저장 품질 검사(AI-6 2차)의 SQL. 결과 수가 많을 수 있어(컬럼 10만 개 = 결과 수십만 건) 엔티티를 쓰지 않고 JDBC 로 읽고 쓴다 —
 * 그래서 쓰기 트랜잭션은 {@code ProjectWriteGuard.lock/guard} 를 직접 불러야 한다 (서비스가 부른다). 모델 테이블에는 쓰지 않는다.
 * 큰 묶음 변경은 배열 {@code unnest} 한 문장(청크마다)으로, 새 결과는 배치 INSERT(드라이버가 다중 VALUES 로 바꾼다)로 넣는다.
 */
@Repository
public class InspectionRepository {

    /** 한 문장·한 배치에 담는 행 수 */
    public static final int CHUNK = 5_000;
    /** 결과 저장 한 문장(배치·unnest)의 행 수 — 성능 시험만 바꿔 본다 (설정 아님, FINAL 배치 크기 비교) */
    private int storeChunk = CHUNK;

    private final JdbcTemplate jdbc;

    public InspectionRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    // ------------------------------------------------------------------ 실행

    public record RunRow(long id, long projectId, Long accountId, RunStatus status, LocalDateTime requestedAt, LocalDateTime startedAt,
                         LocalDateTime basisAt, LocalDateTime endedAt, String errorCode, String errorMessage, String requestedBy) {
    }

    private static final String RUN_COLUMNS = "INSP_EXCN_SN, PRJ_SN, ACNT_SN, EXCN_STTS_CD, DMND_DT, BGNG_DT, CRTR_DT, END_DT, ERR_CD, ERR_CN, RGTR_ID";

    private static RunRow run(ResultSet rs, int i) throws SQLException {
        return new RunRow(rs.getLong(1), rs.getLong(2), (Long) rs.getObject(3, Long.class), RunStatus.valueOf(rs.getString(4)), time(rs, 5), time(rs, 6),
                time(rs, 7), time(rs, 8), rs.getString(9), rs.getString(10), rs.getString(11));
    }

    /** 대기 실행을 넣는다. 진행 중 실행이 이미 있으면 {@code UX_INSP_EXCN_01} 위반(DuplicateKeyException) */
    public long insertRun(long projectId, long accountId, LocalDateTime now, String auditor) {
        Long id = jdbc.queryForObject("select nextval('SQ_INSP_EXCN')", Long.class);
        jdbc.update("insert into TB_INSP_EXCN(INSP_EXCN_SN, PRJ_SN, PRGRS_PRJ_SN, ACNT_SN, EXCN_STTS_CD, DMND_DT, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?)", id, projectId, projectId, accountId, ts(now), ts(now), ts(now), auditor, auditor);
        return id;
    }

    public Optional<RunRow> findActiveRun(long projectId) {
        return jdbc.query("select " + RUN_COLUMNS + " from TB_INSP_EXCN where PRGRS_PRJ_SN = ?", InspectionRepository::run, projectId).stream().findFirst();
    }

    public Optional<RunRow> findRun(long projectId, long runId) {
        return jdbc.query("select " + RUN_COLUMNS + " from TB_INSP_EXCN where INSP_EXCN_SN = ? and PRJ_SN = ?", InspectionRepository::run, runId, projectId)
                .stream().findFirst();
    }

    public Optional<RunRow> lockRun(long runId) {
        return jdbc.query("select " + RUN_COLUMNS + " from TB_INSP_EXCN where INSP_EXCN_SN = ? for update", InspectionRepository::run, runId).stream().findFirst();
    }

    public Optional<RunRow> lastRun(long projectId, Set<RunStatus> statuses) {
        return jdbc.query("select " + RUN_COLUMNS + " from TB_INSP_EXCN where PRJ_SN = ? and EXCN_STTS_CD = any(?) order by INSP_EXCN_SN desc limit 1",
                InspectionRepository::run, projectId, names(statuses)).stream().findFirst();
    }

    /** 실행 이력 : 실행 일련번호(= 요청 순서) 순. 기본은 최근부터 */
    public List<RunRow> runs(long projectId, int offset, int limit, boolean ascending) {
        return jdbc.query("select " + RUN_COLUMNS + " from TB_INSP_EXCN where PRJ_SN = ? order by INSP_EXCN_SN " + (ascending ? "asc" : "desc") + " offset ? limit ?",
                InspectionRepository::run, projectId, offset, limit);
    }

    public long countRuns(long projectId) {
        return jdbc.queryForObject("select count(*) from TB_INSP_EXCN where PRJ_SN = ?", Long.class, projectId);
    }

    public int startRun(long runId, LocalDateTime now, String auditor) {
        return jdbc.update("update TB_INSP_EXCN set EXCN_STTS_CD = 'RUNNING', BGNG_DT = ?, MDFCN_DT = ?, MDFR_ID = ? where INSP_EXCN_SN = ? and EXCN_STTS_CD = 'PENDING'",
                ts(now), ts(now), auditor, runId);
    }

    /** 결과 확정. errorCode : 일부 규칙 묶음을 판정하지 못했으면 RULE_FAILED (실행은 확정, 그 규칙의 결과는 그대로) */
    public void completeRun(long runId, LocalDateTime basisAt, String errorCode, LocalDateTime now, String auditor) {
        jdbc.update("update TB_INSP_EXCN set EXCN_STTS_CD = 'COMPLETED', PRGRS_PRJ_SN = null, CRTR_DT = ?, END_DT = ?, ERR_CD = ?, MDFCN_DT = ?, MDFR_ID = ?"
                + " where INSP_EXCN_SN = ? and EXCN_STTS_CD = 'RUNNING'", ts(basisAt), ts(now), errorCode, ts(now), auditor, runId);
    }

    public void failRunRules(long runId, Collection<StandardizationRule> rules, LocalDateTime now, String auditor) {
        if (rules.isEmpty()) return;
        jdbc.update("update TB_INSP_EXCN_RULE set EXCN_STTS_CD = 'FAILED', MDFCN_DT = ?, MDFR_ID = ? where INSP_EXCN_SN = ? and RULE_CD = any(?)",
                ts(now), auditor, runId, names(rules));
    }

    /** 진행 중 실행을 실패·중단으로 닫는다 (이미 끝났으면 아무것도 하지 않는다). 판정 중이던 규칙은 FAILED */
    public int closeRun(long runId, RunStatus status, String errorCode, String errorMessage, LocalDateTime basisAt, LocalDateTime now, String auditor) {
        int rows = jdbc.update("update TB_INSP_EXCN set EXCN_STTS_CD = ?, PRGRS_PRJ_SN = null, CRTR_DT = coalesce(?, CRTR_DT), END_DT = ?, ERR_CD = ?, ERR_CN = ?,"
                + " MDFCN_DT = ?, MDFR_ID = ? where INSP_EXCN_SN = ? and EXCN_STTS_CD in ('PENDING', 'RUNNING')",
                status.name(), ts(basisAt), ts(now), errorCode, errorMessage, ts(now), auditor, runId);
        if (rows > 0) {
            jdbc.update("update TB_INSP_EXCN_RULE set EXCN_STTS_CD = 'FAILED', MDFCN_DT = ?, MDFR_ID = ? where INSP_EXCN_SN = ? and EXCN_STTS_CD = 'RUNNING'",
                    ts(now), auditor, runId);
        }
        return rows;
    }

    /** 서버 기동 때 : 끝나지 않은 실행 ID (모든 프로젝트) */
    public List<Long> activeRunIds() {
        return jdbc.queryForList("select INSP_EXCN_SN from TB_INSP_EXCN where EXCN_STTS_CD in ('PENDING', 'RUNNING') order by INSP_EXCN_SN", Long.class);
    }

    // ------------------------------------------------------------------ 실행 규칙·건수

    public record RuleRunRow(StandardizationRule rule, RuleRunStatus status, Integer resultCount) {
    }

    public void insertRunRules(long runId, Collection<StandardizationRule> rules, LocalDateTime now, String auditor) {
        List<Object[]> rows = new ArrayList<>();
        for (StandardizationRule rule : rules) rows.add(new Object[]{runId, rule.name(), ts(now), ts(now), auditor, auditor});
        jdbc.batchUpdate("insert into TB_INSP_EXCN_RULE(INSP_EXCN_RULE_SN, INSP_EXCN_SN, RULE_CD, EXCN_STTS_CD, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (nextval('SQ_INSP_EXCN_RULE'), ?, ?, 'RUNNING', ?, ?, ?, ?)", rows);
    }

    public List<RuleRunRow> runRules(long runId) {
        return jdbc.query("select RULE_CD, EXCN_STTS_CD, RSLT_CNT from TB_INSP_EXCN_RULE where INSP_EXCN_SN = ? order by INSP_EXCN_RULE_SN",
                (rs, i) -> new RuleRunRow(StandardizationRule.valueOf(rs.getString(1)), RuleRunStatus.valueOf(rs.getString(2)), (Integer) rs.getObject(3, Integer.class)),
                runId);
    }

    public void completeRunRules(long runId, Map<StandardizationRule, Integer> found, LocalDateTime now, String auditor) {
        List<Object[]> rows = new ArrayList<>();
        found.forEach((rule, count) -> rows.add(new Object[]{count, ts(now), auditor, runId, rule.name()}));
        jdbc.batchUpdate("update TB_INSP_EXCN_RULE set EXCN_STTS_CD = 'COMPLETED', RSLT_CNT = ?, MDFCN_DT = ?, MDFR_ID = ? where INSP_EXCN_SN = ? and RULE_CD = ?", rows);
    }

    /** 실행 × 규칙 × 변화 유형 건수 — 0 이 아닌 것만 넣는다 (없는 행 = 0) */
    public void insertRunCounts(long runId, Map<StandardizationRule, Map<RunChange, Integer>> counts, LocalDateTime now, String auditor) {
        List<Object[]> rows = new ArrayList<>();
        counts.forEach((rule, byChange) -> byChange.forEach((change, count) -> {
            if (count > 0) rows.add(new Object[]{runId, rule.name(), change.name(), count, ts(now), ts(now), auditor, auditor});
        }));
        if (rows.isEmpty()) return;
        jdbc.batchUpdate("insert into TB_INSP_EXCN_RSLT(INSP_EXCN_RSLT_SN, INSP_EXCN_SN, RULE_CD, CHG_TYPE_CD, RSLT_CNT, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (nextval('SQ_INSP_EXCN_RSLT'), ?, ?, ?, ?, ?, ?, ?, ?)", rows);
    }

    public record RunCountRow(StandardizationRule rule, RunChange change, int count) {
    }

    public List<RunCountRow> runCounts(long runId) {
        return jdbc.query("select RULE_CD, CHG_TYPE_CD, RSLT_CNT from TB_INSP_EXCN_RSLT where INSP_EXCN_SN = ? order by RULE_CD, CHG_TYPE_CD",
                (rs, i) -> new RunCountRow(StandardizationRule.valueOf(rs.getString(1)), RunChange.valueOf(rs.getString(2)), rs.getInt(3)), runId);
    }

    // ------------------------------------------------------------------ 규칙 설정

    /** 끈 규칙 (설정 행이 있고 ACTVTN_YN = false). 행이 없는 규칙은 활성 (AI6-D05) */
    public Set<StandardizationRule> disabledRules(long projectId) {
        Set<StandardizationRule> rules = EnumSet.noneOf(StandardizationRule.class);
        jdbc.query("select RULE_CD from TB_INSP_RULE_STNG where PRJ_SN = ? and ACTVTN_YN = false", rs -> {
            try {
                rules.add(StandardizationRule.valueOf(rs.getString(1)));
            } catch (IllegalArgumentException ignored) {
                // 지금 정의에 없는 규칙 코드(예전 값)는 보지 않는다
            }
        }, projectId);
        return rules;
    }

    public void disableRule(long projectId, StandardizationRule rule, LocalDateTime now, String auditor) {
        jdbc.update("insert into TB_INSP_RULE_STNG(INSP_RULE_STNG_SN, PRJ_SN, RULE_CD, ACTVTN_YN, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID)"
                + " values (nextval('SQ_INSP_RULE_STNG'), ?, ?, false, ?, ?, ?, ?) on conflict (PRJ_SN, RULE_CD) do nothing",
                projectId, rule.name(), ts(now), ts(now), auditor, auditor);
    }

    /** 기본값(활성)으로 되돌리면 행을 지운다 — 바꾼 것만 남긴다 */
    public void enableRule(long projectId, StandardizationRule rule) {
        jdbc.update("delete from TB_INSP_RULE_STNG where PRJ_SN = ? and RULE_CD = ?", projectId, rule.name());
    }

    // ------------------------------------------------------------------ 결과 : 저장 (실행 1회)

    public record ExistingTarget(Existing row, InspectionItem.ObjectType type, long targetId, List<Long> targetIds) {
    }

    /** 프로젝트의 저장된 결과 전부 (판정에 필요한 값 + 대상 삭제 판정용 대상 ID) */
    public List<ExistingTarget> existing(long projectId) {
        // 대상 ID 목록은 대표 대상 하나뿐이 아닐 때(용어 그룹 등)만 읽는다 — 대부분의 결과는 [TRGT_SN] 이라 비워 두면 대표 대상을 쓴다 (FINAL 메모리 개선)
        return jdbc.query("select INSP_RSLT_SN, HASH_VL, RULE_CD, STTS_CD, EXCL_YN, END_RSN_CD, END_DT, TRGT_TYPE_CD, TRGT_SN,"
                        + " case when RSLT_EVDNC_CN -> 'targetIds' = jsonb_build_array(TRGT_SN) then null"
                        + " else array(select jsonb_array_elements_text(RSLT_EVDNC_CN -> 'targetIds')::bigint) end from TB_INSP_RSLT where PRJ_SN = ?",
                (rs, i) -> {
                    StandardizationRule rule;
                    try {
                        rule = StandardizationRule.valueOf(rs.getString(3));
                    } catch (IllegalArgumentException e) {
                        rule = null; // 지금 정의에 없는 규칙 : 판정하지 않는다
                    }
                    String reason = rs.getString(6);
                    Existing row = new Existing(rs.getLong(1), rs.getString(2), rule, FindingStatus.valueOf(rs.getString(4)), rs.getBoolean(5),
                            reason == null ? null : EndReason.valueOf(reason), time(rs, 7));
                    Array array = rs.getArray(10);
                    List<Long> ids = List.of();
                    if (array != null) {
                        Object[] values = (Object[]) array.getArray();
                        Long[] longs = new Long[values.length];
                        for (int k = 0; k < values.length; k++) longs[k] = ((Number) values[k]).longValue();
                        ids = List.of(longs);
                    }
                    return new ExistingTarget(row, InspectionItem.ObjectType.valueOf(rs.getString(8)), rs.getLong(9), ids);
                }, projectId);
    }

    /** 지금(이 트랜잭션의 스냅숏에서) 있는 대상 ID */
    public Set<Long> existingObjects(long projectId, InspectionItem.ObjectType type, Collection<Long> ids) {
        Set<Long> found = new HashSet<>();
        if (ids.isEmpty()) return found;
        String sql = switch (type) {
            case COLUMN -> "select COL_SN from TB_COL where PRJ_SN = ? and COL_SN = any(?)";
            case TABLE -> "select TBL_SN from TB_TBL where PRJ_SN = ? and TBL_SN = any(?)";
            case SCHEMA -> "select SCHM_SN from TB_SCHM where PRJ_SN = ? and SCHM_SN = any(?)";
            case TERM -> "select STD_TRM_SN from TB_STD_TRM where PRJ_SN = ? and STD_TRM_SN = any(?)";
        };
        List<Long> list = List.copyOf(ids);
        for (int from = 0; from < list.size(); from += CHUNK) {
            List<Long> chunk = list.subList(from, Math.min(from + CHUNK, list.size()));
            found.addAll(jdbc.query(sql, (rs, i) -> rs.getLong(1), projectId, chunk.toArray(Long[]::new)));
        }
        return found;
    }

    public void insertFindings(long projectId, long runId, List<StoredItem> items, LocalDateTime basisAt, LocalDateTime now, String auditor) {
        Timestamp basis = ts(basisAt);
        Timestamp stamp = ts(now);
        String sql = "insert into TB_INSP_RSLT(INSP_RSLT_SN, PRJ_SN, RULE_CD, RSLT_GRD_CD, TRGT_TYPE_CD, TRGT_SN, TBL_SN, TRGT_CN, HASH_VL, STTS_CD, EXCL_YN,"
                + " LAST_CHG_TYPE_CD, END_RSN_CD, RSLT_EVDNC_CN, FRST_IDNTY_DT, LAST_IDNTY_DT, END_DT, FRST_INSP_EXCN_SN, LAST_INSP_EXCN_SN, VER_NO,"
                + " REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values (nextval('SQ_INSP_RSLT'), ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', false, 'NEW', null, ?::jsonb, ?, ?, null, ?, ?, 0,"
                + " ?, ?, ?, ?)";
        for (int from = 0; from < items.size(); from += storeChunk) {
            List<StoredItem> chunk = items.subList(from, Math.min(from + storeChunk, items.size()));
            jdbc.batchUpdate(sql, chunk, chunk.size(), (ps, item) -> {
                StandardizationRule rule = item.rule();
                ps.setLong(1, projectId);
                ps.setString(2, rule.name());
                ps.setString(3, rule.severity().name());
                ps.setString(4, item.objectType().name());
                ps.setLong(5, item.objectId());
                ps.setObject(6, item.tableId());
                ps.setString(7, item.targetName());
                ps.setString(8, item.fingerprint());
                ps.setString(9, item.evidence());
                ps.setTimestamp(10, basis);
                ps.setTimestamp(11, basis);
                ps.setLong(12, runId);
                ps.setLong(13, runId);
                ps.setTimestamp(14, stamp);
                ps.setTimestamp(15, stamp);
                ps.setString(16, auditor);
                ps.setString(17, auditor);
            });
        }
    }

    /**
     * 다시 찾은 결과 : 내용(등급·대상·표시 이름·근거)과 마지막 판정 값을 고친다. 재발이면 OPEN 으로 되돌리고 종료 값을 비운다.
     * 예외 여부·버전 번호는 건드리지 않는다 — 예외 처리와 동시에 일어나도 서로의 값을 지우지 않는다 (AI6-D29).
     */
    public void updateFound(long projectId, long runId, List<Long> ids, List<StoredItem> items, boolean reopen, LocalDateTime basisAt, LocalDateTime now,
                            String auditor) {
        String sql = "update TB_INSP_RSLT r set RSLT_GRD_CD = v.grade, TRGT_TYPE_CD = v.type, TRGT_SN = v.target, TBL_SN = v.tbl, TRGT_CN = v.name,"
                + " RSLT_EVDNC_CN = v.evidence::jsonb, LAST_CHG_TYPE_CD = ?, STTS_CD = 'OPEN', END_RSN_CD = null, END_DT = null,"
                + " LAST_IDNTY_DT = ?, LAST_INSP_EXCN_SN = ?, MDFCN_DT = ?, MDFR_ID = ?"
                + " from unnest(?::bigint[], ?::text[], ?::text[], ?::bigint[], ?::bigint[], ?::text[], ?::text[]) as v(id, grade, type, target, tbl, name, evidence)"
                + " where r.INSP_RSLT_SN = v.id and r.PRJ_SN = ?";
        for (int from = 0; from < ids.size(); from += storeChunk) {
            int to = Math.min(from + storeChunk, ids.size());
            List<Long> idChunk = ids.subList(from, to);
            List<StoredItem> chunk = items.subList(from, to);
            jdbc.execute((ConnectionCallback<Integer>) connection -> {
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    ps.setString(1, reopen ? "REOPENED" : "PERSISTING");
                    ps.setTimestamp(2, ts(basisAt));
                    ps.setLong(3, runId);
                    ps.setTimestamp(4, ts(now));
                    ps.setString(5, auditor);
                    ps.setArray(6, connection.createArrayOf("bigint", idChunk.toArray()));
                    ps.setArray(7, connection.createArrayOf("text", chunk.stream().map(i -> i.rule().severity().name()).toArray()));
                    ps.setArray(8, connection.createArrayOf("text", chunk.stream().map(i -> i.objectType().name()).toArray()));
                    ps.setArray(9, connection.createArrayOf("bigint", chunk.stream().map(StoredItem::objectId).toArray()));
                    ps.setArray(10, connection.createArrayOf("bigint", chunk.stream().map(StoredItem::tableId).toArray()));
                    ps.setArray(11, connection.createArrayOf("text", chunk.stream().map(StoredItem::targetName).toArray()));
                    ps.setArray(12, connection.createArrayOf("text", chunk.stream().map(StoredItem::evidence).toArray()));
                    ps.setLong(13, projectId);
                    return ps.executeUpdate();
                }
            });
        }
    }

    /** 찾지 못한 OPEN 결과를 해결로 (사유별로 나눠 부른다) */
    public void resolve(long projectId, long runId, List<Long> ids, EndReason reason, LocalDateTime basisAt, LocalDateTime now, String auditor) {
        for (int from = 0; from < ids.size(); from += CHUNK) {
            Long[] chunk = ids.subList(from, Math.min(from + CHUNK, ids.size())).toArray(Long[]::new);
            jdbc.update("update TB_INSP_RSLT set STTS_CD = 'RESOLVED', LAST_CHG_TYPE_CD = 'RESOLVED', END_RSN_CD = ?, END_DT = ?, LAST_IDNTY_DT = ?,"
                            + " LAST_INSP_EXCN_SN = ?, MDFCN_DT = ?, MDFR_ID = ? where PRJ_SN = ? and INSP_RSLT_SN = any(?) and STTS_CD = 'OPEN'",
                    reason.name(), ts(basisAt), ts(basisAt), runId, ts(now), auditor, projectId, chunk);
        }
    }

    /** 이 실행이 찾은(신규·유지·재발) 결과 중 지금 예외인 것의 규칙별 수 */
    public Map<StandardizationRule, Integer> excludedFound(long projectId, long runId) {
        Map<StandardizationRule, Integer> counts = new java.util.EnumMap<>(StandardizationRule.class);
        jdbc.query("select RULE_CD, count(*) from TB_INSP_RSLT where PRJ_SN = ? and LAST_INSP_EXCN_SN = ? and STTS_CD = 'OPEN' and EXCL_YN = true group by RULE_CD",
                rs -> {
                    counts.put(StandardizationRule.valueOf(rs.getString(1)), rs.getInt(2));
                }, projectId, runId);
        return counts;
    }

    public record HistoryInsert(long findingId, Long runId, HistoryType type, FindingStatus status, boolean excluded, String reason,
                                EndReason endReason, LocalDateTime endedAt) {
    }

    public void insertHistory(long projectId, List<HistoryInsert> rows, LocalDateTime now, String auditor) {
        String sql = "insert into TB_INSP_RSLT_HSTRY(INSP_RSLT_HSTRY_SN, INSP_RSLT_SN, PRJ_SN, INSP_EXCN_SN, CHG_TYPE_CD, STTS_CD, EXCL_YN, CHG_RSN_CN,"
                + " END_RSN_CD, END_DT, REG_DT, MDFCN_DT, RGTR_ID, MDFR_ID) values (nextval('SQ_INSP_RSLT_HSTRY'), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int from = 0; from < rows.size(); from += CHUNK) {
            List<HistoryInsert> chunk = rows.subList(from, Math.min(from + CHUNK, rows.size()));
            jdbc.batchUpdate(sql, chunk, chunk.size(), (ps, row) -> {
                ps.setLong(1, row.findingId());
                ps.setLong(2, projectId);
                ps.setObject(3, row.runId());
                ps.setString(4, row.type().name());
                ps.setString(5, row.status().name());
                ps.setBoolean(6, row.excluded());
                ps.setString(7, row.reason());
                ps.setString(8, row.endReason() == null ? null : row.endReason().name());
                ps.setTimestamp(9, ts(row.endedAt()));
                ps.setTimestamp(10, ts(now));
                ps.setTimestamp(11, ts(now));
                ps.setString(12, auditor);
                ps.setString(13, auditor);
            });
        }
    }

    // ------------------------------------------------------------------ 결과 : 예외

    public record LockedFinding(long id, FindingStatus status, boolean excluded, long version) {
    }

    public Optional<LockedFinding> lockFinding(long projectId, long findingId) {
        return jdbc.query("select INSP_RSLT_SN, STTS_CD, EXCL_YN, VER_NO from TB_INSP_RSLT where INSP_RSLT_SN = ? and PRJ_SN = ? for update",
                (rs, i) -> new LockedFinding(rs.getLong(1), FindingStatus.valueOf(rs.getString(2)), rs.getBoolean(3), rs.getLong(4)),
                findingId, projectId).stream().findFirst();
    }

    public void updateExclusion(long projectId, long findingId, boolean excluded, LocalDateTime now, String auditor) {
        jdbc.update("update TB_INSP_RSLT set EXCL_YN = ?, VER_NO = VER_NO + 1, MDFCN_DT = ?, MDFR_ID = ? where INSP_RSLT_SN = ? and PRJ_SN = ?",
                excluded, ts(now), auditor, findingId, projectId);
    }

    // ------------------------------------------------------------------ 보조

    static Timestamp ts(LocalDateTime time) {
        return time == null ? null : Timestamp.valueOf(time);
    }

    static LocalDateTime time(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    static String[] names(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toArray(String[]::new);
    }
}
