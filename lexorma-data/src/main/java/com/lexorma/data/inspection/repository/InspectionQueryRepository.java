package com.lexorma.data.inspection.repository;

import com.lexorma.data.dictionary.quality.StandardizationRule;
import com.lexorma.data.dictionary.quality.StandardizationRule.Severity;
import com.lexorma.data.inspection.domain.InspectionCodes.EndReason;
import com.lexorma.data.inspection.domain.InspectionCodes.FindingStatus;
import com.lexorma.data.inspection.domain.InspectionCodes.HistoryType;
import com.lexorma.data.inspection.domain.InspectionCodes.LastChange;
import com.lexorma.data.inspection.domain.dto.InspectionDto;
import com.lexorma.data.inspection.domain.dto.InspectionDto.ExclusionFilter;
import com.lexorma.data.inspection.domain.dto.InspectionDto.RuleScope;
import com.lexorma.data.inspection.domain.dto.InspectionDto.StatusFilter;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.lexorma.data.inspection.repository.InspectionRepository.names;
import static com.lexorma.data.inspection.repository.InspectionRepository.time;

/** 저장 품질 검사 결과 조회 SQL (읽기 전용, 프로젝트 범위). 목록 조건·정렬은 서버 허용 목록만 쓴다 */
@Repository
public class InspectionQueryRepository {

    /** 정렬할 수 있는 필드 → SQL (PageableUtil 허용 목록 = 키) */
    public static final Map<String, String> SORTS = Map.of(
            "severity", "case RSLT_GRD_CD when 'ERROR' then 0 when 'WARNING' then 1 else 2 end",
            "rule", "RULE_CD",
            "targetType", "TRGT_TYPE_CD",
            "targetName", "TRGT_CN",
            "status", "STTS_CD",
            "firstSeenAt", "FRST_IDNTY_DT",
            "lastSeenAt", "LAST_IDNTY_DT",
            "endedAt", "END_DT");
    public static final Set<String> SORT_FIELDS = SORTS.keySet();

    private static final String COLUMNS = "INSP_RSLT_SN, RULE_CD, RSLT_GRD_CD, TRGT_TYPE_CD, TRGT_SN, TBL_SN, TRGT_CN, STTS_CD, EXCL_YN, LAST_CHG_TYPE_CD,"
            + " END_RSN_CD, FRST_IDNTY_DT, LAST_IDNTY_DT, END_DT, FRST_INSP_EXCN_SN, LAST_INSP_EXCN_SN, VER_NO,"
            + " RSLT_EVDNC_CN ->> 'actualValue', RSLT_EVDNC_CN ->> 'expectedValue'";

    private final JdbcTemplate jdbc;

    public InspectionQueryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    private static InspectionDto.Finding finding(ResultSet rs, Set<StandardizationRule> disabled) throws SQLException {
        StandardizationRule rule = StandardizationRule.valueOf(rs.getString(2));
        String reason = rs.getString(11);
        return new InspectionDto.Finding(rs.getLong(1), rule, Severity.valueOf(rs.getString(3)), rs.getString(4), rs.getLong(5),
                (Long) rs.getObject(6, Long.class), rs.getString(7), FindingStatus.valueOf(rs.getString(8)), rs.getBoolean(9),
                LastChange.valueOf(rs.getString(10)), reason == null ? null : EndReason.valueOf(reason), time(rs, 12), time(rs, 13), time(rs, 14),
                rs.getLong(15), rs.getLong(16), rs.getLong(17), !disabled.contains(rule), rs.getString(18), rs.getString(19));
    }

    private record Where(String sql, List<Object> args) {
    }

    private static Where where(long projectId, InspectionDto.Search search, Set<StandardizationRule> disabled) {
        StringBuilder sql = new StringBuilder(" where PRJ_SN = ? and RULE_CD = any(?)");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        // 지금 정의에 있는 규칙만 (예전 코드의 행은 보이지 않는다)
        args.add(names(Set.of(StandardizationRule.values())));
        String[] off = names(disabled);
        RuleScope scope = search.ruleScope() == null ? RuleScope.ACTIVE : search.ruleScope();
        if (scope == RuleScope.ACTIVE) {
            sql.append(" and RULE_CD <> all(?)");
            args.add(off);
        } else if (scope == RuleScope.INACTIVE) {
            sql.append(" and RULE_CD = any(?)");
            args.add(off);
        }
        StatusFilter status = search.status() == null ? StatusFilter.OPEN : search.status();
        if (status != StatusFilter.ALL) {
            sql.append(" and STTS_CD = ?");
            args.add(status.name());
        }
        ExclusionFilter exclusion = search.exclusion() == null ? ExclusionFilter.NOT_EXCLUDED : search.exclusion();
        if (exclusion != ExclusionFilter.ALL) {
            sql.append(" and EXCL_YN = ?");
            args.add(exclusion == ExclusionFilter.EXCLUDED);
        }
        if (search.group() != null && !search.group().isBlank()) {
            com.lexorma.data.inspection.service.InspectionJudge.Group group = groupOf(search.group());
            sql.append(" and RULE_CD = any(?)");
            args.add(names(java.util.Arrays.stream(StandardizationRule.values())
                    .filter(rule -> com.lexorma.data.inspection.service.InspectionJudge.group(rule) == group).collect(java.util.stream.Collectors.toSet())));
        }
        if (search.lastChange() != null) {
            sql.append(" and LAST_CHG_TYPE_CD = ?");
            args.add(search.lastChange().name());
        }
        if (search.rule() != null) {
            sql.append(" and RULE_CD = ?");
            args.add(search.rule().name());
        }
        if (search.severity() != null) {
            sql.append(" and RSLT_GRD_CD = ?");
            args.add(search.severity().name());
        }
        if (search.targetType() != null && !search.targetType().isBlank()) {
            sql.append(" and TRGT_TYPE_CD = ?");
            args.add(search.targetType().trim().toUpperCase(Locale.ROOT));
        }
        if (search.endReason() != null) {
            sql.append(" and END_RSN_CD = ?");
            args.add(search.endReason().name());
        }
        if (search.tableId() != null) {
            sql.append(" and TBL_SN = ?");
            args.add(search.tableId());
        }
        if (search.firstSeenRunId() != null) {
            sql.append(" and FRST_INSP_EXCN_SN = ?");
            args.add(search.firstSeenRunId());
        }
        if (search.resolvedInRunId() != null) {
            // 해결 처리한 실행 = 해결 행의 최종 실행 (해결된 결과는 다시 찾을 때까지 최종 실행이 바뀌지 않는다). 재발하면 OPEN 이 되어 빠진다
            sql.append(" and STTS_CD = 'RESOLVED' and LAST_INSP_EXCN_SN = ?");
            args.add(search.resolvedInRunId());
        }
        if (search.searchQuery() != null && !search.searchQuery().isBlank()) {
            sql.append(" and lower(TRGT_CN) like ? escape '\\'");
            args.add("%" + search.searchQuery().trim().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        return new Where(sql.toString(), args);
    }

    private static com.lexorma.data.inspection.service.InspectionJudge.Group groupOf(String value) {
        try {
            return com.lexorma.data.inspection.service.InspectionJudge.Group.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.lexorma.common.exception.CustomException(com.lexorma.common.exception.domain.ErrorCode.INVALID_PAGE_REQUEST);
        }
    }

    /** 규칙별 마지막 확정 판정 : 확정(COMPLETED) 실행 중 그 규칙을 끝까지 판정(COMPLETED)한 가장 최근 실행 → [실행 ID, 검사 기준 일시] */
    public Map<StandardizationRule, Object[]> lastInspected(long projectId) {
        Map<StandardizationRule, Object[]> result = new java.util.EnumMap<>(StandardizationRule.class);
        jdbc.query("select distinct on (r.RULE_CD) r.RULE_CD, e.INSP_EXCN_SN, e.CRTR_DT from TB_INSP_EXCN_RULE r join TB_INSP_EXCN e on e.INSP_EXCN_SN = r.INSP_EXCN_SN"
                        + " where e.PRJ_SN = ? and e.EXCN_STTS_CD = 'COMPLETED' and r.EXCN_STTS_CD = 'COMPLETED' and r.RULE_CD = any(?)"
                        + " order by r.RULE_CD, e.INSP_EXCN_SN desc",
                rs -> {
                    result.put(StandardizationRule.valueOf(rs.getString(1)), new Object[]{rs.getLong(2), time(rs, 3)});
                }, projectId, names(Set.of(StandardizationRule.values())));
        return result;
    }

    public long count(long projectId, InspectionDto.Search search, Set<StandardizationRule> disabled) {
        Where where = where(projectId, search, disabled);
        return jdbc.queryForObject("select count(*) from TB_INSP_RSLT" + where.sql(), Long.class, where.args().toArray());
    }

    /** 같은 조건(같은 where)에 맞는 결과가 하나라도 있는지 — 건수를 세지 않는다 (AI-7 FINAL-CLOSE, AI7-D44) */
    public boolean exists(long projectId, InspectionDto.Search search, Set<StandardizationRule> disabled) {
        Where where = where(projectId, search, disabled);
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from TB_INSP_RSLT" + where.sql() + ")", Boolean.class, where.args().toArray()));
    }

    public List<InspectionDto.Finding> page(long projectId, InspectionDto.Search search, Set<StandardizationRule> disabled, Sort sort, long offset, int limit) {
        Where where = where(projectId, search, disabled);
        StringBuilder order = new StringBuilder();
        for (Sort.Order requested : sort) {
            String column = SORTS.get(requested.getProperty());
            if (column == null) continue;
            order.append(order.isEmpty() ? " order by " : ", ").append(column).append(requested.isAscending() ? " asc" : " desc").append(" nulls last");
        }
        if (order.isEmpty()) order.append(" order by ").append(SORTS.get("severity")).append(", RULE_CD");
        order.append(", INSP_RSLT_SN");
        List<Object> args = new ArrayList<>(where.args());
        args.add(offset);
        args.add(limit);
        return jdbc.query("select " + COLUMNS + " from TB_INSP_RSLT" + where.sql() + order + " offset ? limit ?",
                (rs, i) -> finding(rs, disabled), args.toArray());
    }

    public record FindingRow(InspectionDto.Finding finding, String fingerprint, String evidence) {
    }

    public Optional<FindingRow> find(long projectId, long findingId, Set<StandardizationRule> disabled) {
        return jdbc.query("select " + COLUMNS + ", HASH_VL, RSLT_EVDNC_CN::text from TB_INSP_RSLT where INSP_RSLT_SN = ? and PRJ_SN = ? and RULE_CD = any(?)",
                (rs, i) -> new FindingRow(finding(rs, disabled), rs.getString(20), rs.getString(21)),
                findingId, projectId, names(Set.of(StandardizationRule.values()))).stream().findFirst();
    }

    public List<InspectionDto.History> history(long projectId, long findingId) {
        return jdbc.query("select INSP_RSLT_HSTRY_SN, CHG_TYPE_CD, STTS_CD, EXCL_YN, CHG_RSN_CN, INSP_EXCN_SN, END_RSN_CD, END_DT, REG_DT, RGTR_ID"
                        + " from TB_INSP_RSLT_HSTRY where INSP_RSLT_SN = ? and PRJ_SN = ? order by INSP_RSLT_HSTRY_SN",
                (rs, i) -> {
                    String reason = rs.getString(7);
                    return new InspectionDto.History(rs.getLong(1), HistoryType.valueOf(rs.getString(2)), FindingStatus.valueOf(rs.getString(3)),
                            rs.getBoolean(4), rs.getString(5), (Long) rs.getObject(6, Long.class), reason == null ? null : EndReason.valueOf(reason),
                            time(rs, 8), time(rs, 9), rs.getString(10));
                }, findingId, projectId);
    }

    /** AI 근거 검증용 결과 스냅숏 (AI-7) : 프로젝트 범위 없이 ID 로 읽고, 프로젝트는 값으로 돌려준다 (비교는 근거 검증이 한다) */
    public record FindingSnapshot(long id, long projectId, String rule, String targetName, Long tableId, java.time.LocalDateTime modifiedAt) {
    }

    public List<FindingSnapshot> findingSnapshots(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("select INSP_RSLT_SN, PRJ_SN, RULE_CD, TRGT_CN, TBL_SN, MDFCN_DT from TB_INSP_RSLT where INSP_RSLT_SN = any(?)",
                (rs, i) -> new FindingSnapshot(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), (Long) rs.getObject(5, Long.class), time(rs, 6)),
                (Object) ids.toArray(Long[]::new));
    }

    /** AI 근거 검증용 실행 스냅숏 (AI-7) */
    public record RunSnapshot(long id, long projectId, String status, java.time.LocalDateTime modifiedAt) {
    }

    public List<RunSnapshot> runSnapshots(java.util.Collection<Long> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query("select INSP_EXCN_SN, PRJ_SN, EXCN_STTS_CD, MDFCN_DT from TB_INSP_EXCN where INSP_EXCN_SN = any(?)",
                (rs, i) -> new RunSnapshot(rs.getLong(1), rs.getLong(2), rs.getString(3), time(rs, 4)), (Object) ids.toArray(Long[]::new));
    }

    /**
     * 검사 기준 이후 변경 감지용 건수 (AI-7 AI7-D08) — 각 값은 {@code cap + 1} 까지만 센다. from = 비교 시작(실행 시작 일시), basisAt = 검사 기준 일시.
     * 모델 = 모델 변경 이력(CHG_DT ≥ from), 표준 사전 = 단어·용어·도메인 행의 MDFCN_DT ≥ from (추가·수정만 — 삭제는 행이 없어 보이지 않는다),
     * 프로젝트 = 프로젝트 행 MDFCN_DT ≥ from, 예외 = 예외 처리·해제 이력 REG_DT > basisAt. 새 저장 구조 없이 기존 인덱스·감사 칼럼만 쓴다
     */
    public record ChangeCounts(long model, long dictionary, long project, long exclusions) {
    }

    public ChangeCounts changeCounts(long projectId, java.time.LocalDateTime from, java.time.LocalDateTime basisAt, int cap) {
        java.sql.Timestamp start = java.sql.Timestamp.valueOf(from);
        java.sql.Timestamp basis = java.sql.Timestamp.valueOf(basisAt);
        int limit = cap + 1;
        long model = capped("select count(*) from (select 1 from TB_MDL_CHG_HSTRY where PRJ_SN = ? and CHG_DT >= ? limit ?) c", projectId, start, limit);
        long dictionary = capped("select count(*) from (select 1 from TB_STD_WORD where PRJ_SN = ? and MDFCN_DT >= ? limit ?) c", projectId, start, limit)
                + capped("select count(*) from (select 1 from TB_STD_TRM where PRJ_SN = ? and MDFCN_DT >= ? limit ?) c", projectId, start, limit)
                + capped("select count(*) from (select 1 from TB_STD_DMN where PRJ_SN = ? and MDFCN_DT >= ? limit ?) c", projectId, start, limit);
        long project = capped("select count(*) from (select 1 from TB_PRJ where PRJ_SN = ? and MDFCN_DT >= ? limit ?) c", projectId, start, limit);
        long exclusions = capped("select count(*) from (select 1 from TB_INSP_RSLT_HSTRY where PRJ_SN = ? and CHG_TYPE_CD in ('EXCLUDE', 'UNEXCLUDE') and REG_DT > ? limit ?) c",
                projectId, basis, limit);
        return new ChangeCounts(Math.min(model, limit), Math.min(dictionary, limit), project, exclusions);
    }

    private long capped(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    /** 요약용 : 규칙 × 상태 × 예외 × 종료 사유별 건수 */
    public record Count(StandardizationRule rule, FindingStatus status, boolean excluded, EndReason endReason, long count) {
    }

    public List<Count> counts(long projectId) {
        return jdbc.query("select RULE_CD, STTS_CD, EXCL_YN, END_RSN_CD, count(*) from TB_INSP_RSLT where PRJ_SN = ? and RULE_CD = any(?)"
                        + " group by RULE_CD, STTS_CD, EXCL_YN, END_RSN_CD",
                (rs, i) -> {
                    String reason = rs.getString(4);
                    return new Count(StandardizationRule.valueOf(rs.getString(1)), FindingStatus.valueOf(rs.getString(2)), rs.getBoolean(3),
                            reason == null ? null : EndReason.valueOf(reason), rs.getLong(5));
                }, projectId, names(Set.of(StandardizationRule.values())));
    }
}
