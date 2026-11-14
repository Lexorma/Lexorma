package com.lexorma.source.connection.service;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import com.lexorma.core.crypto.SecretCipher;
import com.lexorma.core.crypto.SecretScope;
import com.lexorma.core.execution.ExecutionContext;
import com.lexorma.core.execution.ExecutionContextVerifier;
import com.lexorma.core.project.domain.enums.DbType;
import com.lexorma.core.project.guard.ProjectWriteGuard;
import com.lexorma.core.source.SourceConnectionDeletedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.lexorma.core.security.domain.dto.AccountDto;
import com.lexorma.source.catalog.repository.SourceSchemaRepository;
import com.lexorma.source.cleanup.SourceCatalogCleanup;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto;
import com.lexorma.source.connection.domain.dto.SourceConnectionDto.PasswordStatus;
import com.lexorma.source.connection.domain.entity.SourceConnection;
import com.lexorma.source.connection.domain.enums.DatabaseNameType;
import com.lexorma.source.connection.domain.enums.IdentifierComparison;
import com.lexorma.source.connection.jdbc.SourceHostPolicy;
import com.lexorma.source.connection.jdbc.SourceJdbcSettings;
import com.lexorma.source.connection.repository.SourceConnectionRepository;
import com.lexorma.source.catalog.service.SourceIdentifiers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 프로젝트의 외부 DB 연결 설정. 모든 조회·변경은 로그인한 프로젝트의 연결만 본다 (다른 프로젝트의 연결은 ID 를 알아도 없는 것과 같다).
 * <ul>
 *   <li>조회 : 프로젝트 멤버 (컨트롤러의 VIEWER 이상)</li>
 *   <li>등록·수정·삭제 : 그 프로젝트의 관리자 또는 ADMIN — {@link ExecutionContextVerifier#verifyProjectManager} 로 지금의 DB 상태를 본다
 *       (비활성 프로젝트, 멤버에서 빠진 사용자, 내려간 역할은 세션이 남아 있어도 거절된다)</li>
 *   <li>비밀번호 : {@link SecretCipher} 로 암호화해 저장한다. 자리(AAD) = source-connection / 프로젝트 / 이 연결의 ID / password —
 *       연결을 먼저 저장해 ID 를 받은 뒤 같은 트랜잭션에서 암호화한다. 암호화가 실패하면(키 없음) 연결도 저장되지 않는다. 응답에는 넣지 않는다</li>
 *   <li>삭제 : 그 연결의 원본 카탈로그를 함께 지운다. 물리·논리 모델은 원본 카탈로그와 이어져 있지 않아 그대로 남는다</li>
 * </ul>
 * 이 서비스는 설정만 다룬다 : 원본 DB 에 접속하지 않는다. 접속은 내부 도구(source/tool)가 실행될 때만 일어난다.
 */
@Service
@RequiredArgsConstructor
public class SourceConnectionService {

    public static final String SECRET_PURPOSE = "source-connection";
    public static final String SECRET_FIELD = "password";
    private static final int NAME_MAX = 100;
    private static final int USERNAME_MAX = 255;
    private static final int PASSWORD_MAX = 1000;
    private static final int DESCRIPTION_MAX = 1000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SourceConnectionRepository repository;
    private final SourceSchemaRepository schemaRepository;
    private final SourceCatalogCleanup cleanup;
    private final ExecutionContextVerifier verifier;
    private final SecretCipher secretCipher;
    private final SourceHostPolicy hostPolicy;
    private final ProjectWriteGuard projectWriteGuard;
    private final ApplicationEventPublisher events;

    @Transactional(readOnly = true)
    public List<SourceConnectionDto.Response> list(AccountDto accountDto) {
        return repository.findAllByProjectIdOrderByNameAscIdAsc(accountDto.getProjectId()).stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public SourceConnectionDto.Response detail(AccountDto accountDto, Long id) {
        return response(connection(accountDto.getProjectId(), id));
    }

    @Transactional
    public SourceConnectionDto.Response create(AccountDto accountDto, SourceConnectionDto.Request request) {
        Long projectId = manage(accountDto);
        Validated validated = validate(request, null);
        if (repository.existsByProjectIdAndNameIgnoreCase(projectId, validated.name())) throw invalid("같은 이름의 연결이 이미 있습니다.");
        try {
            // ID 를 먼저 받는다 : 비밀번호는 이 연결의 ID 에 묶어 암호화한다.
            // projectId 를 가진 엔티티라 ProjectWriteGuard 가 커밋 직전에 프로젝트가 아직 있는지 확인한다 (삭제와 겹친 등록)
            SourceConnection connection = repository.saveAndFlush(SourceConnection.builder().projectId(projectId).name(validated.name()).dbType(validated.dbType())
                    .host(validated.host()).port(validated.port()).databaseName(validated.databaseName()).databaseNameType(validated.nameType())
                    .username(validated.username()).options(validated.options()).identifierComparison(validated.comparison())
                    .description(validated.description()).enabled(request.enabled() == null || request.enabled()).build());
            if (validated.password() != null) connection.setEncryptedPassword(secretCipher.encrypt(validated.password(), scope(connection)));
            return response(connection);
        } catch (DataIntegrityViolationException e) {
            throw invalid("같은 이름의 연결이 이미 있습니다.");
        }
    }

    @Transactional
    public SourceConnectionDto.Response update(AccountDto accountDto, Long id, SourceConnectionDto.Request request) {
        Long projectId = manage(accountDto);
        // 프로젝트 → 연결 순서로 잠근다 : 진행 중인 수집 반영(연결 행 잠금)이 끝난 뒤에 카탈로그 유무를 본다 (반영과 규칙 변경이 엇갈리지 않는다)
        projectWriteGuard.lock(projectId);
        SourceConnection connection = repository.findForUpdate(id, projectId).orElseThrow(SourceConnectionService::notFound);
        Validated validated = validate(request, connection);
        if (repository.existsByProjectIdAndNameIgnoreCaseAndIdNot(projectId, validated.name(), id)) throw invalid("같은 이름의 연결이 이미 있습니다.");
        // 카탈로그의 비교용 이름은 DB 유형과 비교 규칙으로 만든 것이다 : 카탈로그가 있는 채로 바꾸면 같은 객체를 다르게 보게 된다
        boolean rulesChanged = connection.getDbType() != validated.dbType() || connection.getIdentifierComparison() != validated.comparison();
        if (rulesChanged && schemaRepository.countByConnection_Id(id) > 0) {
            throw invalid("원본 카탈로그가 있는 연결은 DB 유형과 식별자 비교 규칙을 바꿀 수 없습니다.");
        }
        connection.setName(validated.name());
        connection.setDbType(validated.dbType());
        connection.setHost(validated.host());
        connection.setPort(validated.port());
        connection.setDatabaseName(validated.databaseName());
        connection.setDatabaseNameType(validated.nameType());
        connection.setUsername(validated.username());
        connection.setOptions(validated.options());
        connection.setIdentifierComparison(validated.comparison());
        connection.setDescription(validated.description());
        if (request.enabled() != null) connection.setEnabled(request.enabled());
        // 비밀번호를 비워 둔 수정은 저장된 암호문을 그대로 둔다
        if (validated.password() != null) connection.setEncryptedPassword(secretCipher.encrypt(validated.password(), scope(connection)));
        try {
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw invalid("같은 이름의 연결이 이미 있습니다.");
        }
        return response(connection);
    }

    /** 연결과 그 연결의 원본 카탈로그(수집 실행·버전·변경 이력 포함)를 한 트랜잭션에서 지운다. 지운 행 수를 돌려준다 */
    @Transactional
    public int delete(AccountDto accountDto, Long id) {
        Long projectId = manage(accountDto);
        // 프로젝트(공유) → 연결(배타) 순서로 잠근다 : 같은 연결로 적재하는 트랜잭션(수집 반영·SourceCatalogWriter)과 같은 순서라 서로를 기다리지 않고,
        // 진행 중인 반영이 끝난 뒤에 지운다 (반영이 먼저 끝나면 그 결과까지 지우고, 지운 뒤의 반영은 연결이 없어 실패한다)
        projectWriteGuard.lock(projectId);
        repository.findForUpdate(id, projectId).orElseThrow(SourceConnectionService::notFound);
        // 이 연결에 기대는 다른 모듈의 데이터(원본 매핑·기준 연결, lexorma-data)를 같은 트랜잭션에서 먼저 정리한다 (실패하면 삭제 전체가 롤백된다)
        events.publishEvent(new SourceConnectionDeletedEvent(projectId, id));
        int rows = cleanup.deleteCatalog(id);
        repository.delete(repository.findByIdAndProjectId(id, projectId).orElseThrow(SourceConnectionService::notFound));
        repository.flush();
        return rows;
    }

    /**
     * 저장하지 않은 연결 설정 (연결 테스트, AI-2b). 등록·수정과 같은 검증을 거친다. 저장하지 않는다.
     *
     * @param password 접속에 쓸 비밀번호 : 요청에 있으면 그것, 비워 둔 수정이면 저장된 것 — 단 DBMS·호스트·포트·데이터베이스·계정이 저장된 것과 같을 때만
     *                 (다른 주소로 바꾼 설정에 저장된 비밀번호를 보내지 않는다)
     */
    public record Draft(SourceConnection connection, java.util.function.Supplier<String> password) {
    }

    /** @param id 수정 중인 연결 (없으면 새 연결) */
    @Transactional(readOnly = true)
    public Draft draft(AccountDto accountDto, Long id, SourceConnectionDto.Request request) {
        Long projectId = manage(accountDto);
        SourceConnection current = id == null ? null : connection(projectId, id);
        // 테스트에는 연결명이 필요 없다 : 비어 있으면 검증만 통과하게 채운다
        if (request != null && trim(request.name()) == null) {
            request = new SourceConnectionDto.Request("connection-test", request.dbType(), request.host(), request.port(), request.databaseName(), request.databaseNameType(),
                    request.username(), request.password(), request.options(), request.identifierComparison(), request.description(), request.enabled());
        }
        Validated validated = validate(request, current);
        SourceConnection draft = SourceConnection.builder().id(current == null ? null : current.getId()).projectId(projectId).name(validated.name())
                .dbType(validated.dbType()).host(validated.host()).port(validated.port()).databaseName(validated.databaseName()).databaseNameType(validated.nameType())
                .username(validated.username()).options(validated.options()).identifierComparison(validated.comparison()).enabled(true).build();
        if (validated.password() != null) {
            String password = validated.password();
            return new Draft(draft, () -> password);
        }
        if (current == null || current.getEncryptedPassword() == null) return new Draft(draft, () -> null);
        boolean sameTarget = current.getDbType() == draft.getDbType() && current.getHost().equalsIgnoreCase(draft.getHost()) && current.getPort().equals(draft.getPort())
                && current.getDatabaseName().equals(draft.getDatabaseName()) && current.getDatabaseNameType() == draft.getDatabaseNameType()
                && current.getUsername().equals(draft.getUsername());
        if (!sameTarget) throw invalid("접속 대상이나 계정을 바꾸면 비밀번호를 다시 입력해야 합니다.");
        String encrypted = current.getEncryptedPassword();
        SecretScope scope = scope(current);
        return new Draft(draft, () -> secretCipher.decrypt(encrypted, scope));
    }

    /** 비밀번호의 암호화 자리 : 다른 프로젝트·다른 연결의 암호문을 옮겨 와도 읽히지 않는다 */
    public static SecretScope scope(SourceConnection connection) {
        return SecretScope.ofProject(SECRET_PURPOSE, connection.getProjectId(), connection.getId(), SECRET_FIELD);
    }

    /** 저장된 옵션 (JSON) → 이름·값 */
    public static Map<String, String> options(SourceConnection connection) {
        if (connection.getOptions() == null || connection.getOptions().isBlank()) return Map.of();
        return JSON.readValue(connection.getOptions(), new TypeReference<TreeMap<String, String>>() {
        });
    }

    /** 변경 권한 : 프로젝트 관리자 또는 ADMIN (지금의 DB 기준). 확인한 프로젝트 ID 를 돌려준다 */
    private Long manage(AccountDto accountDto) {
        return verifier.verifyProjectManager(ExecutionContext.user(accountDto)).projectId();
    }

    private SourceConnection connection(Long projectId, Long id) {
        return (id == null ? java.util.Optional.<SourceConnection>empty() : repository.findByIdAndProjectId(id, projectId)).orElseThrow(SourceConnectionService::notFound);
    }

    private SourceConnectionDto.Response response(SourceConnection connection) {
        return new SourceConnectionDto.Response(connection.getId(), connection.getName(), connection.getDbType().name(), connection.getHost(), connection.getPort(),
                connection.getDatabaseName(), connection.getDatabaseNameType().name(), connection.getUsername(), connection.getEncryptedPassword() != null,
                passwordStatus(connection), options(connection), connection.getIdentifierComparison().name(), connection.getDescription(),
                Boolean.TRUE.equals(connection.getEnabled()), SourceJdbcSettings.isDriverAvailable(connection.getDbType()), schemaRepository.countByConnection_Id(connection.getId()));
    }

    private PasswordStatus passwordStatus(SourceConnection connection) {
        if (connection.getEncryptedPassword() == null) return PasswordStatus.NONE;
        if (!secretCipher.isAvailable()) return PasswordStatus.KEY_NOT_CONFIGURED;
        try {
            secretCipher.decrypt(connection.getEncryptedPassword(), scope(connection));
            return PasswordStatus.CONFIGURED;
        } catch (CustomException e) {
            return PasswordStatus.REREGISTER_REQUIRED;
        }
    }

    private record Validated(String name, DbType dbType, String host, int port, String databaseName, DatabaseNameType nameType, String username, String password,
                             String options, IdentifierComparison comparison, String description) {
    }

    /** DBMS 별 필수 항목과 형식. 여기서 받아 준 값만 JDBC URL 을 만드는 데 쓰인다 (SourceJdbcSettings) */
    private Validated validate(SourceConnectionDto.Request request, SourceConnection current) {
        if (request == null) throw invalid("{0}을(를) 입력해주세요.", Messages.get("연결명"));
        String name = trim(request.name());
        if (name == null) throw invalid("{0}을(를) 입력해주세요.", Messages.get("연결명"));
        if (name.length() > NAME_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get("연결명"), NAME_MAX);
        String description = trim(request.description());
        if (description != null && description.length() > DESCRIPTION_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get("설명"), DESCRIPTION_MAX);

        // DbType.fromOrDefault 는 모르는 값을 PostgreSQL 로 바꾼다 : 원본의 DBMS 는 드라이버와 식별자 규칙을 정하므로 여기서는 정확한 값만 받는다
        DbType dbType = parse(DbType.class, request.dbType(), current == null ? null : current.getDbType(), "대상 DB 유형이 올바르지 않습니다.");
        if (dbType == null) throw invalid("대상 DB 유형이 올바르지 않습니다.");

        String host = trim(request.host());
        if (host == null) throw invalid("{0}을(를) 입력해주세요.", Messages.get("호스트"));
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1); // IPv6 를 대괄호로 적은 경우
        if (!SourceJdbcSettings.isValidHost(host)) throw invalid("호스트의 형식이 올바르지 않습니다. 호스트명 또는 IP 주소만 입력해주세요.");
        if (!hostPolicy.isAllowed(host)) throw invalid("이 호스트로는 연결을 등록할 수 없습니다. 운영자가 허용한 호스트만 사용할 수 있습니다.");
        int port = request.port() == null ? (current != null && current.getDbType() == dbType ? current.getPort() : SourceJdbcSettings.defaultPort(dbType)) : request.port();
        if (port < 1 || port > 65535) throw invalid("포트는 1 ~ 65535 사이여야 합니다.");

        String databaseName = trim(request.databaseName());
        if (databaseName == null) throw invalid("{0}을(를) 입력해주세요.", Messages.get(dbType == DbType.ORACLE ? "서비스명 또는 SID" : "데이터베이스명"));
        if (!SourceJdbcSettings.isValidDatabaseName(databaseName)) throw invalid("데이터베이스명의 형식이 올바르지 않습니다.");
        DatabaseNameType nameType = parse(DatabaseNameType.class, request.databaseNameType(),
                current != null && current.getDbType() == dbType ? current.getDatabaseNameType() : SourceJdbcSettings.defaultNameType(dbType), "데이터베이스명 종류가 올바르지 않습니다.");
        if (!SourceJdbcSettings.supports(dbType, nameType)) throw invalid("이 DB 유형에서 쓸 수 없는 데이터베이스명 종류입니다.");

        String username = trim(request.username());
        if (username == null) throw invalid("{0}을(를) 입력해주세요.", Messages.get("DB 사용자명"));
        if (username.length() > USERNAME_MAX || username.chars().anyMatch(Character::isISOControl)) throw invalid("DB 사용자명의 형식이 올바르지 않습니다.");
        // 비밀번호는 다듬지 않는다 (앞뒤 공백도 값이다). 빈 값은 "주지 않음" 이다
        String password = request.password() == null || request.password().isEmpty() ? null : request.password();
        if (password != null && password.length() > PASSWORD_MAX) throw invalid("{0}은(는) {1}자까지 입력할 수 있습니다.", Messages.get("비밀번호"), PASSWORD_MAX);

        // 옵션 : 요청에 없으면 (수정) 그대로 둔다. DB 유형이 바뀌었으면 이전 옵션은 버린다
        Map<String, String> requested = request.options() != null ? request.options() : current != null && current.getDbType() == dbType ? options(current) : Map.of();
        Map<String, String> options = SourceJdbcSettings.normalizeOptions(dbType, requested);
        if (options == null) throw invalid("허용하지 않는 연결 옵션입니다. 사용할 수 있는 옵션 : {0}", String.join(", ", SourceJdbcSettings.allowedOptions(dbType)));

        IdentifierComparison comparison = parse(IdentifierComparison.class, request.identifierComparison(),
                current != null && current.getDbType() == dbType ? current.getIdentifierComparison() : IdentifierComparison.DBMS_DEFAULT, "식별자 비교 규칙이 올바르지 않습니다.");
        if (!SourceIdentifiers.supports(dbType, comparison)) throw invalid("이 DB 유형에서는 식별자 비교 규칙을 선택할 수 없습니다.");
        return new Validated(name, dbType, host, port, databaseName, nameType, username, password, options.isEmpty() ? null : JSON.writeValueAsString(options), comparison, description);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String message) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(message);
        }
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static CustomException notFound() {
        return new CustomException(ErrorCode.SOURCE_CONNECTION_NOT_FOUND);
    }

    private static CustomException invalid(String message, Object... args) {
        return new CustomException(ErrorCode.INVALID_SOURCE_CONNECTION, message, args);
    }
}
