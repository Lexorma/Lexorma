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
        add("필수 항목이 없습니다 : {0}", "A required field is missing: {0}");
        add("이미 등록된 도메인 : {0}", "Existing domain: {0}");
        add("같은 이름의 도메인 : {0}", "Domain name: {0}");
        add("도메인을 찾을 수 없습니다: {0}", "Domain not found: {0}");
        add("표준 단어를 찾을 수 없습니다.", "The standard word could not be found.");
        add("표준 용어가 연결되지 않았습니다.", "No standard term is linked.");
        add("컬럼명이 표준 용어의 영문약어와 다릅니다.", "The column name differs from the abbreviation of its standard term.");
        add("표준 도메인 정의와 다릅니다 : {0}", "Differs from the standard domain definition: {0}");
        add("테이블명이 프로젝트 접두어({0})로 시작하지 않습니다.", "The table name does not start with the project prefix ({0}).");
        add("표준 단어로 등록되지 않은 약어 : {0}", "Abbreviations not registered as standard words: {0}");
        add("승인되지 않은 표준 단어 약어 : {0}", "Abbreviations of unapproved standard words: {0}");
        add("비어 있는 약어가 있습니다 ('_' 가 연달아 있거나 앞뒤에 있음).", "There is an empty abbreviation (consecutive, leading or trailing '_').");
        add("바꾼 단어 : {0}", "Replaced words: {0}");
        add("이음동의어 : {0}", "Synonym: {0}");
        add("금칙어 → 표준 단어 : {0}", "Restricted word → standard word: {0}");
        add("영문약어와 도메인이 같은 표준 용어가 {0}개 있습니다.", "{0} standard terms have the same abbreviation and domain.");
        add("용어명이 같은 표준 용어가 {0}개 있습니다 (공백·'_'·대소문자 무시).", "{0} standard terms have the same name (ignoring spaces, '_' and case).");
        add("도메인이 서로 다릅니다.", "Their domains differ.");
        add("영문약어가 같고 도메인이 다른 표준 용어가 {0}개 있습니다. 컬럼명 생성·엑셀 업로드에서 용어를 구분할 수 없습니다.",
                "{0} standard terms share an abbreviation with different domains. Column name generation and Excel upload cannot tell them apart.");
        add("같은 표준 단어로 이루어진 용어가 {0}개 있습니다.", "{0} terms consist of the same standard words.");
        add("단어 순서가 다릅니다.", "The word order differs.");
        add("표준 단어의 이음동의어를 대표 단어로 바꾸면 단어 구성이 같습니다 : {0}", "Replacing standard-word synonyms with their main words gives the same words: {0}");
        add("용어명이 다른 표준 용어의 이음동의어({0})와 같습니다.", "The term name equals a synonym ({0}) of another standard term.");
        add("다른 표준 단어의 금칙어가 쓰였습니다 : {0}", "A restricted word of another standard word is used: {0}");
        add("표준 용어가 연결되어 있고 컬럼명이 영문약어와 같아 개선 후보를 계산하지 않습니다.",
                "The column is linked to a standard term and its name equals the abbreviation, so no suggestions are calculated.");
        add("표준 사전에서 이 컬럼에 맞는 후보를 찾지 못했습니다. 표준 용어를 직접 찾거나 새 용어 등록을 검토하세요.",
                "No matching candidate was found in the standard dictionary. Search the standard terms yourself or consider registering a new term.");
        add("연결된 표준 용어의 영문약어로 컬럼명을 바꾸는 후보입니다.", "Rename the column to the abbreviation of the linked standard term.");
        add("컬럼명이 이 표준 용어의 영문약어와 같습니다. 용어만 연결하면 됩니다.", "The column name equals this term's abbreviation. Linking the term is enough.");
        add("컬럼 논리명이 이 표준 용어의 용어명과 같습니다.", "The column's logical name equals this term's name.");
        add("컬럼 논리명이 이 표준 용어의 이음동의어와 같습니다.", "The column's logical name equals a synonym of this term.");
        add("컬럼명의 약어가 이 표준 용어의 단어 구성과 같습니다.", "The abbreviations in the column name match this term's words.");
        add("논리명이 같은 다른 컬럼 {0}개가 이 표준 용어를 씁니다.", "{0} other columns with the same logical name use this term.");
        add("컬럼명의 약어가 모두 표준 단어이지만 그 단어 구성의 표준 용어가 없습니다. 새 용어 등록을 검토하세요.",
                "Every abbreviation in the column name is a standard word, but no standard term has these words. Consider registering a new term.");
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
        add("대상과 업무 설명, 화면이 읽은 업무 설명이 필요합니다.", "The target, the business description and the business description the screen loaded are required.");
        add("바꿀 표준 용어와 화면이 읽은 표준 용어가 필요합니다.", "The new standard term and the standard term the screen loaded are required.");
        add("표준 용어가 연결되지 않은 컬럼은 표준 용어를 바꿀 수 없습니다.", "The standard term cannot be changed for a column that has no standard term.");
        add("종결 사유를 입력해주세요.", "Enter a reason for closing.");
        add("확인한 변경과 계속 검토하는 이유를 입력해주세요.", "Enter the changes you checked and why the review continues.");
        add("{0} : 다른 사용자가 업무 설명을 수정했습니다. 화면을 새로 열어 확인하세요.", "{0}: another user changed the business description. Open the screen again and check.");
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
        add("테이블 찾기는 1~{0}개까지 조회할 수 있습니다.", "Table search returns 1 to {0} tables at a time.");
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

        // ---------------------------------------------------------------- AI Provider (AI-3)
        add("AI 서버 응답 시간이 초과되었습니다.", "The AI server did not respond in time.");
        add("AI 서버 주소 형식이 올바르지 않습니다.", "The AI server address is not valid.");
        add("AI 서버 주소는 255자까지 입력할 수 있습니다.", "The AI server address can be up to 255 characters.");
        add("AI 서버 주소는 http:// 또는 https:// 로 시작해야 합니다.", "The AI server address must start with http:// or https://.");
        add("AI 서버 주소를 입력해주세요.", "Enter the AI server address.");
        add("AI 서버 주소에 쿼리나 # 을 넣을 수 없습니다.", "The AI server address cannot contain a query or #.");
        add("AI 서버 주소의 호스트가 올바르지 않습니다.", "The host of the AI server address is not valid.");
        add("AI 서버가 다른 주소로 이동하라는 응답({0})을 보냈습니다. 리다이렉트는 따라가지 않으니 실제 주소를 등록하세요.", "The AI server responded with a redirect ({0}). Redirects are not followed, so register the actual address.");
        add("AI 서버가 연결을 거부했습니다. 서버가 실행 중인지, 주소와 포트가 맞는지 확인하세요.", "The AI server refused the connection. Check that the server is running and that the address and port are correct.");
        add("AI 서버가 오류로 응답했습니다 (HTTP {0}).", "The AI server responded with an error (HTTP {0}).");
        add("AI 서버가 요청한 API 를 찾지 못했습니다 (404). 주소와 Provider 유형을 확인하세요.", "The AI server could not find the requested API (404). Check the address and the provider type.");
        add("AI 서버에 그 모델이 없습니다. 모델 목록을 확인하거나 서버에서 모델을 내려받으세요.", "The AI server does not have that model. Check the model list or download the model on the server.");
        add("AI 서버에 연결하지 못했습니다.", "Could not connect to the AI server.");
        add("AI 서버와 TLS 연결을 맺지 못했습니다 (인증서·호스트명 확인).", "Could not establish a TLS connection with the AI server (check the certificate and host name).");
        add("AI 서버의 응답을 처리하지 못했습니다.", "The response from the AI server could not be processed.");
        add("Provider 유형을 선택해주세요.", "Select a provider type.");
        add("Provider 이름은 100자까지 입력할 수 있습니다.", "The provider name can be up to 100 characters.");
        add("Provider 이름을 입력해주세요.", "Enter the provider name.");
        add("같은 이름의 Provider 가 이미 있습니다.", "A provider with the same name already exists.");
        add("기본 모델이 없습니다. 모델을 지정하세요.", "There is no default model. Specify a model.");
        add("모델 ID 형식이 올바르지 않습니다.", "The model ID format is not valid.");
        add("이 Provider 는 모델 목록 조회를 지원하지 않습니다. 모델 ID 를 직접 입력하세요.", "This provider does not support listing models. Enter the model ID directly.");
        add("이 Provider 는 연결 테스트를 지원하지 않습니다.", "This provider does not support connection tests.");
        add("지원하지 않는 Provider 유형입니다: {0}", "Unsupported provider type: {0}");
        add("기본으로 지정할 Provider 를 선택해주세요.", "Select the provider to set as the default.");
        add("비활성화된 Provider 는 기본으로 지정할 수 없습니다.", "A disabled provider cannot be set as the default.");
        add("기본 모델이 없는 Provider 는 기본으로 지정할 수 없습니다. Provider 의 기본 모델을 먼저 정하세요.", "A provider without a default model cannot be set as the default. Set the provider's default model first.");
        add("이 Provider 에서 지원하지 않는 AI 기능입니다 (스트리밍 등).", "This AI feature is not supported by this provider (streaming and so on).");
        add("프롬프트는 {0}자까지 입력할 수 있습니다.", "The prompt can be up to {0} characters.");
        add("프롬프트를 입력해주세요.", "Enter a prompt.");
        // AI-4 메타데이터 질의·Tool
        add("질문을 입력해주세요.", "Enter a question.");
        add("그 이름의 물리 테이블이 없습니다. 한글명·설명이면 searchTables 로 먼저 찾아 tableId 를 쓰세요.", "There is no physical table with that name. For a Korean name or a description, call searchTables first and use the tableId.");
        add("targetId 나 targetName 이 필요합니다.", "targetId or targetName is required.");
        add("대상 범위가 올바르지 않습니다.", "The target scope is not valid.");
        add("대상 테이블이나 컬럼이 필요합니다.", "A target table or column is required.");
        add("대상을 고르세요.", "Choose the targets.");
        add("만들 대상이 없습니다.", "There is nothing to generate.");
        add("반려 사유를 입력해주세요.", "Enter the reason for rejection.");
        add("사유는 {0}자까지 입력할 수 있습니다.", "The reason can be up to {0} characters.");
        add("이 대상의 설명 초안을 만들지 못했습니다.", "The description draft for this target could not be created.");
        add("블라인드 평가 중인 제안은 반려할 수 없습니다. 실제 의미를 확인한 뒤 처리하거나 보류·종결하세요.", "A proposal under blind evaluation cannot be rejected. Handle it after the actual meaning is confirmed, or put it on hold or close it.");
        add("보류 사유를 입력해주세요.", "Enter the reason for putting it on hold.");
        add("판정을 고친 사유를 입력해주세요.", "Enter the reason for changing the judgment.");
        add("바꿀 컬럼을 고르세요.", "Choose the columns to change.");
        add("한 번에 {0}개 테이블까지 고를 수 있습니다.", "Up to {0} tables can be chosen at once.");
        add("미리보기를 먼저 확인해주세요.", "Check the preview first.");
        add("생성 계획", "Generation plan");
        add("만들 수 없는 항목이 있습니다. 미리보기의 오류를 먼저 고쳐주세요.", "Some items cannot be created. Fix the errors in the preview first.");
        add("이미 테이블 {0} 에 매핑된 엔터티라 만들지 않습니다.", "Not created because the entity is already mapped to table {0}.");
        add("엔터티명 {0} 으로 표준 단어 기준 테이블명을 만들 수 없습니다. 표준 단어를 먼저 등록해주세요.", "A table name cannot be built from standard words for entity {0}. Register the standard words first.");
        add("고른 엔터티 중 같은 테이블명({0})이 되는 것이 있습니다.", "Several chosen entities would get the same table name ({0}).");
        add("스키마 {0} 에 같은 이름의 테이블·VIEW {1} 가 있습니다. 덮어쓰지 않습니다.", "Schema {0} already has a table or view named {1}. It is not overwritten.");
        add("주 식별자가 없어 PK 없이 만듭니다.", "There is no primary identifier, so the table is created without a PK.");
        add("속성 {0} 에 표준 용어가 없습니다. 논리 모델에서 표준 용어를 먼저 연결해주세요.", "Attribute {0} has no standard term. Link a standard term in the logical model first.");
        add("속성 {0} 의 표준 용어에 영문 약어가 없습니다.", "The standard term of attribute {0} has no abbreviation.");
        add("속성 {0} 의 표준 용어에 도메인 데이터 타입이 없습니다.", "The standard term of attribute {0} has no domain data type.");
        add("같은 컬럼명({0})이 되는 속성이 둘 이상입니다.", "Two or more attributes would get the same column name ({0}).");
        add("속성 {0} 의 표준 용어가 승인 전입니다.", "The standard term of attribute {0} is not approved yet.");
        add("속성이 없는 엔터티는 만들 수 없습니다.", "An entity without attributes cannot be created.");
        add("관계 {0} → {1} : 부모 식별자 ↔ 자식 속성 짝이 지정되지 않아 FK 를 만들지 않습니다.", "Relationship {0} → {1}: no FK is created because no parent identifier ↔ child attribute pairs are specified.");
        add("엔터티 {0} 이(가) 승인 전입니다.", "Entity {0} is not approved yet.");
        // 논리 관계 기반 FK 생성 (M-7 보강 4차)
        add("FK 속성 {0} 은(는) 제외할 수 없어 포함했습니다.", "Foreign key attribute {0} cannot be excluded, so it is included.");
        add("관계 {0} → {1} : FK 를 만듭니다 ({2}).", "Relationship {0} → {1}: an FK is created ({2}).");
        add("관계 {0} → {1} : 부모 테이블이 없어(이번에 만들지 않고 매핑도 없음) FK 를 만들지 않습니다.", "Relationship {0} → {1}: no FK is created because there is no parent table (not created now and not mapped).");
        add("관계 {0} → {1} : 짝의 부모·자식 컬럼을 정할 수 없어(용어·도메인 없음, 부모 속성 매핑 없음) FK 를 만들지 않습니다.", "Relationship {0} → {1}: no FK is created because the parent or child columns of the pairs cannot be determined (no term or domain, or the parent attribute is not mapped).");
        add("관계 {0} → {1} : 자식 속성이 다른 관계의 FK 에 이미 쓰여 FK 를 만들지 않습니다.", "Relationship {0} → {1}: no FK is created because the child attribute is already used by another relationship's FK.");
        add("관계 {0} → {1} : 자기 참조인데 자식 속성이 부모 키 속성 자신이라 FK 를 만들지 않습니다.", "Relationship {0} → {1}: no FK is created because this self-reference uses the parent key attributes themselves as the child.");
        add("관계 {0} → {1} : 부모·자식 컬럼의 데이터 타입이 FK 로 연결할 수 없는 조합이라 FK 를 만들지 않습니다.", "Relationship {0} → {1}: no FK is created because the parent and child column data types cannot be linked by an FK.");
        add("관계 {0} → {1} : 기존 부모 테이블의 PK 가 짝의 부모 컬럼과 달라 FK 를 만들지 않습니다 (기존 테이블은 고치지 않습니다).", "Relationship {0} → {1}: no FK is created because the existing parent table's PK differs from the pairs' parent columns (existing tables are not changed).");
        add("일반 단일 FK", "single FK");
        add("자기 참조 단일 FK", "self-referencing single FK");
        add("복합 FK", "composite FK");
        add("자기 참조 복합 FK", "self-referencing composite FK");
        // 논리 관계 속성 짝 (M-7 보강 3차)
        add("관계 {0} 의 속성 짝에서 사용 중인 속성입니다. 속성 짝을 먼저 해제해주세요.", "The attribute is used in an attribute pair of relationship {0}. Remove the pair first.");
        add("속성 짝은 {0}개까지 지정할 수 있습니다.", "Up to {0} attribute pairs can be specified.");
        add("부모 엔터티 {0} 에 주 식별자가 없어 속성 짝을 지정할 수 없습니다.", "Parent entity {0} has no primary identifier, so attribute pairs cannot be specified.");
        add("속성 짝의 부모 속성과 자식 속성을 모두 골라주세요.", "Choose both the parent and the child attribute of each pair.");
        add("부모 속성은 부모 엔터티 {0} 의 주 식별자 속성이어야 합니다. (ID {1})", "The parent attribute must be a primary identifier attribute of parent entity {0}. (ID {1})");
        add("자식 속성은 자식 엔터티 {0} 의 속성이어야 합니다. (ID {1})", "The child attribute must be an attribute of child entity {0}. (ID {1})");
        add("같은 부모 속성 {0} 을(를) 두 번 지정할 수 없습니다.", "Parent attribute {0} cannot be used twice.");
        add("같은 자식 속성 {0} 을(를) 두 번 지정할 수 없습니다.", "Child attribute {0} cannot be used twice.");
        add("형식 버전 {0} 파일에는 논리 관계 속성 짝이 들어갈 수 없습니다.", "A format version {0} file cannot contain logical relationship attribute pairs.");
        add("논리 관계 {0} 의 속성 짝이 올바르지 않습니다 (부모 주 식별자 속성 · 자식 엔터티 속성 · 중복 없는 순서).", "The attribute pairs of logical relationship {0} are invalid (parent primary identifier attribute · child entity attribute · unique order).");
        add("관계 {0} → {1} : 속성 짝이 부모 주 식별자의 일부만 이어 FK 를 만들지 않습니다.", "Relationship {0} → {1}: no FK is created because the attribute pairs cover only part of the parent primary identifier.");
        add("관계 {0} → {1} : 속성 짝이 지금 부모 주 식별자와 맞지 않아 FK 를 만들지 않습니다. 관계의 속성 짝을 고쳐주세요.", "Relationship {0} → {1}: no FK is created because the attribute pairs no longer match the parent primary identifier. Fix the relationship's attribute pairs.");
        // 논리 → 물리 생성 보강 2차 (P3·P7·P10·P11)
        add("다른 사용자가 같은 논리 모델이나 스키마를 바꾸는 중입니다. 잠시 뒤 다시 미리보기해주세요.", "Another user is changing the same logical model or schema. Preview again in a moment.");
        add("논리 → 물리 생성 직전 상태 (엔터티 {0}개, 요청 {1})", "State before logical → physical generation ({0} entities, request {1})");
        add("주 식별자 속성 {0} 은(는) 제외할 수 없어 포함했습니다.", "Primary identifier attribute {0} cannot be excluded, so it is included.");
        add("모든 속성을 제외해 만들 컬럼이 없습니다.", "All attributes are excluded, so there are no columns to create.");
        add("제외할 속성 ID 가 비어 있습니다.", "An attribute ID to exclude is empty.");
        add("제외할 속성 ID 가 중복되었습니다. (ID {0})", "An attribute ID to exclude is duplicated. (ID {0})");
        add("제외할 속성을 고른 엔터티에서 찾을 수 없습니다. (ID {0})", "The attribute to exclude is not in the chosen entities. (ID {0})");
        // DBMS 식별자 검사 (M-7 보강 1차)
        add("PostgreSQL : 63바이트 (UTF-8, 넘으면 DBMS 가 잘라서 만듭니다)", "PostgreSQL: 63 bytes (UTF-8; longer names are truncated by the DBMS)");
        add("Oracle : 30바이트 (12.1 이하 기준 — 12.2 이상은 128바이트, DDL 생성 기준과 같음)", "Oracle: 30 bytes (12.1 and earlier — 128 bytes from 12.2; same as DDL generation)");
        add("MySQL : 64자", "MySQL: 64 characters");
        add("MariaDB : 64자", "MariaDB: 64 characters");
        add("PostgreSQL 비인용 식별자 : 문자 또는 _ 로 시작, 문자·숫자·_·$", "PostgreSQL unquoted identifier: starts with a letter or _, then letters, digits, _ or $");
        add("Oracle 비인용 식별자 : 문자로 시작, 문자·숫자·_·$·#", "Oracle unquoted identifier: starts with a letter, then letters, digits, _, $ or #");
        add("MySQL 비인용 식별자 : 영문·숫자·$·_·U+0080 이상 문자 (숫자만은 불가)", "MySQL unquoted identifier: letters, digits, $, _ and characters from U+0080 (not digits only)");
        add("MariaDB 비인용 식별자 : 영문·숫자·$·_·U+0080 이상 문자 (숫자만은 불가)", "MariaDB unquoted identifier: letters, digits, $, _ and characters from U+0080 (not digits only)");
        add("Lexorma 권장 형식 : 영문 또는 _ 로 시작, 영문·숫자·_·$", "Lexorma recommended format: starts with a letter or _, then letters, digits, _ or $");
        add("PostgreSQL : PK·UNIQUE·인덱스는 스키마 안에서, 제약조건은 테이블 안에서 이름이 하나여야 합니다", "PostgreSQL: PK, UNIQUE and index names must be unique in the schema, constraint names in the table");
        add("Oracle : 제약조건과 인덱스는 각각 스키마 안에서 이름이 하나여야 합니다 (PK·UNIQUE 는 같은 이름의 인덱스를 만듭니다)", "Oracle: constraint names and index names must each be unique in the schema (PK and UNIQUE create an index with the same name)");
        add("MySQL : FK·CHECK 는 스키마 안에서, UNIQUE·인덱스는 테이블 안에서 이름이 하나여야 합니다 (PK 이름은 쓰지 않습니다)", "MySQL: FK and CHECK names must be unique in the schema, UNIQUE and index names in the table (the PK name is not used)");
        add("MariaDB : FK 는 스키마 안에서, UNIQUE·인덱스·CHECK 는 테이블 안에서 이름이 하나여야 합니다 (PK 이름은 쓰지 않습니다)", "MariaDB: FK names must be unique in the schema, UNIQUE, index and CHECK names in the table (the PK name is not used)");
        add("{0} 이(가) {1} 식별자 길이 기준({2})을 넘습니다. (현재 {3})", "{0} exceeds the {1} identifier length limit ({2}). (currently {3})");
        add("{0} 은(는) {1} 에서 따옴표 없는 식별자로 쓸 수 없는 문자나 시작 문자가 있습니다.", "{0} has characters or a first character that {1} does not accept in an unquoted identifier.");
        add("{0} 은(는) {1} 에서 쓸 수 있지만 Lexorma 권장 형식과 다릅니다.", "{0} is accepted by {1} but differs from the Lexorma recommended format.");
        add("DDL 의 제약조건명 {0} 이(가) {1} 길이 기준({2})을 넘어 {3} 으로 잘립니다.", "The DDL constraint name {0} exceeds the {1} length limit ({2}) and is truncated to {3}.");
        add("DDL 의 최종 이름 {0} 이(가) 같은 이름 공간의 {1} 와(과) 겹칩니다.", "The final DDL name {0} collides with {1} in the same namespace.");
        add("스키마", "Schema");
        add("테이블", "Table");
        add("컬럼", "Column");
        add("인덱스", "Index");
        add("더 짧은 표준 단어 약어를 쓰거나 이름을 줄여주세요.", "Use shorter standard word abbreviations or shorten the name.");
        add("영문으로 시작하고 영문·숫자·_ 만 쓰도록 이름(표준 단어·용어 약어)을 고쳐주세요.", "Change the name (standard word or term abbreviation) to start with a letter and use only letters, digits and _.");
        add("테이블명·컬럼명을 줄이면 잘리지 않습니다. 잘린 이름이 겹치지 않는지 확인해주세요.", "Shorter table or column names are not truncated. Check that the truncated names do not collide.");
        add("겹치는 테이블명·컬럼명이나 제약조건·인덱스 이름을 바꿔주세요. 이대로면 DDL 실행이 실패할 수 있습니다.", "Rename the colliding table, column, constraint or index. Otherwise running the DDL can fail.");
        add("만들 엔터티를 고르세요.", "Choose the entities to create.");
        add("한 번에 {0}개 엔터티까지 만들 수 있습니다.", "Up to {0} entities can be created at once.");
        add("엔터티를 찾을 수 없습니다. (ID {0})", "Entity not found. (ID {0})");
        add("논리 모델을 찾을 수 없습니다.", "Logical model not found.");
        add("프로젝트에서 쓸 Provider 를 선택해주세요.", "Choose the provider for this project.");
        add("비활성화된 Provider 는 지정할 수 없습니다.", "A disabled provider cannot be set.");
        add("기본 모델이 없는 Provider 는 모델을 함께 지정해야 합니다.", "A provider without a default model needs a model.");
        add("충돌한 객체 : {0}", "Conflicting object: {0}");
        add("한 번에 1~{0}개 엔터티까지 찾을 수 있습니다.", "Up to 1–{0} entities can be searched at once.");
        add("한 번에 1~{0}개 엔터티까지 조회할 수 있습니다.", "Up to 1–{0} entities can be retrieved at once.");
        add("페이지 번호는 0 이상이어야 합니다.", "The page number must be 0 or greater.");
        add("페이지 크기는 1~{0} 사이여야 합니다.", "The page size must be between 1 and {0}.");
        add("요청한 페이지가 조회할 수 있는 범위를 벗어났습니다.", "The requested page is out of the range that can be retrieved.");
        add("허용하지 않는 정렬 항목입니다.", "This field cannot be used for sorting.");
        add("정렬 방향은 asc 또는 desc 만 쓸 수 있습니다.", "The sort direction must be asc or desc.");
        add("화면을 연 뒤 다른 사용자가 표준 용어 {0} 을(를) 먼저 변경해 저장하지 않았습니다. 입력한 내용은 그대로 두었습니다. 최신 정보를 확인한 후 다시 수정해 주세요. 창을 닫고 다시 불러오면 입력한 내용은 사라지니 필요하면 먼저 복사해 두세요.", "Another user changed the standard term {0} after you opened this screen, so it was not saved. Your input was kept. Review the latest information and edit again. Closing this window and reloading discards your input, so copy it first if you need it.");
        add("화면을 연 뒤 표준 용어 {0} 이(가) 변경되어 승인하지 않았습니다. 최신 정보를 확인한 후 다시 승인해 주세요.", "The standard term {0} changed after you opened this screen, so it was not approved. Review the latest information and approve again.");
        add("화면을 연 뒤 표준 용어 {0} 이(가) 변경되어 승인을 취소하지 않았습니다. 최신 정보를 확인한 후 다시 승인 취소해 주세요.", "The standard term {0} changed after you opened this screen, so its approval was not revoked. Review the latest information and revoke the approval again.");
        add("한 번에 {0}개 컬럼까지 바꿀 수 있습니다.", "Up to {0} columns can be changed at once.");
        add("같은 컬럼이 두 번 들어 있습니다.", "The same column is included twice.");
        add("{0} : 엑셀 셀에 담을 수 있는 길이({1}자)를 넘는 값이 있어 내려받을 수 없습니다.", "{0} : A value is longer than an Excel cell can hold ({1} characters), so it cannot be downloaded.");
        add("실제 의미를 확인한 평가 대상만 판정할 수 있습니다.", "Only evaluation targets whose actual meaning is confirmed can be judged.");
        add("실행을 찾을 수 없습니다.", "The job was not found.");
        add("일괄 처리는 승인·반려·보류만 할 수 있습니다.", "Bulk review supports only approve, reject and hold.");
        add("조회 조건이 올바르지 않습니다.", "The search conditions are not valid.");
        add("처리할 제안을 고르세요.", "Choose the proposals to process.");
        add("테이블을 고르세요.", "Choose a table.");
        add("한 번에 {0}개까지 만들 수 있습니다. 범위를 좁혀주세요.", "Up to {0} targets can be generated at once. Narrow the scope.");
        add("한 번에 {0}개까지 처리할 수 있습니다.", "Up to {0} items can be processed at once.");
        add("그 대상이 현재 프로젝트에 없고 기록된 이력도 없습니다. 이름은 물리명이어야 합니다 — 한글명·설명이면 searchTables 로 먼저 찾아 ID 를 쓰세요.", "No such object exists in the current project and no history is recorded for it. Names must be physical names; for a Korean name or a description, call searchTables first and use the id.");
        add("targetType 은 TABLE·COLUMN·RELATION 중 하나입니다.", "targetType must be TABLE, COLUMN or RELATION.");
        add("컬럼 ID 나 검색어가 필요합니다.", "A column id or a keyword is required.");
        add("현재 프로젝트에 그 컬럼이 없습니다.", "There is no such column in the current project.");
        add("질문은 {0}자까지 입력할 수 있습니다.", "The question can be up to {0} characters.");
        add("검색어가 필요합니다.", "A search keyword is required.");
        add("같은 이름의 테이블이 여러 스키마에 있습니다. tableId 로 지정하세요 : {0}", "Tables with this name exist in several schemas. Specify the tableId : {0}");

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

                ### 11. AI 기능 (선택)

                관리자가 **AI 관리 › AI Provider 설정**에서 AI 서버(현재 Ollama)를 등록하고 시스템 기본 AI Provider로 지정하면 AI 기능을 사용할 수 있습니다. AI를 사용하지 않아도 다른 기능은 그대로 동작하며, 기본 AI Provider가 없으면 AI 관리 메뉴는 관리자에게만 보입니다.

                - **AI 도우미** : 화면 오른쪽 아래 버튼으로 현재 프로젝트의 메타데이터에 대해 질문합니다. AI는 읽기 전용 조회만 할 수 있고, 답변이 근거로 든 메타데이터는 Lexorma가 다시 확인해 표시합니다.
                - **AI 설명 제안** : AI 관리 › AI 설명 제안에서 테이블·컬럼의 업무 설명 초안을 만들고 근거, 확인할 수 없는 부분, 확인 질문과 함께 검토합니다. 승인한 내용만 업무 설명에 반영되며, 원본 COMMENT와 표준 용어 연결은 바꾸지 않습니다.
                - **AI 품질 평가** : AI 관리 › AI 품질 평가에서 업무 담당자·검토자가 확인한 실제 의미를 기준으로 AI 설명을 판정하고 모델별 지표를 봅니다. AI가 스스로 정답을 판정하지 않습니다.

                ## 이용 안내

                - 최초 로그인 시 초기 비밀번호를 변경하고 접근 가능한 프로젝트를 선택해 주세요.
                - 기본 프로젝트 `LEXORMA`에서 Lexorma 자체 스키마를 예제로 담은 데이터 사전, 논리·물리 모델과 ERD를 확인해 보세요.
                - 원본 DB 정보는 모든 사용자가 조회할 수 있고, 연결·수집·매핑·가져오기는 프로젝트 관리자 또는 ADMIN만 실행할 수 있습니다. 원본 DB에는 조회만 하며 원본 구조를 바꾸지 않습니다.
                - 원본 DB 연결 비밀번호를 저장하려면 서버에 암호화 키(`LEXORMA_SECRET_KEYS`)가 설정되어 있어야 합니다.
                - AI 서버 연결은 관리자가 등록한 주소와 외부 연결 보안의 허용 규칙으로만 이루어지며, 질문과 조회한 메타데이터가 그 AI 서버로 전송됩니다. 실제 업무 데이터는 보내지 않습니다.
                - AI가 만든 답변과 설명 초안은 참고용입니다. 확정하기 전에 담당자가 내용을 확인해 주세요.
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

                Lexorma는 MIT 라이선스로 공개된 프로젝트입니다. 현재 제공하는 기능과 향후 계획(원본 DB 재최신화, AI 기반 품질 점검 등)은 저장소의 문서를 확인해 주세요.
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

                ### 11. AI features (optional)

                When an administrator registers an AI server (Ollama for now) under **AI management › AI provider settings** and sets it as the system default AI provider, the AI features become available. Everything else works without AI, and without a default AI provider the AI management menu is shown only to administrators.

                - **AI assistant** : ask about the metadata of the current project with the button at the bottom right of the screen. The AI can only read, and Lexorma checks again the metadata that an answer cites.
                - **AI description proposals** : under AI management › AI description proposals, draft business descriptions of tables and columns and review them with their evidence, the points that cannot be determined and questions to confirm. Only approved text becomes the business description; source comments and standard term links are not changed.
                - **AI quality evaluation** : under AI management › AI quality evaluation, judge AI descriptions against the actual meaning confirmed by business owners or reviewers and see metrics per model. The AI never judges its own answers.

                ## Getting started

                - On your first login, change the initial password and choose a project you can access.
                - Explore the default project `LEXORMA`, whose example data dictionary, logical and physical models and ERDs describe Lexorma's own schema.
                - Every user can view source database information; connecting, collecting, mapping and importing are limited to the project manager or ADMIN. Lexorma only reads from source databases and never changes their structure.
                - Storing a source database password requires an encryption key (`LEXORMA_SECRET_KEYS`) configured on the server.
                - Lexorma connects only to the AI server address an administrator registered and allowed in external connection security; questions and the metadata looked up are sent to that AI server. Actual business data is not sent.
                - AI answers and description drafts are for reference. Have the person in charge check them before confirming.
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

                Lexorma is released under the MIT License. See the documentation in the repository for the current features and future plans (source database refresh, AI-assisted quality checks and more).
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
        add("수집된 VIEW 정의가 없습니다.", "No collected VIEW definition.");
        // DEC-31 내장 MCP
        add("사용 여부를 정해주세요.", "Choose whether to enable it.");
        add("토큰 이름은 1~100자로 정해주세요.", "Token names must be 1 to 100 characters.");
        add("만료 일시는 지금 이후 {0}일 안으로 정해주세요.", "Set the expiry within {0} days from now.");
        add("토큰을 찾을 수 없습니다.", "The token was not found.");
        // DEC-22 모델 Export·Import
        add("Lexorma 모델 파일이 아닙니다.", "Not a Lexorma model file.");
        add("가져올 객체가 없습니다.", "There are no objects to import.");
        add("값의 모양이 올바르지 않습니다 : {0}.{1}", "Invalid value shape: {0}.{1}");
        add("객체 번호가 없거나 겹칩니다 : {0}", "Object numbers are missing or duplicated: {0}");
        add("객체가 너무 많습니다 (최대 {0}개).", "Too many objects (max {0}).");
        add("계획을 먼저 확인해주세요.", "Preview the plan first.");
        add("글자 값이 올바르지 않습니다.", "Invalid text value.");
        add("모델 내용이 없습니다.", "The model content is missing.");
        add("모델 파일을 읽을 수 없습니다.", "The model file cannot be read.");
        add("번호가 올바르지 않습니다.", "Invalid number.");
        add("숫자가 올바르지 않습니다.", "Invalid numeric value.");
        add("알 수 없는 객체 종류입니다 : {0}", "Unknown object kind: {0}");
        add("지원하지 않는 모델 파일 형식 버전입니다 : {0}", "Unsupported model file format version: {0}");
        add("형식 버전 1 파일에는 VIEW·ERD 배치가 들어갈 수 없습니다.", "A format version 1 file cannot contain VIEWs or ERD layouts.");
        add("논리 ERD 배치가 너무 많습니다.", "Too many logical ERD layouts.");
        add("논리 ERD 배치의 모델 번호가 없거나 겹칩니다.", "A logical ERD layout has a missing or duplicate model number.");
        add("ERD 배치 좌표가 없습니다.", "The ERD layout has no coordinates.");
        add("ERD 배치는 {0}개까지 가져올 수 있습니다.", "Up to {0} ERD layout positions can be imported.");
        add("ERD 배치 좌표가 올바르지 않습니다 : {0}", "Invalid ERD layout coordinate: {0}");
        add("ERD 화면 위치·확대 비율이 올바르지 않습니다.", "Invalid ERD viewport position or zoom.");
        add("참/거짓 값이 올바르지 않습니다.", "Invalid true/false value.");
        add("파일이 너무 큽니다 (최대 30MB).", "The file is too large (max 30 MB).");
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
        // 물리명 추천 일괄 적용 판정 (M-7 물리명 추천 일괄 적용 1차)
        add("같은 스키마에 추천 테이블명과 이름이 같은 VIEW {0} 이(가) 있습니다. 테이블과 VIEW 는 같은 이름을 쓸 수 없습니다.", "The same schema has a view {0} with the recommended table name. A table and a view cannot share a name.");
        add("테이블을 참조하는 VIEW 가 있어 이름을 바꿀 수 없습니다 : {0}. VIEW 정의는 자동으로 고치지 않습니다.", "The name cannot be changed because views reference the table: {0}. View definitions are not changed automatically.");
        add("테이블을 참조하는 VIEW 의 정의에 컬럼명이 있어 이름을 바꿀 수 없습니다 : {0}. VIEW 정의는 자동으로 고치지 않습니다.", "The name cannot be changed because the definitions of views referencing the table contain the column name: {0}. View definitions are not changed automatically.");
        add("테이블을 참조하는 VIEW 의 정의에 * 또는 NATURAL 이 있어 컬럼 이름 변경의 영향을 판정할 수 없습니다 : {0}", "The effect of renaming the column cannot be determined because the definitions of views referencing the table contain * or NATURAL: {0}");
        add("CHECK 제약조건이 이 이름을 쓰고 있어 바꿀 수 없습니다 : {0}. CHECK 식은 자동으로 고치지 않습니다.", "The name cannot be changed because CHECK constraints use it: {0}. CHECK expressions are not changed automatically.");
        add("인덱스의 식 또는 조건이 이 이름을 쓰고 있어 바꿀 수 없습니다 : {0}. 인덱스 식은 자동으로 고치지 않습니다.", "The name cannot be changed because index expressions or conditions use it: {0}. Index expressions are not changed automatically.");
        add("CHECK 식 또는 인덱스 식을 낱말로 나눌 수 없어 참조 여부를 판정할 수 없습니다 : {0}", "Whether the name is referenced cannot be determined because CHECK or index expressions cannot be split into words: {0}");
        add("정의를 낱말로 나눌 수 없는 VIEW 가 있어 이름 참조 여부를 판정할 수 없습니다 : {0}", "Whether the name is referenced cannot be determined because the definitions of some views cannot be split into words: {0}");
        add("같은 테이블의 다른 컬럼도 같은 추천 컬럼명을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.", "Other columns of the same table get the same recommended column name: {0}. Recommendations with the same name cannot be applied together.");
        add("같은 스키마의 다른 테이블도 같은 추천 테이블명을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.", "Other tables of the same schema get the same recommended table name: {0}. Recommendations with the same name cannot be applied together.");
        add("승인된 컬럼은 이름을 바꿀 수 없습니다. 승인을 취소한 뒤 적용할 수 있습니다.", "An approved column cannot be renamed. Revoke the approval before applying.");
        add("승인된 테이블은 이름을 바꿀 수 없습니다. 승인을 취소한 뒤 적용할 수 있습니다.", "An approved table cannot be renamed. Revoke the approval before applying.");
        add("컬럼에 표준 용어가 연결되어 있지 않아 이름을 바꿀 수 없습니다. 논리 속성과 같은 표준 용어를 컬럼에 연결한 뒤 적용할 수 있습니다.", "The column cannot be renamed because no standard term is linked to it. Link the same standard term as the logical attribute to the column before applying.");
        add("컬럼의 표준 용어가 논리 속성의 표준 용어와 달라 이름을 바꿀 수 없습니다. 용어 연결을 먼저 맞춰주세요.", "The column cannot be renamed because its standard term differs from the logical attribute's standard term. Align the term links first.");
        add("이름을 바꿀 물리 컬럼이 없습니다. 속성이 컬럼에 매핑되지 않았습니다.", "There is no physical column to rename. The attribute is not mapped to a column.");
        add("이름을 바꿀 물리 테이블이 없습니다. 엔터티가 테이블에 매핑되지 않았습니다.", "There is no physical table to rename. The entity is not mapped to a table.");
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
        add("{0} : IDENTITY 컬럼이라 기본값({1})을 DDL 에 쓰지 않았습니다.", "{0}: the default value ({1}) was not written to the DDL because the column is an IDENTITY column.");
        add("{0} : {1} 은(는) GENERATED ALWAYS 가 없어 AUTO_INCREMENT(값을 직접 넣을 수 있음)로 만들었습니다.", "{0}: {1} has no GENERATED ALWAYS, so the column was created as AUTO_INCREMENT (explicit values are allowed).");
        add("{0} : {1} 은(는) {2} {3} 을(를) 지원하지 않아 DDL 에서 뺐습니다.", "{0}: {1} does not support {2} {3}, so it was left out of the DDL.");
        add("논리 관계에는 삭제·수정 규칙이 없습니다.", "Logical relationships have no delete or update rules.");
        add("FK 규칙은 NO_ACTION · RESTRICT · CASCADE · SET_NULL · SET_DEFAULT 중 하나입니다.", "An FK rule must be one of NO_ACTION, RESTRICT, CASCADE, SET_NULL or SET_DEFAULT.");
        add("IDENTITY 는 ALWAYS 또는 BY_DEFAULT 입니다.", "IDENTITY must be ALWAYS or BY_DEFAULT.");
        add("IDENTITY 는 정수 타입(SMALLINT·INTEGER·BIGINT 등)에만 쓸 수 있습니다.", "IDENTITY can only be used with integer types (SMALLINT, INTEGER, BIGINT and so on).");
        add("IDENTITY 컬럼에는 기본값을 쓸 수 없습니다.", "An IDENTITY column cannot have a default value.");
        add("매핑 예외로 지정된 원본 테이블 {0} 은(는) 매핑할 수 없습니다. 예외를 먼저 취소해주세요.", "The source table {0} is excluded from mapping and cannot be mapped. Cancel the exclusion first.");
        add("VIEW 는 매핑하지 않으므로 예외로 지정할 필요가 없습니다.", "VIEWs are not mapped, so they do not need to be excluded.");
        add("원본 테이블 {0} 은(는) 이미 매핑 예외입니다.", "The source table {0} is already excluded from mapping.");
        add("매핑된 원본 테이블 {0} 은(는) 예외로 지정할 수 없습니다. 매핑을 먼저 해제해주세요.", "The mapped source table {0} cannot be excluded. Release the mapping first.");
        add("원본 테이블을 골라주세요.", "Choose a source table.");
        add("매핑 예외를 찾을 수 없습니다.", "The mapping exclusion was not found.");
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
        // 원본 이름 변경 매핑 (DEC-06, 2026-11-16)
        add("TABLE 매핑과 새 원본 테이블을 골라주세요.", "Choose a TABLE mapping and the new source table.");
        add("기준 연결이 바뀌었거나 새 수집이 반영되었습니다. 화면을 새로 고쳐주세요.", "The base connection changed or a new collection was applied. Refresh the page.");
        add("마지막으로 반영된 수집의 원본 테이블에 컬럼 {0} 이(가) 없습니다.", "Column {0} is not in the source table of the last applied collection.");
        add("매핑된 모델 테이블의 컬럼이 아닙니다.", "The column does not belong to the mapped model table.");
        add("매핑됨 상태인 TABLE 매핑에서만 컬럼 매핑을 확정할 수 있습니다.", "Column mappings can be confirmed only in a TABLE mapping whose status is Mapped.");
        add("모델 컬럼 {0} 은(는) 이미 다른 원본 컬럼과 짝지어져 있습니다.", "Model column {0} is already paired with another source column.");
        add("모델 컬럼 {0} 은(는) 이미 원본 컬럼 {1} 에 매핑되어 있습니다.", "Model column {0} is already mapped to source column {1}.");
        add("모델 테이블이 지워진 매핑은 옮길 수 없습니다. 매핑을 해제해주세요.", "A mapping whose model table was deleted cannot be moved. Release the mapping.");
        add("새 원본 테이블 {0} 의 스키마가 모델 테이블 {1} 의 스키마에 매핑되어 있지 않습니다.", "The schema of the new source table {0} is not mapped to the schema of model table {1}.");
        add("원본 컬럼 {0} 은(는) 이미 다른 모델 컬럼과 짝지어져 있습니다.", "Source column {0} is already paired with another model column.");
        add("원본 컬럼과 모델 컬럼을 골라주세요.", "Choose a source column and a model column.");
        add("원본 테이블 {0} 은(는) 이미 매핑되었거나 매핑 예외입니다.", "Source table {0} is already mapped or excluded from mapping.");
        add("원본 테이블이 아직 있는 매핑은 이름 변경으로 옮길 수 없습니다. 원본에서 사라진(원본 없음) 매핑만 옮깁니다.", "A mapping whose source table still exists cannot be moved as a rename. Only mappings whose source disappeared (Source missing) are moved.");
        add("이름이 같은 컬럼은 매핑하지 않아도 같은 컬럼으로 봅니다.", "Columns with the same name are treated as the same column without a mapping.");
        add("컬럼 매핑을 찾을 수 없습니다.", "The column mapping was not found.");
        // 원본 삭제 반영 (DEC-04, 2026-11-16)
        add("기준 연결과 완료된 수집이 있어야 원본 삭제를 반영할 수 있습니다.", "A base connection and a completed collection are required to apply source deletions.");
        add("기준 수집의 객체 수가 직전 정상 수집의 절반 미만입니다. 수집 결과를 확인한 뒤 급감 경고를 확인하고 다시 확정해주세요.", "The base collection has less than half the objects of the previous successful collection. Check the collection result, acknowledge the drop warning and confirm again.");
        add("원본 삭제 반영 직전 상태 (기준 연결 #{0}, 반영 순서 {1}, 요청 {2})", "State before applying source deletions (base connection #{0}, applied order {1}, request {2})");
        add("이미 적용된 요청입니다. 화면을 새로 고쳐주세요.", "This request has already been applied. Refresh the page.");
        add("지울 수 없는 항목이 있습니다. 미리보기에서 확인해주세요.", "Some items cannot be deleted. Check them in the preview.");
        add("지울 항목을 골라주세요.", "Choose the items to delete.");
        add("한 번에 {0}개까지 고를 수 있습니다.", "You can choose up to {0} items at a time.");
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
        // 논리 모델 주제 영역 (M-6 후속 4차, 2026-11-20)
        add("주제 영역", "subject area");
        add("주제 영역명", "subject area name");
        add("상위 주제 영역", "parent subject area");
        add("옮길 주제 영역", "target subject area");
        add("엔터티의 지금 주제 영역", "current subject area of the entity");
        add("주제 영역과 미분류를 함께 지정할 수 없습니다.", "A subject area and Unclassified cannot be specified together.");
        add("주제 영역 정보가 없습니다.", "The subject area information is missing.");
        add("논리 모델 하나에 주제 영역은 {0}개까지 만들 수 있습니다.", "Up to {0} subject areas can be created in one logical model.");
        add("주제 영역을 자기 자신의 하위로 옮길 수 없습니다.", "A subject area cannot be moved under itself.");
        add("주제 영역을 자기 하위 영역 아래로 옮길 수 없습니다.", "A subject area cannot be moved under one of its own sub-areas.");
        add("주제 영역은 {0}단계까지 만들 수 있습니다.", "Subject areas can be nested up to {0} levels.");
        add("하위 영역까지 {0}단계를 넘게 되어 옮길 수 없습니다.", "The subject area cannot be moved because its sub-areas would exceed {0} levels.");
        add("표시 순서는 0 이상 1,000,000 이하로 입력해주세요.", "Enter a display order between 0 and 1,000,000.");
        add("하위 주제 영역 {0} 이(가) 있습니다. 하위 영역을 먼저 옮기거나 삭제해주세요.", "Child subject area {0} exists. Move or delete the sub-areas first.");
        add("주제 영역에 엔터티 {0}개가 소속되어 있습니다. 엔터티의 분류를 먼저 바꾸거나 해제해주세요.", "{0} entities belong to the subject area. Change or remove their classification first.");
        add("옮길 주제 영역을 지정해주세요. (미분류는 null)", "Specify the target subject area. (null for Unclassified)");
        add("분류할 엔터티를 1개 이상 골라주세요.", "Choose at least one entity to classify.");
        add("한 번에 엔터티 {0}개까지 분류할 수 있습니다.", "Up to {0} entities can be classified at once.");
        add("엔터티를 지정해주세요.", "Specify the entity.");
        add("엔터티의 지금 주제 영역을 함께 보내야 합니다. (미분류는 null)", "Send the current subject area of each entity as well. (null for Unclassified)");
        add("같은 엔터티를 두 번 지정할 수 없습니다.", "The same entity cannot be specified twice.");
        add("엔터티 {0} 의 주제 영역이 화면을 연 뒤 바뀌었습니다. 아무것도 바꾸지 않았습니다. 다시 불러와 확인한 뒤 지정해주세요.", "The subject area of entity {0} changed after you opened this screen. Nothing was changed. Reload, check and classify again.");
        add("{0} 번호가 올바르지 않습니다.", "The {0} ID is invalid.");
        add("주제 영역 {0} 이(가) 올바르지 않습니다.", "Subject area {0} is invalid.");
        add("주제 영역 {0} 의 상위 영역이 올바르지 않습니다.", "The parent of subject area {0} is invalid.");
        add("주제 영역 {0} 의 상위 영역이 순환합니다.", "The parents of subject area {0} form a cycle.");
        add("주제 영역 {0} 이(가) {1}단계를 넘습니다.", "Subject area {0} exceeds {1} levels.");
        add("같은 상위 영역 아래 주제 영역 이름이 겹칩니다 : {0}", "Subject area names overlap under the same parent: {0}");
        add("엔터티의 주제 영역 소속 {0} 이(가) 올바르지 않습니다.", "Subject area membership {0} of an entity is invalid.");
        add("엔터티 하나가 주제 영역 여러 개에 속할 수 없습니다 : {0}", "An entity cannot belong to more than one subject area: {0}");
        add("형식 버전 {0} 파일에는 주제 영역이 들어갈 수 없습니다.", "A format version {0} file cannot contain subject areas.");
        add("형식 버전 {0} 파일에는 주제 영역 배치가 들어갈 수 없습니다.", "A format version {0} file cannot contain subject area layouts.");
        add("주제 영역 배치가 너무 많습니다.", "There are too many subject area layouts.");
        add("주제 영역 배치의 영역 번호가 없거나 겹칩니다.", "A subject area layout has a missing or duplicate subject area ID.");
        add("주제 영역의 상위 영역이 올바르지 않습니다.", "The parent of a subject area is invalid.");

        // ---------------------------------------------------------------- 논리 모델 슈퍼타입·서브타입 (M-6 후속 5차)
        add("같은 슈퍼타입의 서브타입 그룹 이름이 겹칩니다 : {0}", "Subtype group names overlap under the same supertype: {0}");
        add("같은 엔터티를 서브타입으로 두 번 지정할 수 없습니다.", "The same entity cannot be specified as a subtype twice.");
        add("관계 {0} → {1} : 부모 엔터티가 슈퍼타입·서브타입 구조에 속해 FK 를 만들지 않습니다 (상속 구조의 물리 변환은 지원하지 않습니다).", "Relationship {0} → {1}: no FK is created because the parent entity belongs to a supertype/subtype structure (physical conversion of inheritance structures is not supported).");
        add("관계 {0} 의 속성 짝이 엔터티 {1} 의 상속 주 식별자를 쓰고 있어 상속을 바꿀 수 없습니다. 속성 짝을 먼저 해제해주세요.", "The inheritance cannot be changed because the attribute pairs of relationship {0} use the inherited primary identifier of entity {1}. Remove the attribute pairs first.");
        add("루트 슈퍼타입 {0} 에 주 식별자가 없어 서브타입 {1} 을(를) 승인할 수 없습니다.", "Subtype {1} cannot be approved because root supertype {0} has no primary identifier.");
        add("배타(Disjoint) 그룹에서는 분류 값이 겹칠 수 없습니다 : {0}", "Classification values cannot overlap in a Disjoint group: {0}");
        add("배타(Disjoint) 서브타입 그룹 {0} 의 분류 값이 겹칩니다.", "The classification values of Disjoint subtype group {0} overlap.");
        add("배타성(Disjoint·Overlapping)을 선택해주세요.", "Select the disjointness (Disjoint or Overlapping).");
        add("부모 속성은 부모 엔터티 {0} 의 (상속 포함) 주 식별자 속성이어야 합니다. (ID {1})", "The parent attribute must be a primary identifier attribute (including inherited ones) of parent entity {0}. (ID {1})");
        add("부모 엔터티 {0} 의 루트 슈퍼타입 {1} 에 주 식별자가 없어 속성 짝을 지정할 수 없습니다.", "Attribute pairs cannot be specified because root supertype {1} of parent entity {0} has no primary identifier.");
        add("분류 값", "Classification value");
        add("분류 속성은 슈퍼타입 {0} 의 유효 속성(직접 정의 또는 상속)이어야 합니다.", "The classification attribute must be an effective attribute (own or inherited) of supertype {0}.");
        add("상속 계층의 엔터티 {0} 에 같은 이름의 속성 {1} 이(가) 있습니다.", "Entity {0} in the inheritance hierarchy already has an attribute with the same name: {1}.");
        add("상속은 최대 {0}단계까지 가능합니다 : 엔터티 {1} 이(가) {2}단계가 됩니다.", "Inheritance is limited to {0} levels: entity {1} would be at level {2}.");
        add("상속이 순환합니다 : {0} 은(는) 이미 {1} 의 상위 엔터티입니다.", "The inheritance forms a cycle: {0} is already an ancestor of {1}.");
        add("상위 엔터티 {0} 이(가) 승인되지 않아 서브타입 {1} 을(를) 승인할 수 없습니다. 상위 엔터티를 먼저 승인해주세요.", "Subtype {1} cannot be approved because ancestor entity {0} is not approved. Approve the ancestor entity first.");
        add("서브타입 {0} 에 자체 주 식별자가 있습니다. 서브타입은 슈퍼타입의 주 식별자를 상속합니다.", "Subtype {0} has its own primary identifier. A subtype inherits the primary identifier of its supertype.");
        add("서브타입 {0} 은(는) 자체 주 식별자를 둘 수 없습니다. 루트 슈퍼타입 {1} 의 주 식별자를 상속합니다.", "Subtype {0} cannot have its own primary identifier. It inherits the primary identifier of root supertype {1}.");
        add("서브타입 {0} 의 속성 이름이 상위 엔터티 {1} 의 속성과 겹칩니다 : {2}", "An attribute name of subtype {0} overlaps with an attribute of ancestor entity {1}: {2}");
        add("서브타입 그룹 {0} 의 분류 속성은 슈퍼타입의 유효 속성이어야 합니다.", "The classification attribute of subtype group {0} must be an effective attribute of the supertype.");
        add("서브타입 그룹 {0} 의 분류 속성이 상속 변경 뒤 슈퍼타입 {1} 의 유효 속성이 아니게 됩니다. 그룹의 분류 속성을 먼저 바꿔주세요.", "After the inheritance change, the classification attribute of subtype group {0} would no longer be an effective attribute of supertype {1}. Change the group's classification attribute first.");
        add("서브타입 그룹 {0} 의 분류 속성입니다. 그룹의 분류 속성을 먼저 바꿔주세요.", "This is the classification attribute of subtype group {0}. Change the group's classification attribute first.");
        add("논리 식별자 {0} 의 구성 속성은 같은 엔터티의 속성이어야 합니다 (상속 속성·다른 엔터티 속성 불가).", "The attributes of logical identifier {0} must belong to the same entity (inherited attributes and attributes of other entities are not allowed).");
        add("서브타입 그룹 {0} 의 서브타입은 {1}개 이상이어야 합니다.", "Subtype group {0} must have at least {1} subtypes.");
        add("서브타입 그룹 {0} 의 서브타입이 올바르지 않습니다 (같은 모델의 다른 엔터티·순서·분류 값).", "A subtype of subtype group {0} is invalid (another entity of the same model, order, classification value).");
        add("서브타입 그룹 {0} 이(가) 올바르지 않습니다 (모델·슈퍼타입·이름·배타성·완전성).", "Subtype group {0} is invalid (model, supertype, name, disjointness, completeness).");
        add("서브타입 상속은 최대 {0}단계까지 가능합니다 : {1}", "Subtype inheritance is limited to {0} levels: {1}");
        add("서브타입 상속이 순환합니다 : {0}", "The subtype inheritance forms a cycle: {0}");
        add("서브타입 엔터티를 골라주세요.", "Select a subtype entity.");
        add("서브타입은 슈퍼타입과 같은 논리 모델의 엔터티여야 합니다.", "A subtype must be an entity of the same logical model as the supertype.");
        add("서브타입을 {0}개 이상 지정해주세요.", "Specify at least {0} subtypes.");
        add("슈퍼타입 엔터티를 골라주세요.", "Select a supertype entity.");
        add("슈퍼타입·서브타입 구조의 물리 모델 변환은 현재 지원하지 않습니다.", "Physical model generation for supertype/subtype structures is not supported yet.");
        add("슈퍼타입은 그룹과 같은 논리 모델의 엔터티여야 합니다.", "The supertype must be an entity of the same logical model as the group.");
        add("승인된 엔터티 {0} 의 상속 정의는 바꿀 수 없습니다. 승인을 취소한 뒤 다시 시도해주세요.", "The inheritance definition of approved entity {0} cannot be changed. Revoke the approval and try again.");
        add("승인된 하위 엔터티 {0} 이(가) 있어 {1} 의 승인을 취소할 수 없습니다. 하위 엔터티의 승인을 먼저 취소해주세요.", "The approval of {1} cannot be revoked because approved descendant entity {0} exists. Revoke the descendant's approval first.");
        add("엔터티 {0} 에 자체 주 식별자 {1} 이(가) 있어 서브타입으로 연결할 수 없습니다. 서브타입은 슈퍼타입의 주 식별자를 상속합니다.", "Entity {0} cannot be linked as a subtype because it has its own primary identifier {1}. A subtype inherits the primary identifier of its supertype.");
        add("엔터티 {0} 은(는) 서브타입 그룹 {1} 의 서브타입입니다. 상속을 먼저 해제해주세요.", "Entity {0} is a subtype of subtype group {1}. Remove the inheritance first.");
        add("엔터티 {0} 은(는) 서브타입 그룹 {1} 의 슈퍼타입입니다. 서브타입 그룹을 먼저 삭제해주세요.", "Entity {0} is the supertype of subtype group {1}. Delete the subtype group first.");
        add("엔터티 {0} 은(는) 이미 그룹 {1} 의 서브타입입니다. 엔터티 하나는 하나의 슈퍼타입만 가질 수 있습니다.", "Entity {0} is already a subtype of group {1}. An entity can have only one supertype.");
        add("엔터티 {0} 은(는) 자기 자신의 서브타입이 될 수 없습니다.", "Entity {0} cannot be a subtype of itself.");
        add("엔터티 {0} 의 상속 구조가 올바르지 않아 승인할 수 없습니다.", "Entity {0} cannot be approved because its inheritance structure is invalid.");
        add("엔터티 {0} 의 속성 {1} 이(가) 상위 엔터티 {2} 의 속성 {3} 과(와) 이름이 같아 상속할 수 없습니다.", "Attribute {1} of entity {0} has the same name as attribute {3} of ancestor entity {2}, so it cannot inherit.");
        add("엔터티 하나가 서브타입 그룹 여러 개에 속할 수 없습니다 (단일 상속) : {0}", "An entity cannot belong to more than one subtype group (single inheritance): {0}");
        add("완전성(Total·Partial)을 선택해주세요.", "Select the completeness (Total or Partial).");
        add("저장할 서브타입 그룹이 없습니다.", "There is no subtype group to save.");
        add("형식 버전 {0} 파일에는 서브타입 그룹이 들어갈 수 없습니다.", "A format version {0} file cannot contain subtype groups.");
        add("서브타입 그룹", "Subtype group");
        add("속성 {0} 은(는) 엔터티 {1} 에 정의된 속성입니다. 정의한 엔터티에서 수정해주세요.", "Attribute {0} is defined in entity {1}. Edit it in the entity that defines it.");
        add("식별자 {0} 은(는) 엔터티 {1} 에 정의된 식별자입니다. 정의한 엔터티에서 수정해주세요.", "Identifier {0} is defined in entity {1}. Edit it in the entity that defines it.");
        add("승인된 하위 엔터티 {0} 이(가) 엔터티 {1} 의 정의를 상속하고 있습니다. 하위 엔터티의 승인을 먼저 취소해주세요.", "Approved descendant entity {0} inherits the definition of entity {1}. Revoke the descendant's approval first.");
        add("상위 엔터티 {0} 의 물리 테이블이 없습니다. {0} 을(를) 함께 고르거나 먼저 만들어주세요. (서브타입 {1})", "Supertype entity {0} has no physical table. Select {0} as well or create it first. (subtype {1})");
        add("루트 엔터티 {0} 에 주 식별자가 없어 상속 PK 를 만들 수 없습니다.", "Root entity {0} has no primary identifier, so the inherited PK cannot be created.");
        add("루트 엔터티 {0} 의 주 식별자 속성이 테이블 {1} 의 컬럼에 매핑되지 않아 상속 FK 의 참조 컬럼을 정할 수 없습니다.", "The primary identifier attributes of root entity {0} are not mapped to columns of table {1}, so the referenced columns of the inheritance FK cannot be determined.");
        add("그룹 {0}({1}) 의 배타(Disjoint) 조건은 만들 PK·FK 로 보장되지 않습니다 : 같은 키가 여러 서브타입 테이블에 들어갈 수 있습니다.", "The disjoint condition of group {0} ({1}) is not enforced by the generated PK/FKs: the same key can appear in several subtype tables.");
        add("그룹 {0}({1}) 의 완전(Total) 조건은 만들 PK·FK 로 보장되지 않습니다 : 서브타입 행이 없는 {1} 행이 있을 수 있습니다.", "The total condition of group {0} ({1}) is not enforced by the generated PK/FKs: {1} rows without any subtype row can exist.");
        add("계획한 FK 를 만들지 못해 생성 전체를 되돌렸습니다 : {0}", "The planned foreign keys could not be created, so the whole generation was rolled back: {0}");
        add("다른 사용자가 계획이 참조하는 기존 테이블을 바꾸는 중입니다. 잠시 뒤 다시 미리보기해주세요.", "Another user is changing an existing table the plan refers to. Preview again in a moment.");
        add("반영할 수 없는 계획입니다. 미리보기를 다시 확인해주세요.", "This plan cannot be applied. Review the preview again.");
        add("상속 PK 매핑 {0} : 같은 엔터티 매핑의 같은 속성이 두 번 있습니다.", "Inherited PK mapping {0}: the same attribute appears twice in the same entity mapping.");
        add("상속 PK 매핑 {0} 의 속성·컬럼을 가져오지 못했습니다.", "The attribute or column of inherited PK mapping {0} could not be imported.");
        add("상속 PK 매핑 {0} 의 속성이 루트 엔터티의 주 식별자 속성이 아닙니다.", "The attribute of inherited PK mapping {0} is not a primary identifier attribute of the root entity.");
        add("상속 PK 매핑 {0} 의 엔터티가 서브타입이 아닙니다.", "The entity of inherited PK mapping {0} is not a subtype.");
        add("상속 PK 매핑 {0} 의 컬럼이 다른 매핑에 이미 쓰였습니다.", "The column of inherited PK mapping {0} is already used by another mapping.");
        add("상속 PK 매핑 {0} 의 컬럼이 매핑된 테이블의 컬럼이 아닙니다.", "The column of inherited PK mapping {0} does not belong to the mapped table.");
        add("상속 PK 매핑 {0} 이(가) 올바르지 않습니다 (엔터티 매핑·속성·컬럼).", "Inherited PK mapping {0} is invalid (entity mapping, attribute or column).");
        add("상속 PK 매핑 내용이 없습니다.", "The inherited PK mapping is empty.");
        add("상속 PK 매핑이 있는 엔터티 매핑은 테이블을 바꿀 수 없습니다. 상속 PK 매핑을 먼저 해제해주세요.", "The table of an entity mapping with inherited PK mappings cannot be changed. Remove the inherited PK mappings first.");
        add("상속 PK 매핑이 있는 엔터티 매핑입니다. 상속 PK 매핑을 먼저 해제해주세요.", "This entity mapping has inherited PK mappings. Remove the inherited PK mappings first.");
        add("상속 PK 컬럼과 부모 키 컬럼의 데이터 타입이 FK 로 묶일 수 없습니다.", "The data types of the inherited PK columns and the parent key columns cannot be linked by a foreign key.");
        add("상속 PK 컬럼이나 부모 키 컬럼을 정할 수 없습니다.", "The inherited PK columns or the parent key columns cannot be determined.");
        add("상위 엔터티 {0} 는 테이블 {1} 에 매핑되어 있지만 루트 주 식별자 전체의 상속 PK 매핑이 없어 그 테이블의 PK 를 확인할 수 없습니다. 상속 PK 매핑을 먼저 등록해주세요.", "Supertype entity {0} is mapped to table {1}, but not every root primary identifier attribute has an inherited PK mapping, so that table's PK cannot be verified. Register the inherited PK mappings first.");
        add("상위 엔터티 쪽 물리 구조가 맞지 않아 이 테이블의 상속 경로를 확인할 수 없습니다.", "The physical structure of a supertype does not match, so the inheritance path of this table cannot be verified.");
        add("서브타입 {0} 의 상속 FK(직계 슈퍼타입 {1} 참조)를 만들 수 없어 만들지 않습니다 : {2}", "The inheritance FK of subtype {0} (referencing its direct supertype {1}) cannot be created, so it is not created: {2}");
        add("서브타입 테이블 컬럼 {0} 의 상속 PK 매핑에서 사용 중인 속성입니다. 상속 PK 매핑을 먼저 해제해주세요.", "The attribute is used by the inherited PK mapping of subtype table column {0}. Remove the inherited PK mapping first.");
        add("속성 {0} 은(는) 이미 컬럼 {1} 에 상속 PK 매핑되어 있습니다.", "Attribute {0} already has an inherited PK mapping to column {1}.");
        add("속성 {0} 의 상속 PK 매핑에 쓰인 컬럼입니다. 상속 PK 매핑을 먼저 해제해주세요.", "The column is used by the inherited PK mapping of attribute {0}. Remove the inherited PK mapping first.");
        add("엔터티 {0} 은(는) 서브타입이 아니라 상속 PK 매핑을 등록할 수 없습니다.", "Entity {0} is not a subtype, so inherited PK mappings cannot be registered.");
        add("엔터티 {0} 은(는) 지금 서브타입이 아닙니다. 상속 PK 매핑을 해제할 수 있습니다.", "Entity {0} is no longer a subtype. You can remove its inherited PK mappings.");
        add("엔터티 {0} 의 루트 주 식별자 속성만 상속 PK 로 연결할 수 있습니다.", "Only the root primary identifier attributes of entity {0} can be linked as the inherited PK.");
        add("컬럼 {0} 은(는) 이미 속성 {1} 에 매핑되어 있어 상속 PK 매핑에 쓸 수 없습니다.", "Column {0} is already mapped to attribute {1}, so it cannot be used for an inherited PK mapping.");
        add("컬럼 {0} 은(는) 이미 속성 {1} 의 상속 PK 매핑에 쓰이고 있습니다.", "Column {0} is already used by the inherited PK mapping of attribute {1}.");
        add("테이블 {0} 에 직계 상위 테이블을 상속 PK 그대로 참조하는 FK 가 없어 상속 경로를 확인할 수 없습니다. 기존 테이블은 고치지 않습니다. (엔터티 {1})", "Table {0} has no foreign key that references its direct supertype table with the inherited PK, so the inheritance path cannot be verified. Existing tables are not changed. (entity {1})");
        add("테이블 {0} 의 PK 가 엔터티 {1} 의 주 식별자 매핑 컬럼과 같지 않아 상속 FK 로 참조할 수 없습니다. 기존 테이블은 고치지 않습니다.", "The PK of table {0} differs from the columns mapped to the primary identifier of entity {1}, so it cannot be referenced by the inheritance FK. Existing tables are not changed.");
        add("테이블 {0} 의 상속 PK 컬럼 타입이 상위 테이블의 키 컬럼과 FK 로 묶일 수 없습니다. 기존 테이블은 고치지 않습니다. (엔터티 {1})", "The inherited PK column types of table {0} cannot be linked by a foreign key to the key columns of the supertype table. Existing tables are not changed. (entity {1})");
        add("형식 버전 {0} 파일에는 상속 PK 매핑이 들어갈 수 없습니다.", "A format version {0} file cannot contain inherited PK mappings.");
        add("관계 {0} → {1} : 부모 {0} 의 기존 테이블에 상속 PK 매핑이 없어 상속 PK 컬럼을 정할 수 없습니다. FK 없이 만듭니다.", "Relationship {0} → {1}: the existing table of parent {0} has no inherited PK mapping, so the inherited PK columns cannot be determined. It is created without the FK.");
        add("PK 컬럼 순서({0})가 루트 주 식별자 순서({1})와 다릅니다. 상속 FK 는 식별자 순서로 짝지으므로 구조 판정은 바꾸지 않습니다.", "The PK column order ({0}) differs from the root primary identifier order ({1}). The inheritance FK pairs columns in identifier order, so the structure result is not changed.");
        add("PK 컬럼 순서가 루트 주 식별자 순서와 같습니다.", "The PK column order matches the root primary identifier order.");
        add("루트 엔터티 {0} 에 주 식별자가 없어 상속 PK 를 판단할 수 없습니다.", "Root entity {0} has no primary identifier, so the inherited PK cannot be determined.");
        add("루트 주 식별자 {0}개 중 {1}개만 상속 PK 매핑되어 있습니다. 매핑하지 않은 {2}개를 등록해야 구조를 확인할 수 있습니다.", "Only {1} of the {0} root primary identifier attributes have inherited PK mappings. Register the remaining {2} to check the structure.");
        add("루트 주 식별자 {0}개가 모두 상속 PK 매핑되어 있습니다.", "All {0} root primary identifier attributes have inherited PK mappings.");
        add("매핑된 컬럼이 모두 테이블 {0} 의 컬럼입니다.", "All mapped columns belong to table {0}.");
        add("배타(Disjoint)", "Disjoint");
        add("상속 PK {0}개 중 {1}개 연결", "{1} of {0} inherited PK attributes linked");
        add("상속 PK 또는 직계 상위 테이블의 키를 확정할 수 없어 타입과 부모 FK 를 비교하지 않았습니다.", "The inherited PK or the key of the direct supertype table could not be determined, so the types and the parent FK were not compared.");
        add("상속 PK 매핑이 완전하지 않아 물리 PK 구성을 비교하지 않았습니다.", "The inherited PK mapping is incomplete, so the physical PK composition was not compared.");
        add("상속 PK 컬럼 {0} 의 길이·정밀도가 상위 키와 다릅니다. FK 로는 묶일 수 있습니다.", "The length or precision of inherited PK column {0} differs from the parent key. They can still be linked by a foreign key.");
        add("상속 PK 컬럼 {0} 이(가) NULL 을 허용합니다.", "Inherited PK column {0} allows NULL.");
        add("상속 PK 컬럼 타입이 직계 상위 테이블 {0} 의 키와 FK 로 묶일 수 없습니다.", "The inherited PK column types cannot be linked by a foreign key to the key of the direct supertype table {0}.");
        add("상속 PK 컬럼 타입이 직계 상위 테이블의 키와 FK 로 묶일 수 있습니다.", "The inherited PK column types can be linked by a foreign key to the key of the direct supertype table.");
        add("상속 PK 컬럼이 모두 NOT NULL 입니다.", "All inherited PK columns are NOT NULL.");
        add("상속 PK 컬럼이 직계 상위 테이블 {0} 이(가) 아닌 테이블 {1} 을(를) 참조합니다.", "The inherited PK columns reference table {1}, not the direct supertype table {0}.");
        add("상속 그룹 {0} 의 {1} 규칙은 PK·FK 로 보장되지 않습니다 (물리 미보장). 상속 PK·FK 구조 정합성과 따로 보며 불일치로 세지 않습니다.", "The {1} rule of inheritance group {0} is not enforced by PKs and FKs (not physically enforced). It is considered separately from the inherited PK/FK structure and is not counted as a mismatch.");
        add("상위 상속 경로가 유효합니다.", "The ancestor inheritance path is valid.");
        add("엔터티 {0} 은(는) 서브타입이 아니라 상속 PK 매핑 대상이 아닙니다.", "Entity {0} is not a subtype, so inherited PK mappings do not apply.");
        add("엔터티가 지금 서브타입이 아닌데 남은 상속 PK 매핑입니다. 매핑 관리에서 해제할 수 있습니다.", "An inherited PK mapping left on an entity that is no longer a subtype. You can remove it in mapping management.");
        add("완전(Total)", "Total");
        add("유효하지 않은 기존 상속 PK 매핑", "Invalid existing inherited PK mapping");
        add("지금 루트 주 식별자에 없는 속성의 기존 상속 PK 매핑이 {0}건 있습니다. 구조 판정에는 쓰지 않으며 해제할 수 있습니다.", "There are {0} existing inherited PK mapping(s) for attributes that are no longer in the root primary identifier. They are not used for the structure result and can be removed.");
        add("지금 루트 주 식별자에 없는 속성의 기존 상속 PK 매핑입니다. 매핑 관리에서 해제할 수 있습니다.", "An existing inherited PK mapping for an attribute that is no longer in the root primary identifier. You can remove it in mapping management.");
        add("직계 상위 엔터티 {0} 은(는) 테이블 {1} 에 매핑되어 있습니다.", "Direct supertype entity {0} is mapped to table {1}.");
        add("직계 상위 엔터티 {0} 이(가) 물리 테이블에 매핑되지 않아 상속 FK 를 확인할 수 없습니다.", "Direct supertype entity {0} is not mapped to a physical table, so the inheritance FK cannot be checked.");
        add("직계 상위 테이블 {0} 을(를) 상속 PK 그대로 참조하는 FK 가 있습니다.", "There is a foreign key that references the direct supertype table {0} with the inherited PK.");
        add("테이블 PK 가 상속 PK 매핑 컬럼과 같습니다.", "The table PK matches the inherited PK mapping columns.");
        add("테이블 {0} 에 속하지 않은 컬럼이 상속 PK 매핑에 있습니다.", "An inherited PK mapping uses a column that does not belong to table {0}.");
        add("테이블 {0} 에 직계 상위 테이블 {1} 을(를) 참조하는 FK 가 없습니다.", "Table {0} has no foreign key that references the direct supertype table {1}.");
        add("테이블 {0} 에서 {1} 을(를) 참조하는 FK 가 있지만 컬럼 쌍이 상속 PK 전체와 같지 않습니다 (일부 누락 또는 다른 컬럼).", "Table {0} has a foreign key referencing {1}, but its column pairs differ from the whole inherited PK (some missing or different columns).");
        add("테이블 {0} 의 PK 구성({1})이 상속 PK 매핑 컬럼({2})과 다릅니다.", "The PK of table {0} ({1}) differs from the inherited PK mapping columns ({2}).");
        add("같은 대상이 두 번 들어 있습니다. ({0} ID {1})", "The same target is included twice. ({0} ID {1})");
        add("같은 스키마에 추천 테이블명과 이름이 같은 테이블 {0} 이(가) 이미 있습니다.", "Table {0} with the recommended table name already exists in the same schema.");
        add("같은 테이블에 추천 컬럼명과 이름이 같은 컬럼 {0} 이(가) 이미 있습니다.", "Column {0} with the recommended column name already exists in the same table.");
        add("다른 사용자가 같은 모델·테이블·컬럼을 바꾸는 중입니다. 잠시 뒤 다시 미리보기해주세요.", "Another user is changing the same model, tables or columns. Preview again in a moment.");
        add("대상 ID 가 비어 있습니다.", "The target ID is empty.");
        add("대상 종류(TABLE·COLUMN)가 비어 있습니다.", "The target type (TABLE or COLUMN) is empty.");
        add("물리명 추천 일괄 적용 직전 상태 (테이블 {0}개, 컬럼 {1}개, 요청 {2})", "State before applying recommended physical names ({0} tables, {1} columns, request {2})");
        add("바꿀 컬럼명이 비어 있습니다.", "The new column name is empty.");
        add("컬럼명을 입력해주세요.", "Enter the column name.");
        add("{0} : 컬럼명이 비어 있는 행이 있습니다.", "{0} : a row has an empty column name.");
        add("표준용어 {0} 에 영문약어가 없어 컬럼명을 만들 수 없습니다.", "The standard term {0} has no English abbreviation, so no column name can be made.");
        add("엔터티명을 승인된 표준 단어로 나눌 수 없어 추천 테이블명이 없습니다.", "There is no recommended table name because the entity name cannot be split into approved standard words.");
        add("원본 DB 와 매핑된 테이블의 컬럼입니다. 이름을 바꾸면 원본 DB 의 컬럼명과 달라지고, 확정 컬럼 매핑이 없으면 원본 비교에서 원본 전용·모델 전용 컬럼으로 나올 수 있습니다. 원본 DB 는 바꾸지 않습니다.", "This column belongs to a table mapped to a source database. After renaming, it differs from the source column name and, without a confirmed column mapping, may appear as source-only and model-only columns in the source comparison. The source database is not changed.");
        add("원본 DB 와 매핑된 테이블입니다. 이름을 바꾸면 원본 DB 의 이름과 달라지고, 원본 비교에서 이름이 다르게 보이거나 원본 전용·모델 전용 항목으로 나올 수 있습니다. 원본 DB 는 바꾸지 않습니다.", "This table is mapped to a source database. After renaming, it differs from the source name and may show a different name or appear as source-only and model-only items in the source comparison. The source database is not changed.");
        add("이름 변경 계획", "Rename plan");
        add("이름을 바꾸면 DDL 이 만드는 제약조건명에 문제가 생깁니다 : {0}", "Renaming causes a problem in a constraint name generated by the DDL: {0}");
        add("이름을 바꿀 대상을 고르세요.", "Choose the targets to rename.");
        add("적용할 수 없는 항목이 있습니다. 미리보기의 차단 사유를 확인하고 그 항목을 뺀 뒤 다시 미리보기해주세요. ({0} : {1})", "Some items cannot be applied. Check the blocking reasons in the preview, remove those items and preview again. ({0}: {1})");
        add("추천 이름이 바뀌었습니다 (요청 {0}, 지금 {1}). 목록을 다시 불러와 확인해주세요.", "The recommended name has changed (requested {0}, now {1}). Reload the list and check again.");
        add("추천 이름이 비어 있습니다. ({0} ID {1})", "The recommended name is empty. ({0} ID {1})");
        add("컬럼을 찾을 수 없습니다. (ID {0})", "Column not found. (ID {0})");
        add("컬럼이 논리 속성에 매핑되지 않아 추천 컬럼명이 없습니다.", "There is no recommended column name because the column is not mapped to a logical attribute.");
        add("컬럼이 속한 테이블이 승인되어 있어 컬럼 이름을 바꿀 수 없습니다. 테이블 승인을 취소한 뒤 적용할 수 있습니다.", "The column cannot be renamed because its table is approved. Revoke the table approval before applying.");
        add("테이블을 찾을 수 없습니다. (ID {0})", "Table not found. (ID {0})");
        add("테이블이 논리 엔터티에 매핑되지 않아 추천 테이블명이 없습니다.", "There is no recommended table name because the table is not mapped to a logical entity.");
        add("한 번에 {0}개까지 적용할 수 있습니다. (요청 {1}개)", "Up to {0} items can be applied at once. ({1} requested)");
        add("같은 스키마에서 함께 고른 다른 테이블도 같은 이름을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.", "Other selected tables of the same schema get the same name: {0}. Recommendations with the same name cannot be applied together.");
        add("같은 테이블에서 함께 고른 다른 컬럼도 같은 이름을 받습니다 : {0}. 추천끼리 겹치는 이름은 함께 적용할 수 없습니다.", "Other selected columns of the same table get the same name: {0}. Recommendations with the same name cannot be applied together.");
        add("FK 의 컬럼 순서가 관계 속성 짝 순서와 다릅니다.", "The column order of the foreign key differs from the order of the relationship attribute pairs.");
        // 자동 매핑 후보·ERD 매핑 표시 (M-7)
        add("엔터티를 찾을 수 없습니다.", "The entity could not be found.");
        add("매핑 표시는 한 번에 {0}개까지 조회할 수 있습니다.", "Up to {0} objects can be looked up at once for the mapping display.");
        add("UNIQUE 에 보조 식별자에 없는 컬럼이 더 있습니다.", "The UNIQUE constraint has columns that are not in the alternate identifier.");
        add("UNIQUE 에 보조 식별자의 일부 컬럼이 빠져 있습니다.", "The UNIQUE constraint is missing some columns of the alternate identifier.");
        add("UNIQUE 의 일부 컬럼이 논리 속성에 매핑되지 않아 대응 보조 식별자를 찾을 수 없습니다.", "Some columns of the UNIQUE constraint are not mapped to logical attributes, so no matching alternate identifier can be found.");
        add("UNIQUE 의 컬럼 구성이 보조 식별자와 다릅니다.", "The columns of the UNIQUE constraint differ from the alternate identifier.");
        add("UNIQUE 의 컬럼 순서가 보조 식별자의 속성 순서와 다릅니다.", "The column order of the UNIQUE constraint differs from the attribute order of the alternate identifier.");
        add("같은 구성의 FK 가 다른 논리 관계에 이미 대응합니다. 같은 엔터티 사이에 같은 속성 짝의 관계가 둘 이상입니다.", "A foreign key with the same columns already corresponds to another logical relationship. There are two or more relationships with the same attribute pairs between the same entities.");
        add("같은 컬럼의 FK 가 부모 엔터티의 테이블 {0} 이(가) 아닌 {1} 을(를) 참조합니다.", "A foreign key on the same columns references {1} instead of the parent entity's table {0}.");
        add("관계 속성 짝(부모 식별자 ↔ 자식 속성)이 없어 FK 컬럼과 비교할 수 없습니다.", "The relationship has no attribute pairs (parent identifier ↔ child attribute), so it cannot be compared with foreign key columns.");
        add("관계 속성 짝에 대응하는 물리 FK 가 없습니다. 논리 → 물리 생성이나 물리 모델에서 FK 를 만들 수 있습니다 (자동으로 만들지 않습니다).", "There is no physical foreign key for the relationship attribute pairs. You can create it with logical → physical generation or in the physical model (it is not created automatically).");
        add("관계 속성 짝의 부모 컬럼이 부모 테이블의 PK 와 같지 않아 물리 FK 를 만들 대상이 아닙니다 (FK 가 없는 것을 오류로 보지 않습니다).", "The parent columns of the attribute pairs are not the parent table's PK, so this relationship is not a target for a physical foreign key (a missing foreign key is not treated as an error).");
        add("관계 속성 짝의 일부 속성이 물리 컬럼에 매핑되지 않아 FK 와 비교할 수 없습니다.", "Some attributes of the relationship pairs are not mapped to physical columns, so it cannot be compared with a foreign key.");
        add("관계 속성 짝이 부모 주 식별자의 일부만 이어 FK 와 비교할 수 없습니다.", "The attribute pairs cover only part of the parent's primary identifier, so it cannot be compared with a foreign key.");
        add("관계 속성 짝이 지금 부모 주 식별자와 맞지 않아 FK 와 비교할 수 없습니다.", "The attribute pairs do not match the parent's current primary identifier, so it cannot be compared with a foreign key.");
        add("보조 식별자에 대응하는 UNIQUE 제약조건이 없습니다 (UNIQUE 인덱스는 비교하지 않습니다, 자동으로 만들지 않습니다).", "There is no UNIQUE constraint for the alternate identifier (UNIQUE indexes are not compared, nothing is created automatically).");
        add("보조 식별자와 일부 컬럼이 겹치는 UNIQUE 가 여러 개라 어느 것에 대응하는지 정할 수 없습니다.", "Several UNIQUE constraints share some columns with the alternate identifier, so the matching one cannot be determined.");
        add("보조 식별자의 일부 속성이 물리 컬럼에 매핑되지 않아 UNIQUE 와 비교할 수 없습니다.", "Some attributes of the alternate identifier are not mapped to physical columns, so it cannot be compared with a UNIQUE constraint.");
        add("보조 식별자의 컬럼이 테이블 PK 와 같아 UNIQUE 와 따로 비교하지 않습니다.", "The columns of the alternate identifier are the table's PK, so it is not compared with UNIQUE constraints separately.");
        add("부모 엔터티에 주 식별자가 없어 FK 와 비교할 수 없습니다.", "The parent entity has no primary identifier, so it cannot be compared with a foreign key.");
        add("부모 테이블로의 FK 가 여러 개이고 관계 속성 짝과 같은 것이 없어 어느 FK 에 대응하는지 정할 수 없습니다.", "There are several foreign keys to the parent table and none matches the attribute pairs, so the corresponding foreign key cannot be determined.");
        add("부모 테이블로의 FK 가 있지만 컬럼 구성이 관계 속성 짝과 다릅니다.", "There is a foreign key to the parent table, but its columns differ from the relationship attribute pairs.");
        add("엔터티 {0} 이(가) 물리 테이블에 매핑되지 않아 FK 와 비교할 수 없습니다.", "Entity {0} is not mapped to a physical table, so it cannot be compared with a foreign key.");
        add("엔터티가 물리 테이블에 매핑되지 않아 UNIQUE 와 비교할 수 없습니다.", "The entity is not mapped to a physical table, so it cannot be compared with a UNIQUE constraint.");
        add("이 FK 에 대응하는 논리 관계({0} → {1})가 없습니다. 물리에만 있는 구조입니다 (오류로 보지 않으며 자동으로 지우지 않습니다).", "There is no logical relationship ({0} → {1}) for this foreign key. It exists only in the physical model (not treated as an error and never deleted automatically).");
        add("이 UNIQUE 에 대응하는 논리 보조 식별자가 없습니다. 물리에만 있는 구조입니다 (오류로 보지 않으며 자동으로 지우지 않습니다).", "There is no logical alternate identifier for this UNIQUE constraint. It exists only in the physical model (not treated as an error and never deleted automatically).");
        add("자기 참조 관계의 자식 속성이 부모 키 속성 자신이라 물리 FK 를 만들 대상이 아닙니다.", "The child attributes of the self-referencing relationship are the parent key attributes themselves, so it is not a target for a physical foreign key.");
        add("참조받는 테이블 {0} 이(가) 이 논리 모델의 엔터티에 매핑되지 않아 대응 논리 관계를 찾을 수 없습니다.", "The referenced table {0} is not mapped to an entity of this logical model, so no corresponding logical relationship can be found.");
    }
}
