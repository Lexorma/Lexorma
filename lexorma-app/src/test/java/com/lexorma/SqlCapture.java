package com.lexorma;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 테스트 전용 : Hibernate 가 실제로 보내는 SQL 을 모은다 (application-test.yml 의 hibernate.session_factory.statement_inspector).
 * {@link #start()} 와 {@link #stop()} 사이에만 모으므로 다른 테스트에는 영향이 없다 (SQL 을 바꾸지 않는다).
 */
public class SqlCapture implements StatementInspector {

    private static final List<String> STATEMENTS = new ArrayList<>();
    private static volatile boolean recording;

    @Override
    public String inspect(String sql) {
        if (recording) {
            synchronized (STATEMENTS) {
                STATEMENTS.add(sql);
            }
        }
        return sql;
    }

    static void start() {
        synchronized (STATEMENTS) {
            STATEMENTS.clear();
        }
        recording = true;
    }

    /** 모은 SQL 중 그 테이블의 UPDATE 문 */
    static List<String> stop(String table) {
        recording = false;
        synchronized (STATEMENTS) {
            String prefix = "update " + table.toLowerCase(Locale.ROOT) + " ";
            return STATEMENTS.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
    }
}
