package com.lexorma.source.tool;

import com.lexorma.core.project.domain.enums.DbType;

/**
 * 원본 DB 를 다루는 내부 도구 하나 (연결 확인, 스키마·테이블·컬럼·제약조건·인덱스·관계 조회 …).
 * Lexorma 안의 Java 코드이며 별도 서버나 프로세스가 아니다. DBMS 마다 구현을 따로 둘 수 있다 ({@link #supports}).
 * <ul>
 *   <li>도구는 {@link SourceToolExecutor} 로만 실행한다 : 권한·프로젝트 범위·연결 상태를 확인한 {@link SourceToolContext} 는 실행기만 만들 수 있다.
 *       도구는 프로젝트나 연결을 스스로 고르지 않고 컨텍스트가 가리키는 연결에만 접속한다</li>
 *   <li>LLM 없이 실행된다. 사용자 요청(최신화)·비동기 작업·정기 배치가 같은 도구를 쓴다</li>
 *   <li><b>AI 에 공개하는 도구가 아니다.</b> 원본 DB 에 접속하는 도구는 AI 의 Tool Calling 목록에 넣지 않는다 —
 *       AI 가 부를 수 있는 것은 Lexorma 자체 DB 를 읽는 조회 도구뿐이고, 그 목록은 따로 만든다 (AI-3)</li>
 *   <li>원본 DB 에는 메타데이터를 읽는 정해진 조회만 보낸다. DML·DDL 과 입력으로 받은 SQL 은 실행하지 않는다</li>
 * </ul>
 * 결과는 DBMS 와 상관없는 공통 모양으로 돌려준다 (카탈로그에 적재할 것은 {@code SourceCatalogSpec}).
 *
 * @param <I> 입력 (없으면 {@link Void})
 * @param <O> 결과
 */
public interface SourceMetadataTool<I, O> {

    /** 도구 이름 ({@link SourceTools} 의 값). 같은 이름의 도구를 DBMS 별로 여러 개 둘 수 있다 */
    String name();

    /** 이 구현이 맡는 DBMS */
    boolean supports(DbType dbType);

    /** 입력의 타입 : 실행기가 실행 전에 확인한다 */
    Class<I> inputType();

    /**
     * @param context 실행기가 확인한 실행 컨텍스트와 접속 정보. 이 실행 동안만 유효하다 (보관하지 않는다)
     */
    O execute(SourceToolContext context, I input);
}
