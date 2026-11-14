package com.lexorma.data.view.ddl;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.table.ddl.TableDdlGenerator;
import com.lexorma.data.view.domain.entity.PhysicalView;
import com.lexorma.data.view.domain.entity.PhysicalViewReference;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * VIEW 의 CREATE VIEW DDL (M-15). 실행하지 않는다.
 * <ul>
 *   <li>{@code CREATE VIEW 이름 AS\n정의;} — 이름은 테이블 DDL 과 같은 규칙(기본 스키마가 아니면 스키마명.이름, 식별자는 따옴표 없이 : 이름 규칙으로 검증된 값만)</li>
 *   <li>정의 SQL 은 원문 그대로 넣는다 (끝의 ; 와 그 뒤는 빼고 생성기가 ; 하나를 붙인다. 원문이 -- 주석으로 끝나면 ; 를 다음 줄에). 생성기가 만든 부분과 원문을 섞지 않는다</li>
 *   <li>출력 컬럼 목록은 넣지 않는다 (모델링 정보). CREATE OR REPLACE 는 쓰지 않는다 (DBMS 마다 바꿀 수 있는 범위가 다르다)</li>
 *   <li>논리명 COMMENT : PostgreSQL {@code COMMENT ON VIEW}, Oracle {@code COMMENT ON TABLE} (VIEW 에도 쓴다), MySQL·MariaDB 는 VIEW 에 COMMENT 가 없어 넣지 않는다</li>
 * </ul>
 * PostgreSQL 은 실제 DB 에서 실행해 확인했다. Oracle·MySQL·MariaDB 는 같은 형식의 표준 문법이지만 실제 DBMS 에서 확인하지 않았다 (docs/view-management-m15.md).
 * 정의 SQL 이 대상 DBMS 문법인지는 보장하지 않는다.
 */
public final class ViewDdlGenerator {

    private ViewDdlGenerator() {
    }

    public static TableDdlGenerator.Result generate(DbType dbType, PhysicalView view, String defaultSchemaName) {
        List<String> warnings = new ArrayList<>();
        return new TableDdlGenerator.Result(statement(dbType, view, defaultSchemaName, warnings), warnings);
    }

    /**
     * 여러 VIEW 를 한 스크립트로 (프로젝트·스키마 단위 Export). 내부 VIEW 참조를 따라 참조되는 VIEW 를 먼저 낸다 (같은 단계는 이름순).
     * 순환이 있으면 그 VIEW 들은 끝에 이름순으로 내고 경고한다 (등록 때 순환을 막으므로 보통은 없다).
     * 테이블은 이 스크립트에 없다 : 참조하는 테이블을 먼저 만들어야 하고, 미확인 참조는 순서를 보장하지 않는다.
     */
    public static TableDdlGenerator.Result generateAll(DbType dbType, List<PhysicalView> views, String defaultSchemaName) {
        return generateAll(dbType, views, defaultSchemaName, null);
    }

    /**
     * @param includedTableIds 같은 스크립트에서 먼저 만드는 테이블 (통합 Export M-11). 주면 그 밖의 테이블을 참조하는 VIEW 를 경고한다. null 이면 테이블 참조는 보지 않는다
     */
    public static TableDdlGenerator.Result generateAll(DbType dbType, List<PhysicalView> views, String defaultSchemaName, Set<Long> includedTableIds) {
        List<String> warnings = new ArrayList<>();
        List<PhysicalView> ordered = order(views, warnings);
        Set<String> unresolved = new TreeSet<>();
        Set<String> outside = new TreeSet<>();
        Set<String> outsideTables = new TreeSet<>();
        Set<Long> included = new LinkedHashSet<>(views.stream().map(PhysicalView::getId).toList());
        StringBuilder sql = new StringBuilder();
        for (PhysicalView view : ordered) {
            if (!sql.isEmpty()) sql.append('\n');
            sql.append(statement(dbType, view, defaultSchemaName, warnings));
            for (PhysicalViewReference reference : view.getReferences()) {
                if (!reference.isInternal()) unresolved.add(view.getViewName() + " → " + externalName(reference));
                else if (reference.getReferencedView() != null && !included.contains(reference.getReferencedView().getId())) outside.add(view.getViewName() + " → " + reference.getReferencedView().getViewName());
                else if (includedTableIds != null && reference.getReferencedTable() != null && !includedTableIds.contains(reference.getReferencedTable().getId())) {
                    outsideTables.add(view.getViewName() + " → " + reference.getReferencedTable().getTableName());
                }
            }
        }
        if (!outside.isEmpty()) warnings.add(Messages.get("이 스크립트 밖의 VIEW 를 참조합니다 (먼저 만들어야 합니다) : {0}", String.join(", ", outside)));
        if (!outsideTables.isEmpty()) warnings.add(Messages.get("이 스크립트 밖의 테이블을 참조합니다 (대상 DB 에 먼저 있어야 합니다) : {0}", String.join(", ", outsideTables)));
        if (!unresolved.isEmpty()) warnings.add(Messages.get("모델에 없는(미확인) 참조 객체가 있어 생성 순서를 보장하지 않습니다 : {0}", String.join(", ", unresolved)));
        return new TableDdlGenerator.Result(sql.toString(), warnings);
    }

    /** 내부 VIEW 참조가 순환해 생성 순서를 정할 수 없는 VIEW 이름 (이름순). 없으면 빈 목록 */
    public static List<String> cyclicViews(List<PhysicalView> views) {
        return sort(views).cyclic();
    }

    private static String statement(DbType dbType, PhysicalView view, String defaultSchemaName, List<String> warnings) {
        String name = qualifiedName(view, defaultSchemaName);
        ViewDefinitionSql.Checked body;
        try {
            body = ViewDefinitionSql.check(dbType, view.getDefinition());
        } catch (CustomException e) {
            // 저장할 때는 통과했지만 프로젝트 DBMS 를 바꾼 경우 등 : 원문을 그대로 넣고 알린다 (; 는 다음 줄)
            body = new ViewDefinitionSql.Checked(view.getDefinition().stripTrailing(), true);
            warnings.add(Messages.get("{0} : 정의 SQL 이 {1} 기준 검사를 통과하지 않아 원문 그대로 넣었습니다.", view.getViewName(), dbType.getLabel()));
        }
        StringBuilder sql = new StringBuilder("CREATE VIEW ").append(name).append(" AS\n").append(body.body());
        sql.append(body.endsWithLineComment() ? "\n;" : ";").append('\n');
        String comment = comment(view);
        if (comment != null) {
            switch (dbType) {
                case POSTGRESQL -> sql.append("COMMENT ON VIEW ").append(name).append(" IS ").append(quote(comment)).append(";\n");
                case ORACLE -> sql.append("COMMENT ON TABLE ").append(name).append(" IS ").append(quote(comment)).append(";\n");
                default -> warnings.add(Messages.get("{0} : {1} 의 VIEW 에는 COMMENT 를 붙일 수 없어 논리명을 넣지 않았습니다.", view.getViewName(), dbType.getLabel()));
            }
        }
        return sql.toString();
    }

    /** 기본 스키마 VIEW 는 defaultSchemaName 을 받았을 때만 스키마를 붙인다 (테이블 DDL 과 같다) */
    static String qualifiedName(PhysicalView view, String defaultSchemaName) {
        if (view.getSchema() == null || Boolean.TRUE.equals(view.getSchema().getIsDefault())) {
            return defaultSchemaName == null ? view.getViewName() : defaultSchemaName + "." + view.getViewName();
        }
        return view.getSchema().getSchemaName() + "." + view.getViewName();
    }

    /** 참조되는 VIEW 먼저 (Kahn), 같은 단계는 이름순 */
    static List<PhysicalView> order(List<PhysicalView> views, List<String> warnings) {
        Sorted sorted = sort(views);
        if (!sorted.cyclic().isEmpty()) {
            warnings.add(Messages.get("순환 참조가 있어 생성 순서를 정할 수 없는 VIEW 가 있습니다 : {0}", String.join(", ", sorted.cyclic())));
        }
        return sorted.views();
    }

    /** @param views 생성 순서 (순환에 걸린 VIEW 는 끝에 이름순) @param cyclic 순환에 걸린 VIEW 이름 */
    private record Sorted(List<PhysicalView> views, List<String> cyclic) {}

    private static Sorted sort(List<PhysicalView> views) {
        Map<Long, PhysicalView> byId = new LinkedHashMap<>();
        views.stream().sorted(Comparator.comparing(PhysicalView::getViewName)).forEach(v -> byId.put(v.getId(), v));
        Map<Long, Set<Long>> dependsOn = new LinkedHashMap<>();
        byId.values().forEach(v -> dependsOn.put(v.getId(), new LinkedHashSet<>(v.getReferences().stream()
                .filter(r -> r.getReferencedView() != null && byId.containsKey(r.getReferencedView().getId()) && !r.getReferencedView().getId().equals(v.getId()))
                .map(r -> r.getReferencedView().getId()).toList())));
        List<PhysicalView> result = new ArrayList<>();
        Set<Long> done = new LinkedHashSet<>();
        boolean progressed = true;
        while (progressed && done.size() < byId.size()) {
            progressed = false;
            for (PhysicalView view : byId.values()) {
                if (done.contains(view.getId()) || !done.containsAll(dependsOn.get(view.getId()))) continue;
                done.add(view.getId());
                result.add(view);
                progressed = true;
                break; // 이름순으로 하나씩 : 새로 풀린 VIEW 도 이름 순서를 지킨다
            }
        }
        List<PhysicalView> cyclic = byId.values().stream().filter(v -> !done.contains(v.getId())).toList();
        result.addAll(cyclic);
        return new Sorted(result, cyclic.stream().map(PhysicalView::getViewName).toList());
    }

    static String externalName(PhysicalViewReference reference) {
        return reference.getSchemaName() == null ? reference.getObjectName() : reference.getSchemaName() + "." + reference.getObjectName();
    }

    private static String comment(PhysicalView view) {
        if (notBlank(view.getViewKorName())) return view.getViewKorName().trim();
        if (notBlank(view.getViewDescription())) return view.getViewDescription().trim();
        return null;
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
