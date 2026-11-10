package com.koboolean.metamora.common.i18n;

import java.util.HashMap;
import java.util.Map;

/**
 * 영어 문구 : 한국어 원문 → 영어 ({@link Messages})
 * <p>
 * 서버 메시지를 추가하면 여기에 같은 원문을 키로 영어를 넣는다. (없으면 영어 화면에도 한국어로 나간다)
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
        // ---------------------------------------------------------------- ErrorCode
        add("알수없는 오류가 발생하였습니다.", "An unknown error occurred.");
        add("패스워드가 일치하지 않습니다.", "The password does not match.");
        add("엑셀 생성이 실패하였습니다.", "Failed to create the Excel file.");
        add("등록된 아이디가 존재합니다.", "This username is already registered.");
        add("비밀번호는 8자 이상, 대문자 및 특수문자를 각각 하나 이상 포함해야 합니다.",
                "The password must be at least 8 characters long and include at least one uppercase letter and one special character.");
        add("아이디는 5~20자 사이의 소문자, 숫자, 밑줄(_)로 구성할 수 있습니다. 단, 밑줄은 처음과 끝에 사용할 수 없으며 연속된 밑줄도 허용되지 않습니다.",
                "The username must be 5 to 20 characters of lowercase letters, numbers and underscores (_). It cannot start or end with an underscore or contain consecutive underscores.");
        add("동일한 형식의 표준 용어와 표준 도메인이 존재합니다. 중복된 용어는 등록이 불가능합니다.",
                "A standard term with the same standard domain already exists. Duplicate terms cannot be registered.");
        add("해당 용어를 찾을 수 없습니다.", "The term could not be found.");
        add("등록 용어중 매핑되지 않는 단어가 존재합니다.", "Some words of the term are not mapped to standard words.");
        add("표준단어명과 표준단어영문약어명에는 '_' 를 쓸 수 없습니다. ('_' 는 표준용어의 단어 구분자입니다)",
                "Standard word names and their English abbreviations cannot contain '_'. ('_' separates the words of a standard term)");
        add("표준용어명과 표준용어영문약어명의 단어 수('_' 기준)가 다릅니다. (예: 사용자_이름 / USER_NM)",
                "The standard term name and its English abbreviation have a different number of words (separated by '_'). (e.g. 사용자_이름 / USER_NM)");
        add("테이블 한글명 중 승인된 표준 단어가 아닌 단어가 존재합니다.", "The Korean table name contains words that are not approved standard words.");
        add("테이블 한글명을 입력해주세요.", "Enter the Korean table name.");
        add("테이블명 접두어는 영문으로 시작하고 영문/숫자/_ 만 사용할 수 있습니다. (최대 20자)",
                "The table name prefix must start with a letter and contain only letters, numbers and _. (up to 20 characters)");
        add("승인된 정보는 삭제할 수 없습니다.", "Approved data cannot be deleted.");
        add("사용 중인 정보는 삭제할 수 없습니다.", "Data that is in use cannot be deleted.");
        add("해당 도메인을 찾을 수 없습니다.", "The domain could not be found.");
        add("권한이 없어 작업을 수행할 수 없습니다.", "You do not have permission to perform this action.");
        add("저장된 데이터가 존재합니다.", "The data already exists.");
        add("업로드한 엑셀에 같은 테이블의 같은 컬럼명이 중복되어 있습니다.", "The uploaded Excel file contains the same column name more than once for a table.");
        add("컬럼으로 등록된 정보가 존재하기 때문에 삭제가 불가능합니다.", "It cannot be deleted because it is registered as a column.");
        add("등록된 테이블 정보가 존재하지 않습니다.", "The table is not registered.");
        add("NULL 허용여부, 필수 입력 여부, 민감정보 여부, PK 여부, 고유값 여부, 인덱스 생성 여부, 암호화 필요 여부는 Y 또는 N 값이어야합니다.",
                "Nullable, required, sensitive, PK, unique, create index and needs encryption must be Y or N.");
        add("등록된 표준용어 정보가 존재하지 않습니다.", "The standard term is not registered.");
        add("승인된 정보는 수정이 불가능합니다.", "Approved data cannot be edited.");
        add("입력하신 표준도메인분류명을 찾을 수 없습니다.", "The standard domain category you entered could not be found.");
        add("관리자는 변경이 불가능합니다.", "Managers cannot be changed.");
        add("아이디 또는 비밀번호가 잘못되었습니다.", "The username or password is incorrect.");
        add("해당 아이디로 접근 가능한 프로젝트가 없습니다.", "There are no projects this account can access.");
        add("공통 컬럼 세트는 프로젝트 관리자만 변경할 수 있습니다.", "Only project managers can change the common column set.");
        add("공통 컬럼 세트에 같은 컬럼명이 중복되어 있습니다.", "The common column set contains the same column name more than once.");
        add("공통 컬럼 세트에 등록할 수 있는 개수를 넘었습니다.", "The common column set has too many columns.");
        add("초기 비밀번호를 사용 중입니다. 비밀번호를 변경한 뒤 로그인해주세요.", "You are using the initial password. Change your password and sign in again.");
        add("등록된 스키마 정보가 존재하지 않습니다.", "The schema is not registered.");
        add("스키마명은 영문 또는 _ 로 시작하고 영문/숫자/_/$ 만 사용할 수 있습니다. (최대 63자)",
                "The schema name must start with a letter or _ and contain only letters, numbers, _ and $. (up to 63 characters)");
        add("관계·인덱스·제약조건 정의가 올바르지 않습니다.", "The relation, index or constraint definition is invalid.");
        add("ERD 조회 조건 또는 배치 정보가 올바르지 않습니다.", "The ERD query condition or layout is invalid.");
        add("다른 창에서 배치가 먼저 저장되었습니다. 저장된 배치를 다시 불러온 뒤 저장해주세요.",
                "The layout was saved in another window first. Load the saved layout again and then save.");
        add("새 비밀번호는 현재 비밀번호 및 아이디와 달라야 합니다.", "The new password must be different from your current password and username.");

        // ---------------------------------------------------------------- 인증 / 권한 / 공통
        add("로그인이 필요합니다.", "Login is required.");
        add("접근 권한이 없습니다.", "You do not have access.");
        add("보안 토큰이 만료되었거나 올바르지 않습니다. 다시 시도해주세요.", "The security token has expired or is invalid. Please try again.");
        add("세션이 만료되었습니다. 다시 로그인해주세요.", "Your session has expired. Please sign in again.");
        add("이미 다른 곳에서 로그인되어 있습니다. 기존 세션에서 로그아웃하거나 세션이 만료된 뒤 다시 시도해주세요.",
                "You are already signed in elsewhere. Sign out of the existing session or try again after it expires.");
        add("선택한 프로젝트에 접근할 수 없습니다.", "You cannot access the selected project.");
        add("현재 프로젝트에 더 이상 접근할 수 없습니다. 다시 로그인해주세요.", "You can no longer access the current project. Please sign in again.");
        add("존재하지 않는 프로젝트입니다.", "The project does not exist.");
        add("요청한 리소스를 찾을 수 없습니다.", "The requested resource could not be found.");

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
        add("{0} 에 테이블이 있습니다.", "{0} contains tables.");
        add("삭제를 할 수 없습니다.", "It cannot be deleted.");
        add("프로젝트 관리자 외의 멤버가 존재하여 삭제가 불가능합니다.", "The project cannot be deleted because it has members other than the project manager.");
        add("프로젝트 관리자 외는 삭제가 불가능합니다.", "Only the project manager can delete the project.");
        add("기본 프로젝트는 삭제가 불가능합니다.", "The default project cannot be deleted.");

        // ---------------------------------------------------------------- 물리 모델 (관계 / 인덱스 / 제약조건)
        add("relation / index / constraint 중 하나입니다.", "It must be one of relation / index / constraint.");
        add("관계 컬럼 쌍을 1개 이상 지정해주세요.", "Specify at least one pair of relation columns.");
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
    }
}
