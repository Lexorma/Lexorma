package com.lexorma.common.i18n;

import java.util.HashMap;
import java.util.Map;

/**
 * 영어 문구 : 한국어 원문 → 영어 ({@link Messages})
 * <p>
 * ErrorCode 의 메시지는 여기가 아니라 code 를 키로 i18n/errors_{ko,en}.properties 에 둔다.
 * 그 밖의 서버 메시지를 추가하면 여기에 같은 원문을 키로 영어를 넣는다. (없으면 영어 화면에도 한국어로 나간다)
 * {0}, {1} 은 값이 들어갈 자리다.
 */
final class EnglishMessages {

    static final Map<String, String> MESSAGES = new HashMap<>();

    private EnglishMessages() {
    }

    private static void add(String korean, String english) {
        if (MESSAGES.put(korean, english) != null) {
            throw new IllegalStateException("Duplicated message : " + korean);
        }
    }

    static {
        // ---------------------------------------------------------------- 인증 / 권한 / 공통
        add("존재하지 않는 프로젝트입니다.", "The project does not exist.");

        // ---------------------------------------------------------------- 엑셀 / 파일
        add("파일을 찾을 수 없습니다: {0}", "File not found: {0}");
        add("파일 경로가 잘못되었습니다.", "The file path is invalid.");
        add("파일을 읽을 수 없습니다.", "The file could not be read.");
        add("엑셀(.xlsx) 파일 형식이 아닙니다.", "The file is not an Excel (.xlsx) file.");
        add("엑셀 파일을 읽을 수 없습니다.", "The Excel file could not be read.");
        add("Excel 파일에 {0}번째 시트가 존재하지 않습니다.", "The Excel file does not have sheet {0}.");

        // ---------------------------------------------------------------- 데이터 사전
        add("누락된 문자열 : {0}", "Missing word: {0}");
        add("도메인을 찾을 수 없습니다: {0}", "Domain not found: {0}");
        add("표준 단어를 찾을 수 없습니다.", "The standard word could not be found.");
        add("표준 용어가 연결되지 않았습니다.", "No standard term is linked.");
        add("컬럼명이 표준 용어의 영문약어와 다릅니다.", "The column name differs from the abbreviation of its standard term.");
        add("표준 도메인 정의와 다릅니다 : {0}", "Differs from the standard domain definition: {0}");
        add("길이", "length");
        add("정밀도", "precision");
        add("소수 자릿수", "scale");
        add("승인된 도메인은 삭제할 수 없습니다: {0}", "Approved domains cannot be deleted: {0}");
        add("표준용어에서 사용 중인 도메인은 삭제할 수 없습니다: {0}", "Domains used by standard terms cannot be deleted: {0}");

        // ---------------------------------------------------------------- 컬럼
        add("컬럼 설명", "column description");
        add("데이터 타입", "data type");
        add("승인된 테이블에만 컬럼을 등록할 수 있습니다.", "Columns can only be registered on approved tables.");
        add("삭제할 컬럼이 이 테이블에 없습니다. (ID {0})", "The column to delete does not exist in this table. (ID {0})");
        add("{0} : 승인된 컬럼은 삭제할 수 없습니다.", "{0}: approved columns cannot be deleted.");
        add("이 테이블에 없는 컬럼입니다. 화면을 새로 열어주세요.", "This column does not exist in this table. Please reopen the screen.");
        add("{0} : 삭제와 수정을 함께 할 수 없습니다.", "{0}: a column cannot be deleted and edited at the same time.");
        add("{0} : 같은 컬럼이 두 번 들어 있습니다.", "{0}: the same column is included twice.");
        add("표준용어를 찾을 수 없습니다.", "The standard term could not be found.");
        add("{0} : 이 테이블에 이미 같은 컬럼명이 있습니다.", "{0}: this table already has a column with the same name.");
        add("{0} : {1}을(를) 입력해주세요.", "{0}: enter the {1}.");
        add("{0} : 길이/정밀도/소수 자릿수는 0 이상이어야 합니다.", "{0}: length, precision and scale must be 0 or greater.");
        add("공통 컬럼은 {0}개까지 등록할 수 있습니다.", "You can register up to {0} common columns.");
        add("공통 컬럼의 표준용어를 찾을 수 없습니다. (ID {0})", "The standard term of a common column could not be found. (ID {0})");
        add("공통 컬럼 세트에 같은 컬럼명이 중복되어 있습니다 : {0}", "The common column set contains the same column name more than once: {0}");

        // ---------------------------------------------------------------- 스키마 / 프로젝트
        add("기본 스키마는 삭제할 수 없습니다.", "The default schema cannot be deleted.");
        add("기본 스키마의 이름은 바꿀 수 없습니다.", "The name of the default schema cannot be changed.");
        add("{0} 에 테이블이 있습니다.", "{0} contains tables.");
        add("삭제를 할 수 없습니다.", "It cannot be deleted.");
        add("프로젝트 관리자 외의 멤버가 존재하여 삭제가 불가능합니다.", "The project cannot be deleted because it has members other than the project manager.");
        add("프로젝트 관리자 외는 삭제가 불가능합니다.", "Only the project manager can delete the project.");
        add("기본 프로젝트는 삭제가 불가능합니다.", "The default project cannot be deleted.");

        // ---------------------------------------------------------------- 물리 모델 (관계 / 인덱스 / 제약조건)
        add("relation / index / constraint 중 하나입니다.", "It must be one of relation / index / constraint.");
        add("관계 컬럼 쌍을 1개 이상 지정해주세요.", "Specify at least one pair of relation columns.");
        add("테이블 : {0}", "Table: {0}");
        add("컬럼 : {0}", "Columns: {0}");
        add("관계(FK) : {0}", "Relations (FK): {0}");
        add("인덱스 : {0}", "Indexes: {0}");
        add("제약조건 : {0}", "Constraints: {0}");
        add("FK 대상 컬럼({1})이 {0} 의 PK 전체 또는 UNIQUE 키 전체와 같지 않습니다. 대상 테이블의 키를 구성하는 컬럼을 모두 지정해주세요.",
                "The referenced columns ({1}) are not the whole primary key or a whole UNIQUE key of {0}. Specify every column of one key of the referenced table.");
        add("{0} 과(와) {1} 의 데이터 타입이 {2} 에서 FK 로 연결할 수 있는 조합이 아닙니다. ({3} ↔ {4})",
                "The data types of {0} and {1} cannot be linked by a foreign key in {2}. ({3} ↔ {4})");
        add("{0} 은(는) 참조 테이블({1})이 지정된 컬럼입니다. 컬럼 관리에서 참조 테이블을 비운 뒤 등록해주세요.",
                "{0} has a reference table ({1}). Clear the reference table in Columns and then register the relation.");
        add("같은 컬럼 구성의 인덱스", "An index with the same columns");
        add("제약조건 유형은 UNIQUE 또는 CHECK 입니다. (PK 는 컬럼의 PK 여부, FK 는 관계로 정의)",
                "The constraint type must be UNIQUE or CHECK. (Define a PK with the column's PK flag and an FK as a relation)");
        add("같은 컬럼 구성의 UNIQUE", "A UNIQUE constraint with the same columns");
        add("CHECK 조건식을 입력해주세요.", "Enter the CHECK expression.");
        add("CHECK 조건식에는 ; 와 -- 를 쓸 수 없습니다.", "The CHECK expression cannot contain ; or --.");
        add("참조 테이블명으로 만든 관계입니다. 컬럼 관리에서 참조 테이블을 비워주세요.",
                "This relation comes from a reference table name. Clear the reference table in Columns.");
        add("관계 {0} 에서 사용 중인 컬럼입니다.", "The column is used by relation {0}.");
        add("인덱스 {0} 에서 사용 중인 컬럼입니다.", "The column is used by index {0}.");
        add("제약조건 {0} 에서 사용 중인 컬럼입니다.", "The column is used by constraint {0}.");
        add("컬럼을 1개 이상 지정해주세요.", "Specify at least one column.");
        add("컬럼이 비었거나 중복되어 있습니다.", "A column is empty or duplicated.");
        add("{0} 의 컬럼이 아닌 컬럼이 있습니다.", "Some columns do not belong to {0}.");
        add("PK 와 같은 컬럼 구성입니다.", "The columns are the same as the PK.");
        add("{0} 은(는) 컬럼의 PK·고유값·인덱스 설정으로 이미 정의되어 있습니다.", "{0} is already defined by the column's PK, unique or index setting.");
        add("이름은 영문 또는 _ 로 시작하고 영문/숫자/_/$ 만 쓸 수 있습니다. ({0})",
                "The name must start with a letter or _ and contain only letters, numbers, _ and $. ({0})");
        add("알 수 없는 유형입니다. ({0})", "Unknown type. ({0})");
        add("대상을 찾을 수 없습니다.", "The target could not be found.");
        // 인덱스 관리 (M-14)
        add("인덱스의 테이블은 바꿀 수 없습니다.", "The table of an index cannot be changed.");
        add("승인 대기 중인 변경 요청이 있습니다. 먼저 승인하거나 반려해주세요.", "A change request is pending. Approve or reject it first.");
        add("바뀐 내용이 없습니다.", "Nothing has changed.");
        add("이미 처리된 변경 요청입니다.", "The change request has already been processed.");
        add("요청 뒤에 인덱스가 바뀌었습니다. 요청을 반려하고 다시 요청해주세요.", "The index changed after the request. Reject the request and request again.");
        add("컬럼의 인덱스 여부로 만든 인덱스입니다. 컬럼 관리에서 인덱스 여부를 바꿔주세요.",
                "This index comes from the column's index setting. Change the index setting in Columns.");
        add("{0} 에서 지원하지 않는 인덱스 유형입니다. ({1}, 지원 : {2})", "{0} does not support this index type. ({1}, supported: {2})");
        add("{0} 에서 {1} 인덱스는 고유(UNIQUE)로 만들 수 없습니다.", "{0} cannot create a {1} index as UNIQUE.");
        add("DDL 에 주석으로만 냈습니다.", "It is output only as a comment in the DDL.");
        // 고급 인덱스 (M-14 2차)
        add("{0} 에서는 표현식·정렬·INCLUDE·WHERE 조건 인덱스를 지원하지 않습니다. (PostgreSQL 만)",
                "{0} does not support expression, sort, INCLUDE or WHERE indexes. (PostgreSQL only)");
        add("{0} 인덱스는 정렬(ASC/DESC·NULLS)을 지원하지 않습니다. (BTREE 만)", "{0} indexes do not support ordering (ASC/DESC, NULLS). (BTREE only)");
        add("{0} 인덱스는 INCLUDE 를 지원하지 않습니다. (BTREE·GIST·SPGIST 만)", "{0} indexes do not support INCLUDE. (BTREE, GIST, SPGIST only)");
        add("{0} 인덱스는 키를 하나만 가질 수 있습니다.", "{0} indexes can have only one key.");
        add("검색 키는 컬럼 또는 표현식 중 하나만 지정합니다.", "Each key is either a column or an expression.");
        add("정렬 값이 올바르지 않습니다. ({0})", "Invalid ordering. ({0})");
        add("같은 표현식이 두 번 들어 있습니다.", "The same expression appears twice.");
        add("{0} 은(는) 검색 키와 INCLUDE 에 함께 둘 수 없습니다.", "{0} cannot be both a key and an INCLUDE column.");
        add("WHERE 조건을 입력해주세요.", "Enter the WHERE condition.");
        add("표현식을 입력해주세요.", "Enter the expression.");
        add("표현식·조건은 1000자까지 입력할 수 있습니다.", "Expressions and conditions can be up to 1000 characters.");
        add("표현식·조건에는 ; -- /* */ 를 쓸 수 없습니다.", "Expressions and conditions cannot contain ; -- /* */.");
        add("표현식·조건의 괄호나 따옴표 짝이 맞지 않습니다.", "Parentheses or quotes in the expression or condition do not match.");

        // ---------------------------------------------------------------- ERD
        add("한 번에 1~{0}개 테이블까지 조회할 수 있습니다.", "You can view 1 to {0} tables at a time.");
        add("검색어는 {0}자까지 입력할 수 있습니다.", "The keyword can be up to {0} characters long.");
        add("이웃 탐색은 1~{0}단계까지 가능합니다.", "Neighbors can be explored 1 to {0} steps deep.");
        add("배치는 테이블 {0}개까지 저장할 수 있습니다.", "A layout can store up to {0} tables.");
        add("배치 정보가 없습니다.", "There is no layout information.");
        add("좌표 또는 확대 비율이 올바르지 않습니다.", "The coordinates or zoom level are invalid.");

        // ---------------------------------------------------------------- DDL
        add("등록된 컬럼이 없습니다. 컬럼 관리에서 컬럼을 먼저 등록해주세요.", "No columns registered. Register columns in Columns first.");
        add("등록된 컬럼이 없습니다.", "No columns registered.");
        add("PK 컬럼이 없습니다.", "There is no PK column.");
        add("{0} : MySQL 8.0.16 미만은 CHECK 제약을 무시합니다.", "{0}: MySQL versions before 8.0.16 ignore CHECK constraints.");
        add("{0} : 참조 테이블 {1} 이(가) 테이블 관리에 등록되어 있지 않아 FK 를 만들지 않았습니다.",
                "{0}: no FK was created because reference table {1} is not registered in Tables.");
        add("{0} : 참조 테이블 {1} 에 PK 가 없어 FK 를 만들지 않았습니다.", "{0}: no FK was created because reference table {1} has no PK.");
        add("{0} : 참조 테이블 {1} 의 PK 가 복합키({2})라 FK 를 만들지 않았습니다.",
                "{0}: no FK was created because the PK of reference table {1} is a composite key ({2}).");
        add("{0} : 참조 테이블 {1} 의 PK 가 자기 자신이라 FK 를 만들지 않았습니다.",
                "{0}: no FK was created because the PK of reference table {1} is the column itself.");
        add("{0} : 대상 컬럼이 {1} 의 PK(또는 고유값 컬럼)가 아니라 FK 를 만들지 않았습니다.",
                "{0}: no FK was created because the target column is not the PK (or a unique column) of {1}.");
        add("{0} : 참조 PK 와 데이터 타입이 달라 FK 생성이 실패할 수 있습니다.",
                "{0}: creating the FK may fail because the data type differs from the referenced PK.");
        add("{0} : 참조 PK 와 길이/정밀도가 다릅니다. 값이 들어가지 않거나 잘리지 않도록 맞추는 것을 권장합니다.",
                "{0}: the length or precision differs from the referenced PK. Matching them is recommended so that values are not rejected or truncated.");
        add("{0} : 데이터 타입이 없어 문자열(VARCHAR)로 생성했습니다.", "{0}: generated as a string (VARCHAR) because no data type is set.");
        add("{0} : 최대 길이가 없어 {1} 로 생성했습니다.", "{0}: generated as {1} because no max length is set.");
        add("길이 없는 VARCHAR", "VARCHAR without a length");

        // ---------------------------------------------------------------- 외부 연결 보안 (SEC-1)
        add("http 또는 https 주소만 연결할 수 있습니다.", "Only http or https addresses can be connected.");
        add("같은 연결 유형·호스트·대역·포트의 규칙이 이미 있습니다.", "A rule with the same connection type, host, range and port already exists.");
        add("관리자가 허용한 규칙이 없습니다", "No rule allowed by an administrator");
        add("너무 넓은 대역입니다. IPv4 는 /8, IPv6 는 /16 보다 좁게 정해주세요.", "The range is too wide. Use a prefix of at least /8 for IPv4 and /16 for IPv6.");
        add("설명은 1000자까지 입력할 수 있습니다.", "The description can be up to 1000 characters.");
        add("숫자 주소 호스트는 허용 대역 안이어야 합니다.", "A numeric host address must be inside the allowed range.");
        add("연결 시점의 목적지가 확인한 주소와 다릅니다", "The destination at connection time differs from the verified address");
        add("연결 유형은 JDBC 또는 AI_HTTP 입니다.", "The connection type must be JDBC or AI_HTTP.");
        add("연결하지 못했습니다", "Could not connect");
        add("요청이 비어 있습니다.", "The request is empty.");
        add("운영자가 정한 범위 밖의 사설·루프백 주소입니다", "A private or loopback address outside the range set by the operator");
        add("호스트가 여러 주소로 해석되었지만 그 주소를 모두 담는 허용 규칙이 없습니다. localhost 처럼 IPv4·IPv6 로 함께 해석되는 이름이면 127.0.0.1 또는 ::1 같은 명시적인 IP 주소를 사용하세요.", "The host resolved to several addresses, but no allow rule covers all of them. For names that resolve to both IPv4 and IPv6, such as localhost, use an explicit IP address such as 127.0.0.1 or ::1.");
        add("호스트가 IPv4와 IPv6 주소로 동시에 해석되어 현재 허용 규칙과 일치하지 않습니다. localhost 대신 127.0.0.1 또는 ::1과 같은 명시적인 IP 주소를 사용하세요.", "The host resolved to both IPv4 and IPv6 addresses, so it does not match the current allow rules. Use an explicit IP address such as 127.0.0.1 or ::1 instead of localhost.");
        add("정책을 확인하지 못했습니다", "The policy could not be checked");
        add("주소에 계정 정보를 넣을 수 없습니다.", "The address cannot contain account information.");
        add("주소에 호스트가 없습니다.", "The address has no host.");
        add("클라우드 메타데이터 이름은 항상 차단됩니다", "Cloud metadata host names are always blocked");
        add("클라우드 메타데이터 이름은 허용할 수 없습니다.", "Cloud metadata host names cannot be allowed.");
        add("포트는 1~65535 사이로 입력해주세요.", "Enter a port between 1 and 65535.");
        add("항상 차단되는 대역(링크 로컬·클라우드 메타데이터·멀티캐스트·미지정 등)은 허용할 수 없습니다.", "Ranges that are always blocked (link-local, cloud metadata, multicast, unspecified and so on) cannot be allowed.");
        add("항상 차단되는 주소는 허용할 수 없습니다.", "Addresses that are always blocked cannot be allowed.");
        add("항상 차단되는 주소입니다 (링크 로컬·메타데이터·멀티캐스트·미지정 등)", "An address that is always blocked (link-local, metadata, multicast, unspecified and so on)");
        add("허용 대역 밖의 주소입니다", "The address is outside the allowed range");
        add("허용 대역(CIDR)을 입력해주세요.", "Enter the allowed range (CIDR).");
        add("허용 대역은 10.10.0.0/16 · 127.0.0.1/32 · fd12::/48 처럼 숫자 주소와 접두 길이로 입력해주세요.", "Enter the allowed range as a numeric address and prefix length, such as 10.10.0.0/16, 127.0.0.1/32 or fd12::/48.");
        add("허용하지 않은 포트입니다", "The port is not allowed");
        add("허용하지 않은 호스트입니다", "The host is not allowed");
        add("호스트 또는 포트 형식이 올바르지 않습니다", "The host or port format is not valid");
        add("호스트 이름을 풀지 못했습니다", "The host name could not be resolved");
        add("호스트는 호스트명 또는 숫자 주소로 입력해주세요. 와일드카드는 쓸 수 없습니다.", "Enter the host as a host name or a numeric address. Wildcards cannot be used.");

        // ---------------------------------------------------------------- 공지사항
        // 기본 안내 공지 : 제목·본문이 V1__seed_data.sql 의 공지와 한 글자라도 다르면 영어판이 연결되지 않는다. (V0SeedMigrationTest 에서 확인)
        add("[안내] Lexorma에 오신 것을 환영합니다.", "[Notice] Welcome to Lexorma");
        add("""
                안녕하세요. **Lexorma**를 이용해 주셔서 감사합니다.

                Lexorma는 데이터 표준화와 데이터 모델링을 지원하는 오픈소스 메타데이터 관리 플랫폼입니다. 표준 데이터 사전을 기준으로 논리·물리 모델을 설계하고, ERD로 구조와 관계를 확인하며, 변경 이력과 모델 버전으로 모델이 바뀐 과정을 추적할 수 있습니다. 운영 중인 데이터베이스의 구조를 수집해 모델과 비교하고, 필요한 변경만 골라 모델에 반영할 수도 있습니다.

                ## 주요 기능

                ### 1. 데이터 사전

                표준 단어·도메인·용어를 등록하고 승인하며, Excel 템플릿을 이용한 가져오기와 내보내기를 지원합니다. 표준 용어를 기준으로 테이블·컬럼 이름을 만들고, 사용 현황 조회와 표준화 품질 진단(용어 미연결, 이름 불일치, 도메인 정의 불일치)으로 표준 준수 여부를 확인합니다.

                ### 2. 논리 모델

                엔터티·속성·식별자·관계를 관리 화면과 논리 ERD에서 설계하고 승인합니다. 표준 사전을 기준으로 물리 이름을 추천합니다.

                ### 3. 테이블 및 컬럼

                스키마·테이블·컬럼과 관계(복합 FK)·인덱스·UNIQUE/CHECK 제약조건·VIEW를 관리합니다. 일괄 편집, 공통 컬럼 세트, Excel 가져오기를 지원하며, PostgreSQL 인덱스의 표현식·부분 조건·정렬·INCLUDE도 표현합니다.

                ### 4. 논리·물리 ERD

                논리·물리 모델을 ERD에서 조회하고 직접 편집합니다. 드래그로 관계를 연결하고, 자동 배치·전체 보기·사용자별 배치 저장을 지원합니다.

                ### 5. 논리·물리 매핑

                엔터티 ↔ 테이블, 속성 ↔ 컬럼 매핑을 관리하고 정합성을 진단합니다.

                ### 6. DDL 내보내기

                PostgreSQL(기본)·Oracle·MySQL·MariaDB용 DDL을 테이블 단위로, 또는 프로젝트·스키마 단위의 통합 스크립트(스키마 → 테이블 → 인덱스 → FK → VIEW)로 생성합니다.

                ### 7. 변경 이력과 모델 버전

                물리·논리 모델과 매핑을 누가, 언제, 무엇을 바꿨는지 변경 전후 값과 함께 기록합니다. 프로젝트 전체 모델을 이름 있는 버전으로 저장하고, 버전끼리 또는 현재 모델과 목록·ERD로 비교할 수 있습니다.

                ### 8. 원본 DB 연결과 가져오기

                운영 중인 데이터베이스(현재 PostgreSQL)에 연결해 구조를 조회 전용으로 수집하고, 수집 시점별 원본 카탈로그·변경 이력·원본 ERD를 확인합니다. 원본 스키마·테이블을 모델에 매핑하고 원본과 모델의 차이를 비교한 뒤, 고른 항목만 미승인 상태로 모델에 가져옵니다. 가져오기 직전의 모델은 모델 버전으로 자동 보존됩니다.

                ### 9. 프로젝트

                프로젝트별로 데이터 사전과 모델을 구분하고, 프로젝트 멤버·관리자, 테이블 접두사와 대상 DBMS를 관리합니다.

                ### 10. 사용자 및 권한

                역할과 프로젝트 접근 권한에 따라 이용 가능한 메뉴와 기능을 구분합니다. 신규 가입자는 관리자의 권한 부여가 필요합니다.

                ## 이용 안내

                - 최초 로그인 시 초기 비밀번호를 변경하고 접근 가능한 프로젝트를 선택해 주세요.
                - 기본 프로젝트 `LEXORMA`에서 Lexorma 자체 스키마를 예제로 담은 데이터 사전, 논리·물리 모델과 ERD를 확인해 보세요.
                - 원본 DB 정보는 모든 사용자가 조회할 수 있고, 연결·수집·매핑·가져오기는 프로젝트 관리자 또는 ADMIN만 실행할 수 있습니다. 원본 DB에는 조회만 하며 원본 구조를 바꾸지 않습니다.
                - 원본 DB 연결 비밀번호를 저장하려면 서버에 암호화 키(`LEXORMA_SECRET_KEYS`)가 설정되어 있어야 합니다.
                - 일부 기능은 사용자 역할과 프로젝트 권한에 따라 이용이 제한됩니다.
                - 데이터와 메타데이터를 변경하기 전에 해당 프로젝트의 운영 정책을 확인해 주세요.
                - 기능과 화면 구성은 업데이트 과정에서 변경될 수 있습니다.

                ## 문의 및 오류 신고

                | 용도 | 링크 |
                |------|------|
                | 프로젝트 저장소 및 이용 문서 | https://github.com/Lexorma/Lexorma |
                | 오류 신고·기능 제안·프로젝트 관련 문의 (GitHub Issues) | https://github.com/Lexorma/Lexorma/issues |

                > 오류를 신고할 때에는 사용 환경과 재현 순서, 기대한 결과와 실제 결과를 함께 알려 주세요. 비밀번호와 개인정보 등 민감한 정보는 포함하지 마세요.
                > 설치 환경의 계정·권한·운영 정책에 관한 문의는 해당 환경의 관리자에게 확인해 주세요.

                ## 안내 사항

                Lexorma는 MIT 라이선스로 공개된 프로젝트입니다. 현재 제공하는 기능과 향후 계획(원본 DB 재최신화, AI 기반 메타데이터 분석 등)은 저장소의 문서를 확인해 주세요.
                서비스 이용 중 발견한 문제점이나 개선 의견은 프로젝트 발전에 도움이 됩니다.

                감사합니다.
                **Lexorma**""", """
                Hello, and thank you for using **Lexorma**.

                Lexorma is an open-source metadata management platform for data standardization and data modeling. You design logical and physical models on top of a standard data dictionary, review structures and relationships in ERDs, and trace how the model evolved through the change history and model versions. You can also collect the structure of a live database, compare it with the model and apply only the changes you choose.

                ## Key features

                ### 1. Data dictionary

                Register and approve standard words, domains and terms, and import or export them with Excel templates. Table and column names are built from standard terms, and usage lookup and standardization quality checks (columns without a term, mismatched names, domain definition mismatches) show how well the model follows the standard.

                ### 2. Logical model

                Design and approve entities, attributes, identifiers and relationships in the management screens and the logical ERD. Physical names are recommended from the standard dictionary.

                ### 3. Tables and columns

                Manage schemas, tables and columns together with relations (composite foreign keys), indexes, UNIQUE/CHECK constraints and views. Bulk editing, common column sets and Excel import are supported, and PostgreSQL index expressions, partial conditions, sort order and INCLUDE columns can be modeled.

                ### 4. Logical and physical ERD

                View and edit the logical and physical models directly in ERDs. Connect relationships by dragging, and use auto layout, fit-to-view and per-user layouts.

                ### 5. Logical ↔ physical mapping

                Manage entity ↔ table and attribute ↔ column mappings and check their consistency.

                ### 6. DDL export

                Generate DDL for PostgreSQL (default), Oracle, MySQL and MariaDB, per table or as one combined script for a project or schema (schemas → tables → indexes → foreign keys → views).

                ### 7. Change history and model versions

                Every change to the physical and logical models and the mappings is recorded with who made it, when, and the values before and after. Save the whole project model as a named version, and compare versions with each other or with the current model, as a list or as ERDs.

                ### 8. Source database connections and import

                Connect to a live database (PostgreSQL for now), collect its structure read-only, and review the source catalog, its change history and the source ERD for each collection. Map source schemas and tables to the model, compare the source with the model, and import only the items you choose, as unapproved objects. The model just before each import is kept automatically as a model version.

                ### 9. Projects

                Keep the data dictionary and models separate per project, and manage project members, managers, the table prefix and the target DBMS.

                ### 10. Users and permissions

                Available menus and features depend on your role and project access. New users need an administrator to grant them a role.

                ## Getting started

                - On your first login, change the initial password and choose a project you can access.
                - Explore the default project `LEXORMA`, whose example data dictionary, logical and physical models and ERDs describe Lexorma's own schema.
                - Every user can view source database information; connecting, collecting, mapping and importing are limited to the project manager or ADMIN. Lexorma only reads from source databases and never changes their structure.
                - Storing a source database password requires an encryption key (`LEXORMA_SECRET_KEYS`) configured on the server.
                - Some features are restricted by your role and project permissions.
                - Check the operating policy of your project before changing data or metadata.
                - Features and screens may change as Lexorma is updated.

                ## Questions and bug reports

                | Purpose | Link |
                |------|------|
                | Project repository and documentation | https://github.com/Lexorma/Lexorma |
                | Bug reports, feature requests and project questions (GitHub Issues) | https://github.com/Lexorma/Lexorma/issues |

                > When reporting a bug, include your environment, the steps to reproduce it, and the expected and actual results. Do not include passwords, personal data or other sensitive information.
                > For questions about accounts, permissions or policies of your installation, contact its administrator.

                ## About

                Lexorma is released under the MIT License. See the documentation in the repository for the current features and future plans (source database refresh, AI-assisted metadata analysis and more).
                Any problems or suggestions you share while using the service help the project grow.

                Thank you.
                **Lexorma**""");
        // 2026-11-14 이전 V1 로 만든 DB 의 안내 공지 본문 (V1 은 다시 실행되지 않아 그 DB 에는 이전 본문이 남는다)
        add("""
                안녕하세요. **Lexorma**를 이용해 주셔서 감사합니다.

                Lexorma는 데이터베이스 모델링과 데이터 표준화, 메타데이터 관리를 지원하는 오픈소스 데이터 관리 플랫폼입니다. 데이터 사전과 테이블·컬럼 정보를 관리하고, 물리 모델의 구조와 관계를 시각적으로 확인할 수 있습니다.

                ## 주요 기능

                ### 1. 데이터 사전

                표준 단어·도메인·용어를 등록하고 승인하며, 표준 단어와 약어를 기준으로 일관된 이름을 관리합니다. Excel 템플릿을 이용한 가져오기와 데이터 사전 내보내기를 지원합니다.

                ### 2. 테이블 및 컬럼

                테이블·컬럼의 구조와 속성, 스키마, 관계·인덱스·제약을 관리합니다. 표준 용어 기반 컬럼 등록, 일괄 편집과 공통 컬럼 세트를 지원하며, 프로젝트의 대상 DBMS에 맞는 DDL을 생성할 수 있습니다.

                ### 3. 물리 ERD

                등록한 테이블과 컬럼, 테이블 간 관계를 시각적으로 조회합니다. 스키마·검색 조건으로 조회 범위를 좁히고 자동 배치·전체 보기·사용자별 배치 저장 기능을 이용할 수 있습니다.

                ### 4. 프로젝트

                프로젝트별로 데이터 사전과 설계 정보를 구분하고, 프로젝트 멤버·관리자, 테이블 접두사와 대상 DBMS를 관리합니다.

                ### 5. 사용자 및 권한

                역할과 프로젝트 접근 권한에 따라 이용 가능한 메뉴와 기능을 구분합니다. 신규 가입자는 관리자의 권한 부여가 필요합니다.

                ## 이용 안내

                - 최초 로그인 시 초기 비밀번호를 변경하고 접근 가능한 프로젝트를 선택해 주세요.
                - 기본 프로젝트 `LEXORMA`의 예제 데이터 사전과 테이블·컬럼, 물리 ERD를 확인해 보세요.
                - 일부 기능은 사용자 역할과 프로젝트 권한에 따라 이용이 제한됩니다.
                - 데이터와 메타데이터를 변경하기 전에 해당 프로젝트의 운영 정책을 확인해 주세요.
                - 기능과 화면 구성은 업데이트 과정에서 변경될 수 있습니다.

                ## 문의 및 오류 신고

                | 용도 | 링크 |
                |------|------|
                | 프로젝트 저장소 및 이용 문서 | https://github.com/Lexorma/Lexorma |
                | 오류 신고·기능 제안·프로젝트 관련 문의 (GitHub Issues) | https://github.com/Lexorma/Lexorma/issues |

                > 오류를 신고할 때에는 사용 환경과 재현 순서, 기대한 결과와 실제 결과를 함께 알려 주세요. 비밀번호와 개인정보 등 민감한 정보는 포함하지 마세요.
                > 설치 환경의 계정·권한·운영 정책에 관한 문의는 해당 환경의 관리자에게 확인해 주세요.

                ## 안내 사항

                Lexorma는 MIT 라이선스로 공개된 프로젝트입니다. 현재 제공하는 기능과 향후 계획은 저장소의 문서를 확인해 주세요.
                서비스 이용 중 발견한 문제점이나 개선 의견은 프로젝트 발전에 도움이 됩니다.

                감사합니다.
                **Lexorma**""", """
                Hello, and thank you for using **Lexorma**.

                Lexorma is an open-source data management platform for database modeling, data standardization and metadata management. You can manage a data dictionary together with table and column information, and view the structure and relationships of your physical model visually.

                ## Key features

                ### 1. Data dictionary

                Register and approve standard words, domains and terms, and keep names consistent based on standard words and their abbreviations. You can import from an Excel template and export the data dictionary.

                ### 2. Tables and columns

                Manage the structure and attributes of tables and columns, along with schemas, relations, indexes and constraints. Columns can be registered from standard terms, edited in bulk and added from a common column set, and DDL is generated for the target DBMS of the project.

                ### 3. Physical ERD

                View registered tables, columns and the relations between tables visually. Narrow the view by schema or search, and use auto layout, fit to view and per-user layout saving.

                ### 4. Projects

                Keep the data dictionary and design information separate for each project, and manage project members and managers, the table prefix and the target DBMS.

                ### 5. Users and permissions

                Available menus and features depend on your role and project access. New users need permission from an administrator.

                ## Getting started

                - On your first login, change the initial password and select a project you can access.
                - Take a look at the sample data dictionary, tables, columns and physical ERD in the default project `LEXORMA`.
                - Some features are limited depending on your role and project permissions.
                - Check the operating policy of the project before changing data or metadata.
                - Features and screens may change with updates.

                ## Questions and bug reports

                | Purpose | Link |
                |---------|------|
                | Project repository and documentation | https://github.com/Lexorma/Lexorma |
                | Bug reports, feature requests and questions (GitHub Issues) | https://github.com/Lexorma/Lexorma/issues |

                > When reporting a bug, please include your environment, the steps to reproduce it, and the expected and actual results. Do not include sensitive information such as passwords or personal data.
                > For questions about accounts, permissions or operating policies of your installation, contact the administrator of that environment.

                ## About

                Lexorma is released under the MIT License. See the documentation in the repository for the current features and future plans.
                Any problems or suggestions you share while using the service help the project grow.

                Thank you.
                **Lexorma**""");

        // ---------------------------------------------------------------- 논리 모델
        add("{0} 에 엔터티가 있습니다.", "{0} contains entities.");
        add("관계 {0} 에서 사용 중인 엔터티입니다.", "The entity is used by relationship {0}.");
        add("식별자 {0} 에서 사용 중인 속성입니다.", "The attribute is used by identifier {0}.");
        add("논리 속성에서 사용 중인 표준 용어입니다 : {0}", "The standard term is used by a logical attribute: {0}");
        add("엔터티의 속성 전체를 한 번씩 지정해주세요.", "Specify every attribute of the entity exactly once.");
        add("주 식별자는 엔터티에 하나만 둘 수 있습니다. (현재 주 식별자 : {0})", "An entity can have only one primary identifier. (Current primary identifier: {0})");
        add("식별자 속성을 1개 이상 지정해주세요.", "Specify at least one identifier attribute.");
        add("같은 속성을 두 번 지정할 수 없습니다.", "The same attribute cannot be specified twice.");
        add("식별자에는 같은 엔터티의 속성만 넣을 수 있습니다.", "An identifier can only contain attributes of its own entity.");
        add("관계의 엔터티는 같은 논리 모델에 있어야 합니다.", "Both entities of a relationship must belong to the same logical model.");
        add("{0}을(를) 입력해주세요.", "Enter the {0}.");
        add("{0}은(는) {1}자까지 입력할 수 있습니다.", "The {0} can be up to {1} characters long.");
        add("최소 참여 수는 0 이상이어야 합니다.", "The minimum participation must be 0 or greater.");
        add("최대 참여 수는 1 이상이고 최소 참여 수보다 작을 수 없습니다.", "The maximum participation must be at least 1 and not less than the minimum.");
        add("{0}을(를) 찾을 수 없습니다.", "The {0} could not be found.");
        add("모델명", "model name");
        add("엔터티명", "entity name");
        add("속성명", "attribute name");
        add("식별자명", "identifier name");
        add("관계명", "relationship name");
        add("데이터 유형", "data type");
        add("설명", "description");
        add("버전명", "version name");
        add("비교할 버전을 서로 다르게 선택해주세요.", "Select two different versions to compare.");
        add("ERD 로 비교할 모델 종류가 올바르지 않습니다.", "The model kind for the ERD comparison is not valid.");
        add("연결명", "connection name");
        add("같은 이름의 연결이 이미 있습니다.", "A connection with the same name already exists.");
        add("비밀번호", "password");
        add("호스트", "host");
        add("서비스명 또는 SID", "service name or SID");
        add("데이터베이스명", "database name");
        add("DB 사용자명", "database user name");
        add("호스트의 형식이 올바르지 않습니다. 호스트명 또는 IP 주소만 입력해주세요.", "The host is not valid. Enter a host name or an IP address only.");
        add("이 호스트로는 연결을 등록할 수 없습니다. 운영자가 허용한 호스트만 사용할 수 있습니다.", "A connection to this host cannot be registered. Only hosts allowed by the operator can be used.");
        add("포트는 1 ~ 65535 사이여야 합니다.", "The port must be between 1 and 65535.");
        add("데이터베이스명의 형식이 올바르지 않습니다.", "The database name is not valid.");
        add("데이터베이스명 종류가 올바르지 않습니다.", "The database name type is not valid.");
        add("이 DB 유형에서 쓸 수 없는 데이터베이스명 종류입니다.", "This database name type cannot be used with this database type.");
        add("DB 사용자명의 형식이 올바르지 않습니다.", "The database user name is not valid.");
        add("허용하지 않는 연결 옵션입니다. 사용할 수 있는 옵션 : {0}", "This connection option is not allowed. Available options: {0}");
        add("사용 중지된 연결입니다.", "The connection is disabled.");
        // AI-2b : 원본 DB 수집·연결 테스트
        add("인덱스 구성 요소는 컬럼과 표현식 중 하나여야 합니다.", "Each index member must be either a column or an expression.");
        add("인덱스 정렬 방식이 올바르지 않습니다.", "The index sort option is invalid.");
        add("이 DB 유형은 아직 수집할 수 없습니다 : {0}", "Collection is not supported for this database type yet: {0}");
        add("접속 대상이나 계정을 바꾸면 비밀번호를 다시 입력해야 합니다.", "Enter the password again when you change the connection target or account.");
        add("끝난 세션입니다.", "The session has already ended.");
        add("이 호스트로는 접속할 수 없습니다. 운영자가 허용한 호스트만 사용할 수 있습니다.", "Connecting to this host is not allowed. Only hosts allowed by the operator can be used.");
        add("실행할 수 없는 도구입니다 : {0}", "The tool cannot be run: {0}");
        add("도구의 입력이 올바르지 않습니다 : {0}", "The tool input is not valid: {0}");
        add("원본 카탈로그가 있는 연결은 DB 유형과 식별자 비교 규칙을 바꿀 수 없습니다.", "The database type and identifier comparison rule cannot be changed while the connection has a source catalog.");
        add("대상 DB 유형이 올바르지 않습니다.", "The target database type is not valid.");
        add("식별자 비교 규칙이 올바르지 않습니다.", "The identifier comparison rule is not valid.");
        add("이 DB 유형에서는 식별자 비교 규칙을 선택할 수 없습니다.", "The identifier comparison rule cannot be chosen for this database type.");
        add("카탈로그와 스키마 중 하나는 있어야 합니다.", "Either a catalog or a schema is required.");
        add("컬럼 순서가 올바르지 않습니다.", "The column position is not valid.");
        add("제약조건 유형이 없습니다.", "The constraint type is missing.");
        add("구성 컬럼이 없습니다.", "No columns are specified.");
        add("이름이 없거나 너무 깁니다.", "The name is missing or too long.");
        add("구성 컬럼을 찾을 수 없습니다 : {0}", "Column not found: {0}");
        // 원본 카탈로그 수집 실행 (AI-1c)
        add("값이 너무 깁니다.", "The value is too long.");
        add("정밀도가 올바르지 않습니다.", "The precision is invalid.");
        add("같은 스키마가 두 번 있습니다 : {0}", "The same schema appears twice: {0}");
        add("같은 이름의 컬럼이 두 번 있습니다 : {0}", "A column with the same name appears twice: {0}");
        add("같은 이름의 테이블이 두 번 있습니다 : {0}", "A table with the same name appears twice: {0}");
        add("컬럼 순서가 겹칩니다 : {0}", "Column positions overlap: {0}");
        add("부분 수집할 스키마가 없습니다.", "No schemas are specified for the partial collection.");
        add("전체 수집에는 스키마를 지정하지 않습니다.", "Schemas are not specified for a full collection.");
        add("수집 범위가 없습니다.", "The collection scope is not specified.");
        add("수집 결과가 없습니다.", "There is no collection result.");
        add("수집 결과가 올바르지 않습니다.", "The collection result is invalid.");
        add("스키마의 수집 결과가 없습니다 : {0}", "The schema has no collection result: {0}");
        add("요청하지 않은 스키마가 결과에 있습니다 : {0}", "The result contains a schema that was not requested: {0}");
        add("요청한 스키마가 원본에 없습니다.", "A requested schema does not exist in the source database.");
        add("전체 수집 결과에 스키마가 없습니다.", "The full collection result has no schemas.");
        add("완전하게 수집하지 못한 스키마가 있습니다.", "Some schemas were not collected completely.");
        add("수집을 시작한 뒤 연결의 DB 유형이나 식별자 비교 규칙이 바뀌었습니다.", "The database type or identifier comparison rule of the connection changed after the collection started.");
        add("한 번에 반영할 수 있는 테이블 수({0})를 넘었습니다.", "The number of tables exceeds the limit for one collection ({0}).");
        add("한 번에 반영할 수 있는 컬럼 수({0})를 넘었습니다.", "The number of columns exceeds the limit for one collection ({0}).");
        add("삭제·수정 규칙이 올바르지 않습니다.", "The delete or update rule is not valid.");
        add("논리 모델", "logical model");
        add("엔터티", "entity");
        add("속성", "attribute");
        add("식별자", "identifier");
        add("관계", "relationship");

        // ---------------------------------------------------------------- 논리·물리 매핑
        add("엔터티 {0} 은(는) 이미 테이블 {1} 에 매핑되어 있습니다.", "Entity {0} is already mapped to table {1}.");
        add("테이블 {0} 은(는) 이미 엔터티 {1} 에 매핑되어 있습니다.", "Table {0} is already mapped to entity {1}.");
        add("속성 {0} 은(는) 이미 컬럼 {1} 에 매핑되어 있습니다.", "Attribute {0} is already mapped to column {1}.");
        add("컬럼 {0} 은(는) 이미 속성 {1} 에 매핑되어 있습니다.", "Column {0} is already mapped to attribute {1}.");
        add("속성 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 속성 매핑을 먼저 해제해주세요.", "The table of an entity mapping that has attribute mappings cannot be changed. Remove the attribute mappings first.");
        add("속성 매핑이 있는 엔터티 매핑입니다. 속성 매핑을 먼저 해제해주세요.", "The entity mapping has attribute mappings. Remove the attribute mappings first.");
        add("매핑된 엔터티 {0} 의 속성만 연결할 수 있습니다.", "Only attributes of the mapped entity {0} can be mapped.");
        add("매핑된 테이블 {0} 의 컬럼만 연결할 수 있습니다.", "Only columns of the mapped table {0} can be mapped.");
        add("테이블 {0} 에 매핑된 엔터티입니다. 매핑을 먼저 해제해주세요.", "The entity is mapped to table {0}. Remove the mapping first.");
        add("컬럼 {0} 에 매핑된 속성입니다. 매핑을 먼저 해제해주세요.", "The attribute is mapped to column {0}. Remove the mapping first.");
        add("엔터티 {0} 에 매핑된 테이블입니다. 매핑을 먼저 해제해주세요.", "The table is mapped to entity {0}. Remove the mapping first.");
        add("속성 {0} 에 매핑된 컬럼입니다. 매핑을 먼저 해제해주세요.", "The column is mapped to attribute {0}. Remove the mapping first.");
        add("엔터티와 테이블 중 하나만 지정해주세요.", "Specify either an entity or a table, not both.");

        // ---------------------------------------------------------------- 논리·물리 정합성 비교
        add("엔터티가 물리 테이블에 매핑되지 않았습니다.", "The entity is not mapped to a physical table.");
        add("엔터티가 매핑되지 않아 속성도 매핑되지 않았습니다.", "The attribute is not mapped because its entity is not mapped.");
        add("속성이 물리 컬럼에 매핑되지 않았습니다.", "The attribute is not mapped to a physical column.");
        add("양쪽 모두 표준 용어가 연결되지 않았습니다.", "Neither side is linked to a standard term.");
        add("물리 컬럼에만 표준 용어가 연결되어 있습니다.", "Only the physical column is linked to a standard term.");
        add("논리 속성에만 표준 용어가 연결되어 있습니다.", "Only the logical attribute is linked to a standard term.");
        add("논리 속성과 물리 컬럼의 표준 용어가 다릅니다.", "The logical attribute and the physical column are linked to different standard terms.");
        add("논리 속성에 표준 용어가 없어 기대 컬럼명을 알 수 없습니다.", "The expected column name is unknown because the logical attribute has no standard term.");
        add("논리 속성의 표준 용어에 영문약어가 없어 기대 컬럼명을 알 수 없습니다.", "The expected column name is unknown because the standard term of the logical attribute has no abbreviation.");
        add("컬럼명이 논리 속성의 표준 용어 영문약어와 다릅니다.", "The column name differs from the abbreviation of the logical attribute's standard term.");
        add("논리 속성에 표준 용어가 없어 표준 도메인을 알 수 없습니다.", "The standard domain is unknown because the logical attribute has no standard term.");
        add("논리 속성의 표준 용어에 표준 도메인이 없습니다.", "The standard term of the logical attribute has no standard domain.");
        add("물리 컬럼에 데이터 타입이 없습니다.", "The physical column has no data type.");
        add("표준 도메인에 데이터 타입이 정의되지 않았습니다.", "The standard domain does not define a data type.");
        add("표준 도메인의 데이터 타입과 컬럼의 데이터 타입이 다릅니다.", "The data type of the column differs from that of the standard domain.");
        add("데이터 타입이 달라 길이·정밀도를 비교하지 않습니다.", "Length and precision are not compared because the data types differ.");
        add("표준 도메인에 길이·정밀도가 정의되지 않았습니다.", "The standard domain does not define a length or precision.");
        add("필수 속성인데 컬럼이 NULL 을 허용합니다.", "The attribute is required but the column allows NULL.");
        add("선택 속성인데 컬럼이 NOT NULL 입니다.", "The attribute is optional but the column is NOT NULL.");
        add("엔터티에 주 식별자가 없습니다.", "The entity has no primary identifier.");
        add("주 식별자 속성인데 컬럼이 PK 가 아닙니다.", "The attribute belongs to the primary identifier but the column is not part of the PK.");
        add("PK 컬럼인데 속성이 주 식별자가 아닙니다.", "The column is part of the PK but the attribute does not belong to the primary identifier.");

        // ---------------------------------------------------------------- 물리명 추천
        add("현재 컬럼명이 표준 용어의 영문약어와 같습니다.", "The current column name equals the abbreviation of the standard term.");
        add("같은 테이블에서 속성 {0} 에 매핑된 컬럼이 추천 컬럼명을 이미 쓰고 있습니다.", "The recommended column name is already used in the same table by the column mapped to attribute {0}.");
        add("같은 테이블에 추천 컬럼명과 이름이 같은 컬럼이 이미 있습니다. 그 컬럼은 논리 속성에 매핑되지 않았습니다.", "The same table already has a column with the recommended name. That column is not mapped to a logical attribute.");
        add("표준 용어의 영문약어 기준입니다. 현재 컬럼명과 다릅니다.", "Based on the abbreviation of the standard term. It differs from the current column name.");
        add("표준 용어의 영문약어 기준입니다. 속성이 물리 컬럼에 매핑되지 않았습니다.", "Based on the abbreviation of the standard term. The attribute is not mapped to a physical column.");
        add("표준 용어의 영문약어 기준입니다. 엔터티가 물리 테이블에 매핑되지 않았습니다.", "Based on the abbreviation of the standard term. The entity is not mapped to a physical table.");
        add("엔터티명에서 단어를 찾을 수 없습니다.", "No words were found in the entity name.");
        add("엔터티명의 단어가 표준 단어로 등록되지 않았습니다 : {0}. 단어를 공백으로 나눠 적었는지 확인해주세요.", "Words of the entity name are not registered as standard words: {0}. Check that the words are separated by spaces.");
        add("엔터티명의 단어가 승인되지 않은 표준 단어입니다 : {0}", "Words of the entity name are standard words that are not approved: {0}");
        add("현재 테이블명이 표준 단어로 만든 테이블명과 같습니다.", "The current table name equals the name built from the standard words.");
        add("엔터티 {0} 에 매핑된 테이블이 추천 테이블명을 이미 쓰고 있습니다.", "The recommended table name is already used by the table mapped to entity {0}.");
        add("추천 테이블명과 이름이 같은 테이블이 이미 있습니다. 그 테이블은 논리 엔터티에 매핑되지 않았습니다.", "A table with the recommended name already exists. That table is not mapped to a logical entity.");
        add("표준 단어의 영문약어 기준입니다. 현재 테이블명과 다릅니다.", "Based on the abbreviations of the standard words. It differs from the current table name.");
        add("표준 단어의 영문약어 기준입니다. 엔터티가 물리 테이블에 매핑되지 않았습니다.", "Based on the abbreviations of the standard words. The entity is not mapped to a physical table.");
        add("필수", "Required");
        add("선택", "Optional");
        add("주 식별자", "Primary identifier");

        // VIEW 관리 (M-15)
        add("CREATE VIEW 문 전체가 아니라 AS 뒤의 조회 문(SELECT / WITH …)만 입력해주세요.", "Enter only the query after AS (SELECT / WITH …), not the whole CREATE VIEW statement.");
        add("VIEW {0} 이(가) 이 VIEW 를 참조합니다. 그 VIEW 의 참조 객체에서 먼저 빼주세요.", "VIEW {0} references this view. Remove it from that view's referenced objects first.");
        add("VIEW {0} 이(가) 참조하는 테이블입니다. VIEW 의 참조 객체에서 먼저 빼주세요.", "The table is referenced by VIEW {0}. Remove it from the view's referenced objects first.");
        add("VIEW 는 자기 자신을 참조할 수 없습니다.", "A view cannot reference itself.");
        add("VIEW 정의가 없습니다.", "The view definition is missing.");
        add("{0} : {1} 의 VIEW 에는 COMMENT 를 붙일 수 없어 논리명을 넣지 않았습니다.", "{0}: {1} views do not support COMMENT, so the logical name was not added.");
        add("{0} : 같은 이름의 테이블이 있습니다. 테이블과 VIEW 는 같은 이름을 쓸 수 없습니다.", "{0}: a table with the same name exists. A table and a view cannot share a name.");
        add("{0} : 정의 SQL 이 {1} 기준 검사를 통과하지 않아 원문 그대로 넣었습니다.", "{0}: the definition SQL did not pass the {1} check, so it was added as written.");
        add("{0} {1} 은(는) SQL 예약어라 쓸 수 없습니다.", "{0} {1} is an SQL reserved word and cannot be used.");
        add("{0} {1} 은(는) 영문자·숫자·_·$ 로 된 63자 이내의 이름이어야 하고 영문자나 _ 로 시작해야 합니다.", "{0} {1} must be up to 63 letters, digits, _ or $ and start with a letter or _.");
        add("{0} {1} 의 형식이 올바르지 않습니다. (공백·따옴표·마침표 없이 255자 이내)", "{0} {1} is not valid. (Up to 255 characters without spaces, quotes or periods)");
        add("{0} 에 VIEW 가 있습니다.", "{0} has views.");
        add("{0} 은(는) 모델에 있는 객체입니다. 목록에서 골라 내부 참조로 연결해주세요.", "{0} exists in the model. Choose it from the list to link it as an internal reference.");
        add("같은 참조 객체가 두 번 들어 있습니다 : {0}", "The same referenced object appears twice: {0}");
        add("모델에 없는(미확인) 참조 객체가 있어 생성 순서를 보장하지 않습니다 : {0}", "Some referenced objects are not in the model (unresolved), so the creation order is not guaranteed: {0}");
        add("순환 참조가 됩니다 : {0}", "This creates a circular reference: {0}");
        add("순환 참조가 있어 생성 순서를 정할 수 없는 VIEW 가 있습니다 : {0}", "The creation order of some views cannot be determined because of circular references: {0}");
        add("승인 대기 중인 변경 요청은 DDL 에 반영하지 않았습니다 (확정된 정의 기준).", "Pending change requests are not reflected in the DDL (based on the confirmed definition).");
        add("요청 뒤에 VIEW 가 바뀌었습니다. 요청을 반려하고 다시 요청해주세요.", "The view changed after the request. Reject the request and request again.");
        add("이 스크립트 밖의 VIEW 를 참조합니다 (먼저 만들어야 합니다) : {0}", "References views outside this script (create them first): {0}");
        add("정의 SQL 에 조회가 아닌 명령({0})이 들어 있습니다. 조회 문만 입력해주세요.", "The definition SQL contains a non-query command ({0}). Enter a query only.");
        add("정의 SQL 에는 문장 하나만 입력할 수 있습니다. (; 뒤에 다른 문장이 있습니다)", "The definition SQL can contain only one statement. (There is another statement after ;)");
        add("정의 SQL 은 SELECT 또는 WITH 로 시작하는 조회 문이어야 합니다.", "The definition SQL must be a query starting with SELECT or WITH.");
        add("정의 SQL 은 {0}자까지 입력할 수 있습니다.", "The definition SQL can be up to {0} characters.");
        add("정의 SQL 을 입력해주세요.", "Enter the definition SQL.");
        add("정의 SQL 의 괄호 짝이 맞지 않습니다.", "The parentheses in the definition SQL do not match.");
        add("정의 SQL 의 따옴표나 주석(/* */)이 닫히지 않았습니다.", "A quote or comment (/* */) in the definition SQL is not closed.");
        add("참조 객체 유형은 TABLE / VIEW 중 하나입니다.", "The referenced object type must be TABLE or VIEW.");
        add("참조 객체 유형이 대상과 맞지 않습니다.", "The referenced object type does not match the target.");
        add("참조 객체 정보가 없습니다.", "The referenced object is missing.");
        add("참조 객체는 {0}개까지 등록할 수 있습니다.", "Up to {0} referenced objects can be registered.");
        add("참조 객체는 테이블과 VIEW 중 하나만 지정합니다.", "A referenced object is either a table or a view.");
        add("참조 객체를 고르거나, 모델에 없는 객체면 이름을 입력해주세요.", "Choose a referenced object, or enter its name if it is not in the model.");
        add("참조할 VIEW 를 찾을 수 없습니다. (#{0})", "The view to reference was not found. (#{0})");
        add("참조할 테이블을 찾을 수 없습니다. (#{0})", "The table to reference was not found. (#{0})");
        add("출력 컬럼 {0}", "Output column {0}");
        add("출력 컬럼 정보가 없습니다.", "The output column is missing.");
        add("출력 컬럼은 {0}개까지 등록할 수 있습니다.", "Up to {0} output columns can be registered.");
        add("VIEW : {0}", "View: {0}");
        add("VIEW명", "View name");
        add("출력 컬럼명", "Output column name");
        add("논리명", "Logical name");
        add("컬럼 논리명", "Column logical name");
        add("참조 객체명", "Referenced object name");
        add("참조 스키마명", "Referenced schema name");
        add("{0} : 요청 뒤에 같은 이름의 테이블·VIEW 가 등록되어 승인할 수 없습니다. 요청을 반려하고 다른 이름으로 다시 요청해주세요.", "{0}: a table or view with this name was registered after the request, so it cannot be approved. Reject the request and request again with another name.");

        // 통합 DDL Export (M-11)
        add("이 스크립트 밖의 테이블을 참조합니다 (대상 DB 에 먼저 있어야 합니다) : {0}", "References tables outside this script (they must already exist in the target DB): {0}");
        add("{0} : Oracle 의 스키마는 사용자(계정)라 이 스크립트에서 만들지 않습니다.", "{0}: in Oracle a schema is a user (account), so this script does not create it.");
        add("Oracle 은 스키마가 사용자(계정)라 CREATE SCHEMA 를 넣지 않았습니다. 대상 스키마 사용자를 먼저 만들어야 합니다.", "In Oracle a schema is a user (account), so CREATE SCHEMA was not added. Create the schema users first.");
        add("{0} : 컬럼이 없어 CREATE TABLE 을 만들 수 없습니다.", "{0}: CREATE TABLE cannot be generated because the table has no columns.");
        add("이 스크립트 밖의 테이블을 참조하는 FK 가 있습니다 (대상 DB 에 먼저 있어야 합니다) : {0}", "Some foreign keys reference tables outside this script (they must already exist in the target DB): {0}");
        add("VIEW 생성 순서는 등록된 참조 정보 기준입니다. 정의 SQL 이 실제로 참조하는 객체와 다르면 실행이 실패할 수 있습니다.", "The view creation order is based on the registered references. If the definition SQL references other objects, execution may fail.");
        add("승인 대기 중인 VIEW 변경 요청 {0}건은 반영하지 않았습니다 (확정된 정의 기준).", "{0} pending view change request(s) are not reflected (based on the confirmed definition).");
        add("승인 대기 중인 인덱스 변경 요청 {0}건은 반영하지 않았습니다 (확정된 정의 기준).", "{0} pending index change request(s) are not reflected (based on the confirmed definition).");
        add("내보낼 테이블·VIEW 가 없습니다.", "There are no tables or views to export.");
        add("{0} 에서는 통합 DDL 의 실제 실행을 검증하지 않았습니다.", "Execution of the integrated DDL has not been verified on {0}.");
        add("승인 기준 Export 는 범위 안에 승인 대기 항목이 없어야 합니다. 승인을 마치거나 승인 기준을 끄고 설계용 DDL 로 받아주세요.", "An approved-only export requires no pending items in scope. Finish the approvals, or turn off approved-only and get the design DDL.");
        add("승인 대기 항목 포함", "includes pending items");
        add("같은 스키마 안에서 이름이 겹치는 객체가 있어 실행이 실패할 수 있습니다 (길이 제한으로 자른 이름 포함) : {0}", "Some objects in the same schema have the same name, so execution may fail (including names truncated to the length limit): {0}");
        add("지원하지 않는 DBMS 입니다 : {0}", "Unsupported DBMS: {0}");

        // 스키마 단위 TABLE·VIEW 이름 (2026-11-13)
        add("{0} : 다른 사용자가 이 테이블을 수정했습니다. 다시 열어 수정해주세요.", "{0}: another user changed this table. Open it again and edit.");
        add("{0} : 여러 스키마에 같은 이름의 테이블이 있습니다. 스키마명.테이블명으로 적어주세요.", "{0}: tables with this name exist in several schemas. Write it as schema.table.");
        add("참조 테이블 {0} 이(가) 여러 스키마에 있습니다. 스키마명.테이블명으로 적어주세요.", "The referenced table {0} exists in several schemas. Write it as schema.table.");
        add("{0} : 참조 테이블 {1} 이(가) 여러 스키마에 있어 FK 를 만들지 않았습니다. 스키마명.테이블명으로 지정해주세요.", "{0}: the referenced table {1} exists in several schemas, so no FK was created. Specify it as schema.table.");
        add("스키마명 포함 {0} 이(가) 스키마 {1} 와(과) 같아 같은 이름의 객체가 겹칩니다 : {2}. 다른 스키마명을 쓰거나 비워주세요.", "The schema name to include, {0}, is the same as schema {1}, so objects with the same name collide: {2}. Use another schema name or leave it empty.");
        add("스키마명 포함 {0} 이(가) 스키마 {1} 와(과) 같아 기본 스키마의 객체가 그 스키마에 함께 만들어집니다.", "The schema name to include, {0}, is the same as schema {1}, so objects of the default schema are created in that schema too.");
        // 원본 매핑 (M-4c, 2026-11-13)
        add("VIEW 는 매핑할 수 없습니다. TABLE 만 매핑합니다.", "A VIEW cannot be mapped. Only TABLEs are mapped.");
        add("가져오기 기준 연결을 먼저 정해주세요.", "Choose the import base connection first.");
        add("기준 연결에 완료된 수집이 없어 매핑을 확정할 수 없습니다. 먼저 메타데이터를 수집해주세요.", "The base connection has no completed collection, so mappings cannot be confirmed. Collect its metadata first.");
        add("다른 사용자가 같은 매핑을 바꾸었습니다. 화면을 새로 고친 뒤 다시 시도해주세요.", "Another user changed the same mapping. Refresh the page and try again.");
        add("마지막으로 반영된 수집에 없는 원본 스키마입니다.", "The source schema is not in the last applied collection.");
        add("마지막으로 반영된 수집에 없는 원본 테이블입니다.", "The source table is not in the last applied collection.");
        add("매핑되지 않은 원본 스키마입니다.", "The source schema is not mapped.");
        add("매핑을 찾을 수 없습니다.", "The mapping was not found.");
        add("모델 스키마 {0} 은(는) 이미 원본 스키마 {1} 에 매핑되어 있습니다.", "Model schema {0} is already mapped to source schema {1}.");
        add("모델 스키마를 찾을 수 없습니다.", "The model schema was not found.");
        add("모델 테이블 {0} 은(는) 원본 스키마 {1} 에 매핑된 모델 스키마에 있지 않습니다.", "Model table {0} is not in the model schema mapped to source schema {1}.");
        add("모델 테이블 {0} 은(는) 이미 원본 테이블 {1} 에 매핑되어 있습니다.", "Model table {0} is already mapped to source table {1}.");
        add("모델 테이블을 찾을 수 없습니다.", "The model table was not found.");
        add("원본 스키마 {0} 의 스키마 매핑을 먼저 확정해주세요.", "Confirm the schema mapping of source schema {0} first.");
        add("원본 스키마와 모델 스키마를 골라주세요.", "Choose a source schema and a model schema.");
        add("원본 테이블과 모델 테이블을 골라주세요.", "Choose a source table and a model table.");
        add("지금 기준 연결의 매핑(스키마 {0}건, 테이블 {1}건)은 지우지 않고 그 연결의 매핑으로 남습니다. 확인 후 다시 요청해주세요.", "The mappings of the current base connection ({0} schemas, {1} tables) are not deleted and stay with that connection. Confirm and request again.");
        // 원본 비교 (M-4d, 2026-11-14)
        add("비교할 TABLE 을 골라주세요.", "Choose a TABLE to compare.");
        add("비교할 수 있는 기준 연결·수집이 없습니다.", "There is no base connection or collection to compare.");
        add("비교 대상에 없는 TABLE 입니다.", "The TABLE is not in the comparison.");
        add("알 수 없는 값입니다 : {0}", "Unknown value: {0}");
        add("기준 연결이 바뀌었거나 새 수집이 반영되었습니다. 비교 화면을 새로 고쳐주세요.", "The base connection changed or a new collection was applied. Refresh the comparison page.");
        // 원본 가져오기 (M-4e, 2026-11-14)
        add("미리보기에서 받은 계획으로 확정해주세요.", "Confirm the plan received from the preview.");
        add("이미 적용된 가져오기입니다. 비교 결과를 새로 고쳐주세요.", "This import has already been applied. Refresh the comparison.");
        add("적용할 수 없는 항목이 있습니다. 미리보기에서 확인해주세요.", "Some items cannot be applied. Check them in the preview.");
        add("가져올 항목을 골라주세요.", "Choose the items to import.");
        add("가져오기 이력을 찾을 수 없습니다.", "The import history was not found.");
        add("다른 사용자가 같은 모델을 바꾸는 중입니다. 잠시 뒤 비교 결과를 새로 고쳐 다시 확정해주세요.", "Another user is changing the same model. Refresh the comparison in a moment and confirm again.");
        add("원본 가져오기 직전 상태 (기준 연결 #{0}, 반영 순서 {1}, 요청 {2})", "State just before a source import (base connection #{0}, reflected order {1}, request {2})");
        add("가져올 TABLE 을 골라주세요.", "Choose the TABLEs to import.");
        add("한 번에 {0}개 TABLE 까지 가져올 수 있습니다.", "Up to {0} TABLEs can be imported at once.");
        add("기준 연결에 완료된 수집이 없어 가져올 수 없습니다. 먼저 메타데이터를 수집해주세요.", "The base connection has no completed collection, so nothing can be imported. Collect its metadata first.");
        add("기준 연결·수집 또는 모델이 미리보기 이후 바뀌었습니다. 비교 결과를 새로 고친 뒤 다시 미리보기 해주세요.", "The base connection, collection or model changed after the preview. Refresh the comparison and preview again.");
    }
}
