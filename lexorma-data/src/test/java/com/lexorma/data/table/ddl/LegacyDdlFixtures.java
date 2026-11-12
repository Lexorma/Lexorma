package com.lexorma.data.table.ddl;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.data.column.domain.entity.ColumnInfo;
import com.lexorma.data.table.domain.entity.TableInfo;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** M-1 이전 DDL 과 같은지 비교하는 고정 입력 (PK·UK·인덱스·주석·참조 테이블명 FK 의 모든 분기) */
final class LegacyDdlFixtures {

    private LegacyDdlFixtures() {
    }

    static Map<String, String> outputs() {
        TableInfo order = TableInfo.builder().tableName("TB_ORDER").tableKorName("주문").tableDescription("주문 테이블").build();
        TableInfo user = TableInfo.builder().tableName("TB_USER").build();
        TableInfo code = TableInfo.builder().tableName("TB_CODE").build();
        TableInfo dept = TableInfo.builder().tableName("TB_DEPT").build();

        ColumnInfo userId = ColumnInfo.builder().columnName("USER_ID").dataType("VARCHAR").maxLength(20).isPk(true).tableInfo(user).build();
        ColumnInfo codeGrp = ColumnInfo.builder().columnName("GRP_CD").dataType("VARCHAR(10)").isPk(true).tableInfo(code).build();
        ColumnInfo codeCd = ColumnInfo.builder().columnName("CD").dataType("VARCHAR(10)").isPk(true).tableInfo(code).build();
        ColumnInfo orderNo = ColumnInfo.builder().columnName("ORDER_NO").columnDesc("주문 번호").dataType("BIGINT").isPk(true).isNullable(false).sortOrder(1).tableInfo(order).build();

        List<ColumnInfo> columns = List.of(
                orderNo,
                ColumnInfo.builder().columnName("USER_ID").columnDesc("주문자").dataType("VARCHAR").maxLength(30).isRequired(true).refTableName(" tb_user ").sortOrder(2).build(),
                ColumnInfo.builder().columnName("STAT_CD").columnDesc("상태 코드").dataType("VARCHAR(10)").refTableName("TB_CODE").isIndex(true).sortOrder(3).build(),
                ColumnInfo.builder().columnName("DEPT_ID").columnDesc("부서").dataType("NUMBER").precision(new BigDecimal(10)).refTableName("TB_DEPT").sortOrder(4).build(),
                ColumnInfo.builder().columnName("ETC_ID").dataType("NUMBER").refTableName("TB_NONE").sortOrder(5).build(),
                ColumnInfo.builder().columnName("PARENT_NO").columnDesc("상위 주문").dataType("BIGINT").refTableName("TB_ORDER").sortOrder(6).build(),
                ColumnInfo.builder().columnName("ORDER_DT").columnDesc("주문 일시").dataType("TIMESTAMP").defaultValue("CURRENT_TIMESTAMP").isIndex(true).sortOrder(7).build(),
                ColumnInfo.builder().columnName("EMAIL").dataType("VARCHAR").isUnique(true).isIndex(true).sortOrder(8).build(),
                ColumnInfo.builder().columnName("AMT").columnDesc("금액's").dataType("DECIMAL").precision(new BigDecimal(12)).scale(2).defaultValue("0").sortOrder(9).build(),
                ColumnInfo.builder().columnName("MEMO").dataType("TEXT").sortOrder(null).build());

        Map<String, List<ColumnInfo>> refPk = Map.of(
                "TB_USER", List.of(userId),
                "TB_CODE", List.of(codeGrp, codeCd),
                "TB_DEPT", List.of(),
                "TB_ORDER", List.of(orderNo));

        Map<String, String> outputs = new LinkedHashMap<>();
        for (DbType db : DbType.values()) {
            TableDdlGenerator.Result r = TableDdlGenerator.generate(db, order, columns, refPk);
            outputs.put(db.name(), r.ddl() + "\n-- warnings\n" + String.join("\n", r.warnings()));
            TableDdlGenerator.Result empty = TableDdlGenerator.generate(db, user, List.of(), Map.of());
            outputs.put(db.name() + "_EMPTY", empty.ddl() + "\n-- warnings\n" + String.join("\n", empty.warnings()));
        }
        return outputs;
    }
}
