package com.lexorma.source.connection.jdbc;

import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.source.connection.domain.enums.DatabaseNameType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 원본 DB 의 JDBC 접속 규칙 (DBMS 별). 사용자가 준 문자열을 그대로 실행하지 않기 위한 한 곳이다.
 * <ul>
 *   <li><b>JDBC URL 을 입력받지 않는다.</b> 호스트·포트·데이터베이스명을 따로 받아 검증하고 여기서 URL 을 만든다. 계정·비밀번호는 URL 에 넣지 않는다 (접속할 때 속성으로 준다)</li>
 *   <li><b>드라이버는 DBMS 마다 정해진 하나</b>다. 드라이버 클래스 이름을 입력받지 않는다</li>
 *   <li><b>연결 옵션은 허용 목록</b>에 있는 이름·값만 받는다. 임의 옵션(파일 읽기, 다른 주소로의 접속, 로그 출력 등)을 열 수 있는 것은 없다</li>
 * </ul>
 * 이 클래스는 접속하지 않는다. 실제 연결·타임아웃·읽기 전용 설정은 메타데이터 수집 도구(AI-2)가 이 규칙으로 만든 값으로 한다.
 */
public final class SourceJdbcSettings {

    private static final Map<DbType, String> DRIVERS = Map.of(
            DbType.POSTGRESQL, "org.postgresql.Driver",
            DbType.ORACLE, "oracle.jdbc.OracleDriver",
            DbType.MYSQL, "com.mysql.cj.jdbc.Driver",
            DbType.MARIADB, "org.mariadb.jdbc.Driver");

    private static final Map<DbType, Integer> DEFAULT_PORTS = Map.of(DbType.POSTGRESQL, 5432, DbType.ORACLE, 1521, DbType.MYSQL, 3306, DbType.MARIADB, 3306);

    /** DBMS → 옵션 이름 → 허용 값 (드라이버가 쓰는 표기 그대로). 지금은 전송 암호화 방식만 연다 */
    private static final Map<DbType, Map<String, List<String>>> OPTIONS = Map.of(
            DbType.POSTGRESQL, Map.of("sslmode", List.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full")),
            DbType.MYSQL, Map.of("sslMode", List.of("DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY")),
            DbType.MARIADB, Map.of("sslMode", List.of("disable", "trust", "verify-ca", "verify-full")),
            DbType.ORACLE, Map.of());

    /** 호스트명·IPv4 : 영문·숫자·'.'·'-' */
    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.\\-]{0,253}[A-Za-z0-9])?");
    /** IPv6 (대괄호 없이 받는다) */
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    /** 데이터베이스명·서비스명·SID : 글자·숫자·'_'·'$'·'.'·'-' (URL 의 구분 문자가 될 수 있는 것은 받지 않는다) */
    private static final Pattern DATABASE_NAME = Pattern.compile("[\\p{L}\\p{N}_$.\\-]{1,255}");

    private SourceJdbcSettings() {
    }

    /** 이 DBMS 에 쓰는 드라이버 클래스 (고정) */
    public static String driverClassName(DbType dbType) {
        return DRIVERS.get(dbType);
    }

    /** 그 드라이버가 이 배포본에 들어 있는지. 정해진 이름만 확인하고 클래스를 초기화하지 않는다 */
    public static boolean isDriverAvailable(DbType dbType) {
        try {
            Class.forName(DRIVERS.get(dbType), false, SourceJdbcSettings.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static int defaultPort(DbType dbType) {
        return DEFAULT_PORTS.get(dbType);
    }

    public static boolean isValidHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 255) return false;
        return host.contains(":") ? IPV6.matcher(host).matches() : HOSTNAME.matcher(host).matches();
    }

    public static boolean isValidDatabaseName(String name) {
        return name != null && DATABASE_NAME.matcher(name).matches();
    }

    /** 이 DBMS 에서 쓸 수 있는 이름 종류인지 : Oracle 은 서비스명·SID, 그 밖에는 데이터베이스명 */
    public static boolean supports(DbType dbType, DatabaseNameType type) {
        return dbType == DbType.ORACLE ? type != DatabaseNameType.DATABASE : type == DatabaseNameType.DATABASE;
    }

    public static DatabaseNameType defaultNameType(DbType dbType) {
        return dbType == DbType.ORACLE ? DatabaseNameType.SERVICE_NAME : DatabaseNameType.DATABASE;
    }

    public static Set<String> allowedOptions(DbType dbType) {
        return OPTIONS.get(dbType).keySet();
    }

    /**
     * 옵션을 허용 목록의 표기로 맞춘다 (이름·값의 대소문자는 가리지 않고 받아 드라이버의 표기로 저장한다).
     *
     * @return 정리한 옵션 (이름순). 허용하지 않는 이름·값이 있으면 null
     */
    public static Map<String, String> normalizeOptions(DbType dbType, Map<String, String> options) {
        Map<String, String> result = new TreeMap<>();
        if (options == null) return result;
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) return null;
            String name = OPTIONS.get(dbType).keySet().stream().filter(key -> key.equalsIgnoreCase(entry.getKey().trim())).findFirst().orElse(null);
            if (name == null) return null;
            String value = OPTIONS.get(dbType).get(name).stream().filter(allowed -> allowed.equalsIgnoreCase(entry.getValue().trim())).findFirst().orElse(null);
            if (value == null || result.containsKey(name)) return null;
            result.put(name, value);
        }
        return result;
    }

    /**
     * 검증한 항목으로 JDBC URL 을 만든다. 계정·비밀번호는 들어가지 않는다.
     * 넣는 값은 모두 검증을 거친 것이어야 한다 (형식이 맞지 않으면 예외 : 검증하지 않은 값으로 URL 을 만들지 않는다).
     */
    public static String jdbcUrl(DbType dbType, String host, int port, String databaseName, DatabaseNameType nameType, Map<String, String> options) {
        if (!isValidHost(host) || port < 1 || port > 65535 || !isValidDatabaseName(databaseName) || !supports(dbType, nameType)) {
            throw new IllegalArgumentException("검증되지 않은 접속 정보입니다.");
        }
        Map<String, String> normalized = normalizeOptions(dbType, options);
        if (normalized == null) throw new IllegalArgumentException("허용하지 않는 연결 옵션입니다.");
        String address = (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
        String url = switch (dbType) {
            case POSTGRESQL -> "jdbc:postgresql://" + address + "/" + databaseName;
            case MYSQL -> "jdbc:mysql://" + address + "/" + databaseName;
            case MARIADB -> "jdbc:mariadb://" + address + "/" + databaseName;
            case ORACLE -> nameType == DatabaseNameType.SID ? "jdbc:oracle:thin:@" + address + ":" + databaseName : "jdbc:oracle:thin:@//" + address + "/" + databaseName;
        };
        if (normalized.isEmpty()) return url;
        StringBuilder query = new StringBuilder();
        new LinkedHashMap<>(normalized).forEach((name, value) -> query.append(query.isEmpty() ? "?" : "&").append(name).append('=').append(value));
        return url + query;
    }

    /** 표시용 : 대문자 DBMS 이름 */
    public static String label(DbType dbType) {
        return dbType.name().toUpperCase(Locale.ROOT);
    }
}
