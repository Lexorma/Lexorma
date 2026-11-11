package com.koboolean.metamora.common.i18n;

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

        // ---------------------------------------------------------------- 공지사항
        // V0 기본 안내 공지 : 제목·본문이 V0__init.sql 과 한 글자라도 다르면 영어판이 연결되지 않는다. (V0SeedMigrationTest 에서 확인)
        add("[안내] Metamora에 오신 것을 환영합니다.", "[Notice] Welcome to Metamora");
        add("""
                안녕하세요. **Metamora**를 이용해 주셔서 감사합니다.

                Metamora는 데이터베이스 모델링과 데이터 표준화, 메타데이터 관리를 지원하는 오픈소스 데이터 관리 플랫폼입니다. 데이터 사전과 테이블·컬럼 정보를 관리하고, 물리 모델의 구조와 관계를 시각적으로 확인할 수 있습니다.

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
                - 기본 프로젝트 `META-MORA`의 예제 데이터 사전과 테이블·컬럼, 물리 ERD를 확인해 보세요.
                - 일부 기능은 사용자 역할과 프로젝트 권한에 따라 이용이 제한됩니다.
                - 데이터와 메타데이터를 변경하기 전에 해당 프로젝트의 운영 정책을 확인해 주세요.
                - 기능과 화면 구성은 업데이트 과정에서 변경될 수 있습니다.

                ## 문의 및 오류 신고

                | 용도 | 링크 |
                |------|------|
                | 프로젝트 저장소 및 이용 문서 | https://github.com/meta-mora/metamora |
                | 오류 신고·기능 제안·프로젝트 관련 문의 (GitHub Issues) | https://github.com/meta-mora/metamora/issues |

                > 오류를 신고할 때에는 사용 환경과 재현 순서, 기대한 결과와 실제 결과를 함께 알려 주세요. 비밀번호와 개인정보 등 민감한 정보는 포함하지 마세요.
                > 설치 환경의 계정·권한·운영 정책에 관한 문의는 해당 환경의 관리자에게 확인해 주세요.

                ## 안내 사항

                Metamora는 MIT 라이선스로 공개된 프로젝트입니다. 현재 제공하는 기능과 향후 계획은 저장소의 문서를 확인해 주세요.
                서비스 이용 중 발견한 문제점이나 개선 의견은 프로젝트 발전에 도움이 됩니다.

                감사합니다.
                **Metamora**""", """
                Hello, and thank you for using **Metamora**.

                Metamora is an open-source data management platform for database modeling, data standardization and metadata management. You can manage a data dictionary together with table and column information, and view the structure and relationships of your physical model visually.

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
                - Take a look at the sample data dictionary, tables, columns and physical ERD in the default project `META-MORA`.
                - Some features are limited depending on your role and project permissions.
                - Check the operating policy of the project before changing data or metadata.
                - Features and screens may change with updates.

                ## Questions and bug reports

                | Purpose | Link |
                |---------|------|
                | Project repository and documentation | https://github.com/meta-mora/metamora |
                | Bug reports, feature requests and questions (GitHub Issues) | https://github.com/meta-mora/metamora/issues |

                > When reporting a bug, please include your environment, the steps to reproduce it, and the expected and actual results. Do not include sensitive information such as passwords or personal data.
                > For questions about accounts, permissions or operating policies of your installation, contact the administrator of that environment.

                ## About

                Metamora is released under the MIT License. See the documentation in the repository for the current features and future plans.
                Any problems or suggestions you share while using the service help the project grow.

                Thank you.
                **Metamora**""");

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
    }
}
