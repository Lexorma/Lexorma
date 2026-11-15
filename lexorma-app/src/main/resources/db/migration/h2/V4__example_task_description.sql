-- 예제 프로젝트(PRJ_SN 0, LEXORMA)의 테이블·컬럼에 업무 설명(TASK_EXPLN)을 채운다 (2026-11-15, docs/completed/ai-dictionary-draft-ai5.md).
-- 예제는 Lexorma 자신의 스키마를 모델링한 것이라, 각 테이블·컬럼이 Lexorma 에서 하는 일을 업무 설명으로 적는다.
-- 업무 설명이 비어 있는 행만 채운다 (TASK_EXPLN is null) : 사용자가 이미 적은 업무 설명은 그대로 둔다. 다른 프로젝트·다른 컬럼은 건드리지 않는다.
-- 수정 일시(MDFCN_DT)는 바꾸지 않는다. 마이그레이션 쓰기라 모델 변경 이력에는 남지 않는다 (V1 의 예제 데이터와 같다).
-- 증분 마이그레이션 : 이미 적용된 V0~V3 는 고치지 않는다.

-- ---------- 테이블 ----------
update TB_TBL set TASK_EXPLN = 'Lexorma 에 로그인하는 사용자 계정. 아이디·암호화된 비밀번호·이름과 활성 상태를 두며, 역할은 계정 역할로 연결한다. 역할은 계정 전체에 적용되고 프로젝트별로 다르지 않다.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '권한 역할 목록. ADMIN > MANAGER > DBA > USER > VIEWER > NOT_APPROVE 순서의 계층으로, 상위 역할은 하위 역할의 권한을 모두 가진다.' where PRJ_SN = 0 and TBL_NM = 'TB_ROLE' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '계정에 부여한 역할. 한 계정은 여러 역할을 가질 수 있고 역할 계층에 따라 가장 높은 역할의 권한이 적용된다.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT_ROLE' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '데이터 표준과 모델을 나누어 관리하는 작업 단위. 로그인할 때 프로젝트를 고르며, 표준 사전·물리/논리 모델·출처 접속 등 대부분의 데이터는 프로젝트별로 분리된다. 프로젝트 0 은 Lexorma 자신을 모델링한 예제(LEXORMA)이다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '프로젝트에 참여하는 계정 목록. 회원으로 등록되고 활성인 계정만 그 프로젝트로 로그인할 수 있으며, 요청마다 다시 확인한다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ_MBR' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'API 호출 기록. 요청 주소·방식·사용자·응답 상태를 남기며, 인증·출처·AI 관련 API 는 요청·응답 본문 대신 [PROTECTED] 를 저장하고 그 밖의 본문도 비밀번호 등 민감한 값을 가려서 저장한다. 시스템 로그 화면에서 조회한다.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '게시물(공지사항) 분류 코드 목록.' where PRJ_SN = 0 and TBL_NM = 'TB_PST_CTGRY' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '공지사항 게시물. 지정한 프로젝트들의 사용자에게 보이며, 시스템이 등록한 기본 안내 공지는 요청 언어에 맞춰 한국어·영어로 보여 주고 수정할 수 없다.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '게시물을 읽은 계정 기록. 사용자가 이미 본 게시물인지 판단해 조회 표시에 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_PST_ACNT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '게시물에 첨부한 파일 정보. 파일 자체는 서버 저장 경로에 두고 이 테이블에는 이름과 경로만 둔다.' where PRJ_SN = 0 and TBL_NM = 'TB_ATCH_FILE' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '표준 도메인. 같은 성격의 값(금액, 일시, 명, 코드 등)이 가질 자료 유형·길이·형식을 한 번 정해 두고 표준 용어가 이를 참조해 컬럼 타입을 통일한다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '표준 단어. 용어를 이루는 최소 의미 단위로 한글명·영문명·영문 약어를 정하며, 표준 용어와 물리명은 단어의 조합으로 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '표준 단어의 이음동의어. 같은 뜻의 다른 표현을 등록해 두면 용어를 찾거나 만들 때 표준 단어로 안내한다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD_SYNM' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '표준 단어의 금칙어. 표준 대신 쓰면 안 되는 표현을 등록해 용어 작성 때 걸러 낸다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD_PRHBT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '표준 용어. 표준 단어를 조합해 만든 업무 용어로, 한글명·영문 약어(물리 컬럼명)·도메인을 정한다. 컬럼과 논리 속성이 표준 용어에 연결되어 이름과 타입이 표준을 따르는지 검사한다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '표준 용어의 이음동의어. 같은 뜻의 다른 용어명을 등록해 검색·추천에 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_SYNM' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '표준 용어를 이루는 표준 단어와 그 순서. 단어가 바뀌었을 때 영향을 받는 용어를 찾는 데도 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_WORD' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리 모델의 스키마(네임스페이스). 테이블과 뷰는 스키마에 속하며 같은 스키마 안에서 이름이 겹칠 수 없다.' where PRJ_SN = 0 and TBL_NM = 'TB_SCHM' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리 모델의 테이블. 이름·코멘트·업무 설명과 승인 상태를 두며, 컬럼·관계·인덱스·제약조건이 테이블에 속한다. 변경은 모델 변경 이력에 남고 DDL·ERD·버전 비교의 기준이 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리 모델의 컬럼. 자료 유형·길이·NULL 허용·기본값·키 여부와 연결된 표준 용어를 두며, 표준 용어와 비교해 이름·타입이 표준을 따르는지 검사한다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '공통 컬럼 세트. 등록·수정 일시처럼 여러 테이블에 똑같이 붙는 컬럼을 표준 용어로 정해 두고, 컬럼 편집 화면에서 한 번에 추가한다. 프로젝트 관리자·MANAGER·ADMIN 이 관리한다.' where PRJ_SN = 0 and TBL_NM = 'TB_CMN_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리 테이블 사이의 관계. FK 는 DDL 에 외래키로 나가고, LOGICAL 은 ERD 에만 표시하는 논리적 연결이다. 사용자가 직접 만들거나 컬럼의 참조 테이블, 출처 가져오기로 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '관계를 이루는 컬럼 쌍. 복합키 관계는 컬럼 순서대로 여러 행을 둔다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리 테이블의 인덱스. 사용자가 직접 만들거나 컬럼의 인덱스 여부로 자동 생성하며 DDL 에 CREATE INDEX 로 나간다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '인덱스를 이루는 컬럼 또는 표현식과 그 순서·정렬 방향.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '기본키·외래키 밖의 테이블 제약조건 (UNIQUE, CHECK). DDL 에 제약조건으로 나간다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'UNIQUE 제약조건을 이루는 컬럼과 그 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리 ERD 화면 배치. 사용자별로 테이블 위치와 화면 위치·배율을 저장해 다시 열 때 그대로 보여 준다.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '논리 모델. 엔터티·속성·식별자·관계를 묶는 단위로, 프로젝트에 여러 개 둘 수 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_MDL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '논리 엔터티. 업무에서 관리할 대상을 DBMS 와 무관하게 표현하며, 엔터티 테이블 매핑으로 물리 테이블과 연결한다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '논리 속성. 엔터티가 가지는 업무 항목으로, 표준 용어에 연결해 이름과 자료 유형을 표준에 맞춘다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '논리 식별자. 엔터티의 행을 구분하는 속성 묶음으로, 기본 식별자는 물리 모델의 기본키에 해당한다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '식별자를 이루는 속성과 그 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR_ATRB' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '논리 엔터티 사이의 관계. 식별/비식별 여부와 양쪽 카디널리티(최소·최대 수)를 둔다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '논리 ERD 화면 배치. 논리 모델·사용자별로 엔터티 위치와 화면 위치·배율을 저장한다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '논리 엔터티와 물리 테이블의 매핑. 매핑 화면에서 논리·물리 모델의 대응과 누락을 확인한다.' where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '엔터티 테이블 매핑 안에서 논리 속성과 물리 컬럼의 대응.' where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리·논리 모델의 변경 이력. 저장할 때마다 바뀐 객체와 항목의 변경 전·후 값을 자동으로 남기며, 한 번의 저장 작업에서 바뀐 것은 하나의 변경 그룹으로 묶인다. 추가만 하고 고치지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '이름을 붙여 저장한 모델 버전. 그 시점의 프로젝트 전체 물리·논리 모델을 바꿀 수 없는 JSON 스냅샷으로 보관하고, 버전끼리 또는 현재 모델과 목록·ERD 로 비교한다. 출처 가져오기를 적용하기 직전에도 자동으로 만든다 (IMPORT-요청 아이디).' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_VER' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처(원본) DB 접속 정보. 프로젝트별로 등록하며 Lexorma 가 JDBC 로 직접 접속해 메타데이터를 수집한다. JDBC URL 은 받지 않고 호스트·포트·DB 명으로 서버가 만들며, 비밀번호는 암호화해 저장하고 화면에는 설정 여부만 보여 준다. 실제 접속은 외부 접속 규칙이 허용해야 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 카탈로그의 스키마. 수집으로 확인한 출처 DB 의 스키마로, 물리 모델의 스키마와는 별개이다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 카탈로그의 테이블·뷰. 수집한 사실을 그대로 두며 물리 모델의 테이블을 바꾸지 않는다. 출처 COMMENT 는 여기에만 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 카탈로그의 컬럼. 출처 DB 가 알려 준 자료 유형·길이·NULL 허용·기본값·COMMENT 를 그대로 둔다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 카탈로그의 제약조건 (기본키, UNIQUE, CHECK). 외래키는 출처 관계에 둔다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 제약조건을 이루는 컬럼과 그 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 카탈로그의 인덱스.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 인덱스를 이루는 컬럼 또는 표현식과 그 순서·정렬 방향.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 카탈로그의 외래키 관계. 참조 대상이 수집 범위 밖이면 이름으로만 기록한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 외래키를 이루는 컬럼 쌍.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 메타데이터 수집 실행. 시작 → 결과 준비 → 반영 단계를 거치며, 완료된 실행만 접속별 반영 순서를 받아 그 시점의 카탈로그 기준이 된다. 스키마 하나라도 수집하지 못하면 실행 전체가 실패한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '수집 실행의 스키마별 대상과 결과.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 스키마의 시점별 버전. 직전 확정 버전과 내용이 달라진 수집에서만 새 버전을 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 테이블의 시점별 버전. 정의가 바뀐 테이블만 새 버전을 만들고(copy-on-write), 출처 비교·가져오기가 이 버전을 기준으로 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 객체별 변경 이력. 수집할 때 직전 확정 버전과 비교해 추가·변경·삭제된 스키마·테이블·컬럼·제약조건·인덱스·관계를 남긴다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '승인된 인덱스의 변경 요청. 변경 전·후 정의를 두고 승인 권한자가 승인하면 반영하고 반려하면 그대로 둔다. 인덱스마다 승인 대기 요청은 하나뿐이다.' where PRJ_SN = 0 and TBL_NM = 'TB_IDX_CHG_DMND' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '물리 모델의 뷰. 정의(SELECT 문)와 출력 컬럼, 참조하는 테이블·뷰를 두며 DDL 에 CREATE VIEW 로 나간다.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '뷰가 내보내는 출력 컬럼.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '뷰 정의가 참조하는 테이블·뷰. 모델에 있는 객체는 번호로, 없는 객체는 이름으로 기록해 영향 분석에 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '승인된 뷰의 변경 요청. 변경 전·후 정의를 두고 승인 권한자가 승인하면 반영한다.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_CHG_DMND' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '프로젝트의 가져오기 기준 출처 접속. 출처 비교·가져오기는 이 접속의 카탈로그를 물리 모델과 비교한다. 프로젝트마다 하나이다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ_SRC_CNTN' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 스키마와 모델 스키마의 대응. 출처 테이블을 모델의 어느 스키마와 비교·가져올지 정한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 테이블과 모델 테이블의 대응. 출처 비교·가져오기·다시 가져오기가 이 매핑을 기준으로 같은 객체를 찾는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '출처 가져오기 적용 기록. 어느 수집 시점의 출처를 어떤 항목으로 모델에 반영했는지 요약해 두고, 다시 가져오기는 이 기록과 모델 변경 이력으로 지난번 가져온 값을 되살려 비교한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IMPRT_EXCN' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '외부 연결 허용 규칙. 출처 DB(JDBC)·AI 서버(AI_HTTP) 연결은 기본적으로 막혀 있고, 종류·호스트·주소 대역·포트가 모두 맞는 활성 규칙이 있어야 열린다. 시스템 관리자만 관리한다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '외부 연결 허용 규칙의 변경 이력. 누가 언제 어떤 규칙을 바꿨는지 감사용으로 남긴다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE_HSTRY' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '외부 연결 시도의 감사 기록. 허용·차단 판정과 실제 연결 결과를 남기며 인증 정보는 남기지 않는다. 기록에 실패하면 연결하지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'Lexorma 가 호출하는 AI 서버 설정. 현재 Ollama 만 지원하며 인증 정보는 두지 않는다. 저장만으로는 연결하지 않고, 호출할 때마다 이 행으로 클라이언트를 새로 만든다. 시스템 관리자만 관리한다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'Lexorma 전체 AI 설정. 행은 항상 하나이며 시스템 기본 AI 제공자를 가리킨다. 비어 있으면 AI 기능을 쓰지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_STNG' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'AI 가 만든 테이블·컬럼 업무 설명 제안. 근거·불확실한 부분·확인 질문·생성 정보와 검토 상태를 함께 두며, 승인하기 전에는 업무 설명을 바꾸지 않는다. 승인할 때 대상이 그사이 바뀌었는지 다시 확인한다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'AI 설명 제안의 생성·검토 이력. 누가 언제 어떤 처리를 했는지 추가만 하고 고치거나 지우지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = '여러 테이블·컬럼의 AI 설명 제안을 비동기로 만드는 일괄 실행. 프로젝트마다 한 번에 하나만 돌며, 서버가 다시 시작되면 남은 실행은 중단(INTERRUPTED)으로 표시한다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'AI 설명 품질 평가의 기준 데이터. 평가할 테이블·컬럼과 범주, 담당자가 확인한 실제 업무 의미를 둔다. 실제 의미는 AI 에 보내지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and TASK_EXPLN is null;
update TB_TBL set TASK_EXPLN = 'AI 설명 제안 하나에 대한 사람의 판정. 업무적 정확성, 근거 없는 주장, 누락 정보, 불확실성 처리, 표준 추천의 적절성, 검토 시간을 남기며 모델별 품질 지표의 원천이다. AI 가 판정하지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and TASK_EXPLN is null;

-- ---------- 컬럼 (테이블별) ----------
-- TB_ACNT
update TB_COL set TASK_EXPLN = '계정을 구분하는 내부 번호. 프로젝트 회원·ERD 레이아웃·수집 실행 등 계정을 가리키는 곳에서 이 번호로 참조한다.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '로그인할 때 입력하는 아이디. 계정마다 하나이며 감사 컬럼(등록자·수정자 아이디)에 남는 값이기도 하다.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT' and COL_NM = 'USER_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '로그인 비밀번호의 해시 값. 원문은 저장하지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT' and COL_NM = 'PSWD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '화면에 표시하는 사용자 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT' and COL_NM = 'USER_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '계정 사용 가능 여부. 비활성 계정은 로그인할 수 없다.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT' and COL_NM = 'ACTVTN_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '비밀번호를 바꿨는지 여부. 기본 관리자 계정이 아직 바꾸지 않았으면(거짓) 로그인 뒤 내 정보 화면으로 보내 비밀번호부터 바꾸게 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT' and COL_NM = 'PSWD_CHG_YN' and TASK_EXPLN is null;
-- TB_ROLE
update TB_COL set TASK_EXPLN = '역할을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_ROLE' and COL_NM = 'ROLE_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'Spring Security 역할 이름 (ROLE_ADMIN, ROLE_MANAGER 등). 화면 메뉴·버튼과 API 권한 검사가 이 이름을 기준으로 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_ROLE' and COL_NM = 'ROLE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '역할의 짧은 설명 (예: Administrator, Manager).' where PRJ_SN = 0 and TBL_NM = 'TB_ROLE' and COL_NM = 'ROLE_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '역할을 표현식으로 해석할지 표시하는 예비 값. 기본 역할에는 값이 없다.' where PRJ_SN = 0 and TBL_NM = 'TB_ROLE' and COL_NM = 'EXPR_YN' and TASK_EXPLN is null;
-- TB_ACNT_ROLE
update TB_COL set TASK_EXPLN = '역할을 받은 계정.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT_ROLE' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '계정에 부여한 역할.' where PRJ_SN = 0 and TBL_NM = 'TB_ACNT_ROLE' and COL_NM = 'ROLE_SN' and TASK_EXPLN is null;
-- TB_PRJ
update TB_COL set TASK_EXPLN = '프로젝트를 구분하는 내부 번호. 프로젝트에 속한 모든 데이터가 이 번호로 범위를 나눈다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'PRJ_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '로그인 화면과 프로젝트 관리에서 보이는 프로젝트 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'PRJ_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '프로젝트 관리자 계정. 관리자는 역할과 별개로 이 프로젝트의 승인 작업과 출처 접속 관리를 할 수 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'MNGR_ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '프로젝트 사용 여부. 비활성 프로젝트로는 로그인할 수 없고, 사용 중이던 세션은 다음 요청에서 끊긴다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'ACTVTN_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '등록·수정한 표준 사전·모델 항목을 승인 절차 없이 바로 승인된 상태로 둘지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'AUTO_APRV_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '데이터 사전에서 영문 약어를 사용할지 여부. 표준 단어의 약어로 용어·컬럼의 영문명을 만들 때 적용한다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'DIC_ABBR_USE_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '표준 단어로 테이블 물리명을 만들 때 앞에 붙이는 접두어 (예: TB_). 대문자로 정규화하고 끝에 _ 를 붙이며, 비어 있으면 접두어를 쓰지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'TBL_PFX_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 프로젝트 모델의 대상 DBMS (POSTGRESQL, ORACLE, MYSQL, MARIADB). 테이블 DDL 생성 기준이며 지정하지 않으면 PostgreSQL 이다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ' and COL_NM = 'DB_TYPE_CD' and TASK_EXPLN is null;
-- TB_PRJ_MBR
update TB_COL set TASK_EXPLN = '프로젝트 회원 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ_MBR' and COL_NM = 'PRJ_MBR_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '프로젝트에 참여하는 계정.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ_MBR' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '회원 자격의 활성 여부. 비활성으로 바꾸면 그 계정은 이 프로젝트에 접근할 수 없고 사용 중인 세션도 끊긴다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ_MBR' and COL_NM = 'ACTVTN_YN' and TASK_EXPLN is null;
-- TB_LOG
update TB_COL set TASK_EXPLN = '로그 행을 구분하는 번호. IDENTITY 로 자동 생성한다.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'LOG_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '호출한 API 경로.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'DMND_URL_ADDR' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'HTTP 메서드 (GET, POST, PUT, DELETE 등).' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'DMND_MTHD_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청을 보낸 클라이언트 IP 주소.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'CNTN_IP_ADDR' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '호출한 사용자 아이디. 로그인 전 요청이면 비어 있을 수 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'USER_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '호출 당시 사용자의 역할.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'ROLE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'HTTP 응답 상태 코드 (200, 403, 409 등).' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'RSPNS_STTS_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청 본문. 보호 대상 API 는 [PROTECTED] 로, 나머지는 민감한 값을 가린 뒤 저장한다.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'DMND_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '응답 본문. 보호 대상 API 는 [PROTECTED] 로, 나머지는 민감한 값을 가린 뒤 저장한다.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'RSPNS_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리 중 발생한 오류 내용.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'ERR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청을 보낸 브라우저·클라이언트의 User-Agent 값.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'USER_AGNT_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청을 처리한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_LOG' and COL_NM = 'LOG_DT' and TASK_EXPLN is null;
-- TB_PST_CTGRY
update TB_COL set TASK_EXPLN = '게시물 범주를 구분하는 코드.' where PRJ_SN = 0 and TBL_NM = 'TB_PST_CTGRY' and COL_NM = 'CTGRY_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '화면에 표시하는 범주 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_PST_CTGRY' and COL_NM = 'CTGRY_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '범주 목록의 표시 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_PST_CTGRY' and COL_NM = 'SORT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '범주를 선택 목록에 보여 줄지 여부 (Y/N).' where PRJ_SN = 0 and TBL_NM = 'TB_PST_CTGRY' and COL_NM = 'USE_YN' and TASK_EXPLN is null;
-- TB_PST
update TB_COL set TASK_EXPLN = '게시물을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'PST_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '게시물 범주.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'CTGRY_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '게시물을 작성한 사용자 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'WRTR_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '게시물 제목.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'PST_TTL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '게시물 본문 (Markdown).' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'PST_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '삭제한 게시물인지 여부 (Y/N). 삭제하면 목록에서 보이지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'DEL_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '작성자가 게시물 내용을 마지막으로 고친 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'PST_MDFCN_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '게시물을 보여 줄 프로젝트 번호 목록.' where PRJ_SN = 0 and TBL_NM = 'TB_PST' and COL_NM = 'PRJ_SN_LIST' and TASK_EXPLN is null;
-- TB_PST_ACNT
update TB_COL set TASK_EXPLN = '읽은 게시물.' where PRJ_SN = 0 and TBL_NM = 'TB_PST_ACNT' and COL_NM = 'PST_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '게시물을 읽은 계정.' where PRJ_SN = 0 and TBL_NM = 'TB_PST_ACNT' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
-- TB_ATCH_FILE
update TB_COL set TASK_EXPLN = '첨부 파일을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_ATCH_FILE' and COL_NM = 'FILE_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '파일을 첨부한 게시물.' where PRJ_SN = 0 and TBL_NM = 'TB_ATCH_FILE' and COL_NM = 'PST_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '서버에 저장한 파일 이름. 이름 충돌을 피하려고 원본 파일 이름과 다르게 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_ATCH_FILE' and COL_NM = 'FILE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '사용자가 올린 원래 파일 이름. 내려받을 때 이 이름을 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_ATCH_FILE' and COL_NM = 'ORGNL_FILE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '파일 확장자.' where PRJ_SN = 0 and TBL_NM = 'TB_ATCH_FILE' and COL_NM = 'FILE_EXTN_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '서버에서 파일을 저장한 경로.' where PRJ_SN = 0 and TBL_NM = 'TB_ATCH_FILE' and COL_NM = 'FILE_PATH_NM' and TASK_EXPLN is null;
-- TB_STD_DMN
update TB_COL set TASK_EXPLN = '표준 도메인을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'STD_DMN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '도메인의 제정 차수. 표준을 개정할 때마다 늘린다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'ENCT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '도메인을 묶는 상위 그룹 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'DMN_GROUP_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '도메인 분류 이름 (명, 코드, 일시, 여부 등). 표준 단어의 도메인 분류와 맞춰 용어에 쓸 수 있는 도메인을 고른다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'DMN_CLSF_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '도메인 이름 (예: 명V100, 내용T). 표준 용어가 이 이름으로 도메인을 고른다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'DMN_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '도메인의 의미와 사용 기준 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'DMN_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 도메인을 쓰는 컬럼의 자료 유형 (VARCHAR, BIGINT, TIMESTAMP 등).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'DATA_TYPE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '자료 길이. 문자형은 최대 길이, 숫자형은 전체 자릿수.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'DATA_LEN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '숫자형의 소수점 이하 자릿수.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'DATA_DCPT_LEN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 에 저장하는 값의 형식 (예: YYYYMMDD).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'STRG_FRMT_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '화면·보고서에 표시하는 값의 형식 (예: YYYY-MM-DD).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'INDCT_FRMT_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '값의 단위 (원, %, 건 등).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'UNIT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '허용하는 값의 범위나 목록.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'ALWNC_VL_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 도메인인지 여부. 승인 전 도메인은 표준으로 확정되지 않은 상태이다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_DMN' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
-- TB_STD_WORD
update TB_COL set TASK_EXPLN = '표준 단어를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'STD_WORD_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '단어의 제정 차수.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'ENCT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '표준 단어의 한글 이름 (예: 계정, 일련번호).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'WORD_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '물리명에 쓰는 영문 약어 (예: ACNT, SN).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'WORD_ENG_ABBR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '단어의 영문 전체 이름 (예: Account).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'WORD_ENG_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '단어의 의미와 사용 기준 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'WORD_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '형식 단어(분류어)인지 여부. 명·코드·일시처럼 용어 끝에 와서 값의 형식을 정하는 단어이다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'FRMT_WORD_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '형식 단어가 가리키는 도메인 분류. 이 단어로 끝나는 용어는 같은 분류의 도메인을 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'DMN_CLSF_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '물리명을 만들 때 영문 약어를 쓸지 여부. 쓰지 않으면 영문 전체 이름을 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'ABBR_USE_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 단어인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
-- TB_STD_WORD_SYNM
update TB_COL set TASK_EXPLN = '이음동의어가 가리키는 표준 단어.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD_SYNM' and COL_NM = 'STD_WORD_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '표준 단어와 같은 뜻으로 쓰이는 다른 표현.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD_SYNM' and COL_NM = 'SYNM_NM' and TASK_EXPLN is null;
-- TB_STD_WORD_PRHBT
update TB_COL set TASK_EXPLN = '금칙어 대신 써야 하는 표준 단어.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD_PRHBT' and COL_NM = 'STD_WORD_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '사용을 금지하는 단어.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_WORD_PRHBT' and COL_NM = 'PRHBT_WORD_NM' and TASK_EXPLN is null;
-- TB_STD_TRM
update TB_COL set TASK_EXPLN = '표준 용어를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'STD_TRM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '용어의 제정 차수.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'ENCT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '표준 용어 이름. 단어를 _ 로 이어 쓴다 (예: 계정_일련번호).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'TRM_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '용어의 업무 의미 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'TRM_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '용어의 영문 약어. 이 용어에 연결한 컬럼의 물리명이 된다 (예: ACNT_SN).' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'TRM_ENG_ABBR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '공공 행정표준코드와 연결되는 용어일 때 그 코드 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'PBADMS_STD_CD_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '용어를 관리하는 소관 기관 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'JRSDC_INST_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '용어가 따르는 표준 도메인. 연결된 컬럼의 자료 유형·길이를 정한다.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'STD_DMN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 용어인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
-- TB_STD_TRM_SYNM
update TB_COL set TASK_EXPLN = '이음동의어가 가리키는 표준 용어.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_SYNM' and COL_NM = 'STD_TRM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '표준 용어와 같은 뜻으로 쓰이는 다른 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_SYNM' and COL_NM = 'SYNM_NM' and TASK_EXPLN is null;
-- TB_STD_TRM_WORD
update TB_COL set TASK_EXPLN = '용어-단어 연결 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_WORD' and COL_NM = 'STD_TRM_WORD_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '단어로 구성되는 표준 용어.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_WORD' and COL_NM = 'STD_TRM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '용어를 이루는 표준 단어.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_WORD' and COL_NM = 'STD_WORD_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '용어 안에서 단어가 놓이는 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_STD_TRM_WORD' and COL_NM = 'SORT_ORD' and TASK_EXPLN is null;
-- TB_SCHM
update TB_COL set TASK_EXPLN = '스키마를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SCHM' and COL_NM = 'SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 에 만들 스키마 이름. DDL 에서 테이블 이름 앞에 붙는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SCHM' and COL_NM = 'SCHM_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마의 용도 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_SCHM' and COL_NM = 'SCHM_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '프로젝트의 기본 스키마인지 여부. 스키마를 고르지 않고 만든 테이블이 여기에 들어간다.' where PRJ_SN = 0 and TBL_NM = 'TB_SCHM' and COL_NM = 'BSC_YN' and TASK_EXPLN is null;
-- TB_TBL
update TB_COL set TASK_EXPLN = '테이블을 구분하는 내부 번호. 이름을 바꿔도 유지되어 변경 이력과 매핑이 이 번호를 따라간다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블이 속한 스키마.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 에 만들 테이블 물리명. 표준 단어 약어와 프로젝트 테이블 접두어로 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'TBL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블 코멘트. 화면에서 테이블 한글 이름으로 보이고 DDL 의 COMMENT ON TABLE 값이 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'TBL_KORN_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블에 대한 짧은 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'TBL_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '다른 테이블이 참조하는 기준 정보(마스터) 테이블인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'MSTR_TBL_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블 목록과 엑셀 내보내기의 표시 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'SORT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 테이블인지 여부. 승인된 테이블은 출처 가져오기가 바꾸지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 에서 가져온 테이블 COMMENT. 출처 가져오기만 쓰며 테이블 코멘트·업무 설명과 따로 관리한다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'SRC_ANTN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '담당자가 확정한 테이블 업무 설명. 직접 수정하거나 AI 설명 제안을 승인해 바꾸며, 출처 가져오기나 다른 화면 저장이 바꾸지 않는다. 동시에 고치면 화면이 읽은 값과 비교해 충돌을 알린다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL' and COL_NM = 'TASK_EXPLN' and TASK_EXPLN is null;
-- TB_COL
update TB_COL set TASK_EXPLN = '컬럼을 구분하는 내부 번호. 이름을 바꿔도 유지된다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼이 속한 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼이 속한 테이블의 물리명. 컬럼 목록·엑셀에서 테이블을 이름으로 보여 주고 찾을 때 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'TBL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 에 만들 컬럼 물리명. 연결된 표준 용어의 영문 약어와 같아야 표준을 따른다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'COL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 설명. 화면에서 컬럼 한글 이름으로 보이고 DDL 의 COMMENT ON COLUMN 값이 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'COL_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼의 자료 유형 (VARCHAR, BIGINT, TIMESTAMP 등). 표준 도메인의 자료 유형과 비교한다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'DATA_TYPE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '문자형 최대 길이 또는 숫자형 자릿수.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'MAX_LEN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '숫자형의 정밀도 (전체 자릿수).' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'DATA_PRCSN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '숫자형의 소수점 이하 자릿수.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'DCPT_LEN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '값이 비어 있어도 되는지 여부. 거짓이면 DDL 에 NOT NULL 이 붙는다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'NULL_ALWNC_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '값을 넣지 않았을 때 DB 가 채우는 기본값 (DDL 의 DEFAULT).' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'BSC_VL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블 안에서 컬럼이 놓이는 순서. DDL 과 화면의 컬럼 순서가 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'SORT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마스터(기준) 데이터를 담는 컬럼인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'MSTR_DATA_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 컬럼이 참조하는 테이블 이름. 값을 넣으면 그 테이블로 가는 참조 관계(관계 출처 REF_COLUMN)를 저장할 때 자동으로 맞춘다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'RFRNC_TBL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '업무상 반드시 값이 있어야 하는 컬럼인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'ESNTL_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '개인정보·비밀번호처럼 보호해야 하는 민감 정보인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'SNSTV_INFO_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '기본키를 이루는 컬럼인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'PK_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '값이 테이블 안에서 겹치면 안 되는 컬럼인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'UNQ_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 컬럼 하나로 인덱스를 만들지 여부. 켜면 컬럼 인덱스를 자동으로 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'IDX_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '값을 암호화해 저장하는 컬럼인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'ENCPT_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 컬럼에 들어가는 값의 예시.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'EXMPL_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 컬럼인지 여부. 승인된 컬럼은 출처 가져오기가 바꾸지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼에 연결한 표준 용어. 이름·타입 표준 검사와 AI 설명 제안의 근거가 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'STD_TRM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 에서 가져온 컬럼 COMMENT. 출처 가져오기만 쓰며 컬럼 설명·업무 설명과 따로 관리한다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'SRC_ANTN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '값 자동 생성 방법. IDENTITY 컬럼이면 ALWAYS 또는 BY_DEFAULT 이고, 아니면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'AUTO_CRT_MTHD_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '담당자가 확정한 컬럼 업무 설명. 직접 수정하거나 AI 설명 제안을 승인해 바꾸며, 출처 가져오기나 다른 화면 저장이 바꾸지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_COL' and COL_NM = 'TASK_EXPLN' and TASK_EXPLN is null;
-- TB_CMN_COL
update TB_COL set TASK_EXPLN = '공통 컬럼을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_CMN_COL' and COL_NM = 'CMN_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '공통 컬럼으로 쓸 표준 용어. 이 용어의 영문 약어와 도메인으로 컬럼을 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_CMN_COL' and COL_NM = 'STD_TRM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '공통 컬럼 세트 안에서의 순서. 테이블에 추가할 때 이 순서로 붙는다.' where PRJ_SN = 0 and TBL_NM = 'TB_CMN_COL' and COL_NM = 'SORT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '추가하는 공통 컬럼의 NULL 허용 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_CMN_COL' and COL_NM = 'NULL_ALWNC_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '추가하는 공통 컬럼에 인덱스를 만들지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_CMN_COL' and COL_NM = 'IDX_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '추가하는 공통 컬럼의 기본값.' where PRJ_SN = 0 and TBL_NM = 'TB_CMN_COL' and COL_NM = 'BSC_VL' and TASK_EXPLN is null;
-- TB_TBL_REL
update TB_COL set TASK_EXPLN = '관계를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'REL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계를 거는 쪽(자식, 외래키를 가진) 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'ORGNL_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조되는 쪽(부모) 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'TRGT_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계 이름. FK 이면 DDL 의 외래키 제약조건 이름이 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'REL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계 종류 (FK : 외래키, LOGICAL : ERD 에만 그리는 논리 관계).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'REL_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계를 만든 경로 (USER : 사용자, REF_COLUMN : 컬럼 참조 테이블로 자동 생성, IMPORTED : 출처 가져오기, AI_APPROVED : AI 승인).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'REL_SRC_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 참조 테이블로 자동 생성한 관계일 때 그 관계를 만든 컬럼. 컬럼당 관계는 하나다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'DRVN_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 관계인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '부모 행을 지울 때의 처리 규칙 (NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT). 대상 DBMS 가 지원하지 않는 규칙은 DDL 생성 때 경고와 함께 빠진다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'DEL_RULE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '부모 키를 바꿀 때의 처리 규칙. 값은 삭제 규칙과 같다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL' and COL_NM = 'MDFCN_RULE_CD' and TASK_EXPLN is null;
-- TB_TBL_REL_COL
update TB_COL set TASK_EXPLN = '관계 컬럼 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL_COL' and COL_NM = 'REL_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 쌍이 속한 관계.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL_COL' and COL_NM = 'REL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '자식 테이블 쪽(외래키) 컬럼.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL_COL' and COL_NM = 'ORGNL_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '부모 테이블 쪽(참조되는 키) 컬럼.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL_COL' and COL_NM = 'TRGT_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '복합 관계에서 컬럼 쌍의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_REL_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
-- TB_TBL_IDX
update TB_COL set TASK_EXPLN = '인덱스를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'IDX_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스를 만든 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 에 만들 인덱스 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'IDX_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스 방식 (예: BTREE, HASH). 비어 있으면 DBMS 기본 방식이며, 대상 DBMS 가 지원하지 않는 방식은 DDL 생성 때 알린다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'IDX_TYPE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '고유 인덱스인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'UNQ_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 인덱스인지 여부. 승인된 인덱스를 고치면 바로 바뀌지 않고 인덱스 변경 요청으로 승인을 기다린다 (승인 권한자의 요청은 즉시 반영).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스를 만든 경로 (USER : 사용자, COLUMN : 컬럼의 인덱스 여부로 자동 생성).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'IDX_SRC_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 인덱스 여부로 자동 생성한 인덱스일 때 그 컬럼. 컬럼당 하나다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'DRVN_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '부분 인덱스의 조건식 (WHERE 절).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX' and COL_NM = 'CND_CN' and TASK_EXPLN is null;
-- TB_TBL_IDX_COL
update TB_COL set TASK_EXPLN = '인덱스 컬럼 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX_COL' and COL_NM = 'IDX_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼이 속한 인덱스.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX_COL' and COL_NM = 'IDX_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스에 들어가는 컬럼. 표현식 인덱스 항목이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX_COL' and COL_NM = 'COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스 안에서 항목의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 대신 쓰는 표현식 (예: lower(USER_ID)).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX_COL' and COL_NM = 'EXPR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '항목의 정렬 방향 (ASC, DESC).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_IDX_COL' and COL_NM = 'SORT_CD' and TASK_EXPLN is null;
-- TB_TBL_CNSTRT
update TB_COL set TASK_EXPLN = '제약조건을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT' and COL_NM = 'CNSTRT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건을 건 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 에 만들 제약조건 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT' and COL_NM = 'CNSTRT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건 종류 (UNIQUE, CHECK).' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT' and COL_NM = 'CNSTRT_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'CHECK 제약조건의 검사식.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT' and COL_NM = 'INSP_EXPR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 제약조건인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
-- TB_TBL_CNSTRT_COL
update TB_COL set TASK_EXPLN = '제약조건 컬럼 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT_COL' and COL_NM = 'CNSTRT_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼이 속한 제약조건.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT_COL' and COL_NM = 'CNSTRT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건에 들어가는 컬럼.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT_COL' and COL_NM = 'COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건 안에서 컬럼의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_TBL_CNSTRT_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
-- TB_ERD_LYT
update TB_COL set TASK_EXPLN = '레이아웃 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and COL_NM = 'ERD_LYT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '레이아웃을 저장한 계정.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블 노드별 좌표 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and COL_NM = 'NODE_PSTN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 본 화면의 X 위치.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and COL_NM = 'SCRN_X_CRDNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 본 화면의 Y 위치.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and COL_NM = 'SCRN_Y_CRDNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 본 화면 배율.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and COL_NM = 'SCRN_MGNF' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '동시에 저장할 때 덮어쓰기를 막는 버전 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_ERD_LYT' and COL_NM = 'VER_NO' and TASK_EXPLN is null;
-- TB_LGCL_MDL
update TB_COL set TASK_EXPLN = '논리 모델을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_MDL' and COL_NM = 'LGCL_MDL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '논리 모델 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_MDL' and COL_NM = 'MDL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '논리 모델의 범위와 목적 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_MDL' and COL_NM = 'MDL_EXPLN' and TASK_EXPLN is null;
-- TB_LGCL_ENTT
update TB_COL set TASK_EXPLN = '엔터티를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT' and COL_NM = 'LGCL_ENTT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '엔터티가 속한 논리 모델.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT' and COL_NM = 'LGCL_MDL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '엔터티 이름 (업무 용어).' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT' and COL_NM = 'ENTT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '엔터티가 나타내는 업무 대상 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT' and COL_NM = 'ENTT_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 엔터티인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ENTT' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
-- TB_LGCL_ATRB
update TB_COL set TASK_EXPLN = '속성을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'LGCL_ATRB_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성이 속한 엔터티.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'LGCL_ENTT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성 이름 (업무 용어).' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'ATRB_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성의 업무 의미 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'ATRB_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성의 논리 자료 유형.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'DATA_TYPE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '업무상 반드시 값이 있어야 하는 속성인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'ESNTL_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '엔터티 안에서 속성의 표시 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'SORT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성에 연결한 표준 용어.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ATRB' and COL_NM = 'STD_TRM_SN' and TASK_EXPLN is null;
-- TB_LGCL_IDNTFR
update TB_COL set TASK_EXPLN = '식별자를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR' and COL_NM = 'LGCL_IDNTFR_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '식별자를 가진 엔터티.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR' and COL_NM = 'LGCL_ENTT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '식별자 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR' and COL_NM = 'IDNTFR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '엔터티의 기본(주) 식별자인지 여부. 나머지는 대체 식별자이다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR' and COL_NM = 'BSC_YN' and TASK_EXPLN is null;
-- TB_LGCL_IDNTFR_ATRB
update TB_COL set TASK_EXPLN = '식별자 속성 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR_ATRB' and COL_NM = 'LGCL_IDNTFR_ATRB_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성이 속한 식별자.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR_ATRB' and COL_NM = 'LGCL_IDNTFR_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '식별자에 들어가는 속성.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR_ATRB' and COL_NM = 'LGCL_ATRB_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '식별자 안에서 속성의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_IDNTFR_ATRB' and COL_NM = 'SORT_ORD' and TASK_EXPLN is null;
-- TB_LGCL_REL
update TB_COL set TASK_EXPLN = '논리 관계를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'LGCL_REL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계가 속한 논리 모델.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'LGCL_MDL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계를 시작하는 쪽 엔터티.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'ORGNL_ENTT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계가 향하는 쪽 엔터티.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'TRGT_ENTT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계 이름 (업무 표현).' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'REL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계의 업무 의미 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'REL_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '식별 관계인지 여부. 식별 관계이면 한쪽 엔터티의 식별자가 다른 쪽 식별자의 일부가 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'IDNTF_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계에서 원본 엔터티 쪽의 최소 참여 수 (카디널리티). 0 이상이다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'ORGNL_MIN_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계에서 원본 엔터티 쪽의 최대 참여 수. 비어 있으면 제한이 없다(N).' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'ORGNL_MAX_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계에서 대상 엔터티 쪽의 최소 참여 수. 0 이상이다.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'TRGT_MIN_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계에서 대상 엔터티 쪽의 최대 참여 수. 비어 있으면 제한이 없다(N).' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'TRGT_MAX_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 관계인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_REL' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
-- TB_LGCL_ERD_LYT
update TB_COL set TASK_EXPLN = '레이아웃 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'LGCL_ERD_LYT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '레이아웃을 저장한 논리 모델.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'LGCL_MDL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '레이아웃을 저장한 계정.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '엔터티 노드별 좌표 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'NODE_PSTN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 본 화면의 X 위치.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'SCRN_X_CRDNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 본 화면의 Y 위치.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'SCRN_Y_CRDNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 본 화면 배율.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'SCRN_MGNF' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '동시에 저장할 때 덮어쓰기를 막는 버전 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_LGCL_ERD_LYT' and COL_NM = 'VER_NO' and TASK_EXPLN is null;
-- TB_ENTT_TBL_MPNG
update TB_COL set TASK_EXPLN = '매핑 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG' and COL_NM = 'ENTT_TBL_MPNG_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 논리 엔터티.' where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG' and COL_NM = 'LGCL_ENTT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '엔터티가 구현된 물리 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_ENTT_TBL_MPNG' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
-- TB_ATRB_COL_MPNG
update TB_COL set TASK_EXPLN = '매핑 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG' and COL_NM = 'ATRB_COL_MPNG_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성-컬럼 매핑이 속한 엔터티-테이블 매핑.' where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG' and COL_NM = 'ENTT_TBL_MPNG_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 논리 속성.' where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG' and COL_NM = 'LGCL_ATRB_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '속성이 구현된 물리 컬럼.' where PRJ_SN = 0 and TBL_NM = 'TB_ATRB_COL_MPNG' and COL_NM = 'COL_SN' and TASK_EXPLN is null;
-- TB_MDL_CHG_HSTRY
update TB_COL set TASK_EXPLN = '이력 행을 구분하는 번호. 늦게 기록된 이력일수록 크다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'MDL_CHG_HSTRY_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '같은 작업(트랜잭션)에서 함께 바뀐 이력을 묶는 아이디. 출처 가져오기 한 번이 한 그룹이 된다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'CHG_GROUP_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바뀐 객체의 종류 (PHYSICAL_TABLE, PHYSICAL_COLUMN, PHYSICAL_RELATION, LOGICAL_ENTITY 등).' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'TRGT_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바뀐 객체의 내부 번호. 객체를 지운 뒤에도 이력은 남는다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'TRGT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 당시 객체의 이름. 이름이 나중에 바뀌어도 이력은 당시 이름으로 보인다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'TRGT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 종류 (CREATE, UPDATE, DELETE, APPROVE, REVOKE).' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'CHG_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 전 항목 값 JSON. 정해진 항목만 기록한다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'CHG_BFR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 후 항목 값 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'CHG_AFTR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 경로 (USER : 화면·엑셀 저장, AUTO_SYNC : 참조 관계 자동 동기화, IMPORT : 출처 가져오기, AI_APPROVAL : AI 설명 제안 승인).' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'CHG_SRC_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경한 사용자 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'USER_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_CHG_HSTRY' and COL_NM = 'CHG_DT' and TASK_EXPLN is null;
-- TB_MDL_VER
update TB_COL set TASK_EXPLN = '모델 버전을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_VER' and COL_NM = 'MDL_VER_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전 이름 (예: 1차 설계 확정). 출처 가져오기 전 자동 버전은 IMPORT- 로 시작한다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_VER' and COL_NM = 'VER_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전에 대한 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_VER' and COL_NM = 'VER_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스냅샷 JSON 형식의 버전. 형식이 바뀌어도 예전 스냅샷을 읽을 수 있게 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_VER' and COL_NM = 'FRMT_VER_NO' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '대상 종류별 객체 수 JSON. 목록에서 모델 내용을 읽지 않고 규모를 보여 준다.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_VER' and COL_NM = 'MDL_CNT_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '모델 전체 스냅샷 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_MDL_VER' and COL_NM = 'MDL_CN' and TASK_EXPLN is null;
-- TB_SRC_CNTN
update TB_COL set TASK_EXPLN = '출처 접속을 구분하는 내부 번호. 출처 카탈로그·매핑·수집 실행이 이 번호로 접속을 가리킨다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '화면에 표시하는 접속 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'CNTN_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DBMS 종류 (POSTGRESQL, ORACLE, MYSQL, MARIADB). JDBC 드라이버는 종류별로 고정이다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'DB_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 서버의 호스트 이름 또는 IP 주소.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'HOST_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 서버의 포트.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'PORT_NO' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '접속할 데이터베이스 이름. Oracle 은 서비스 이름 또는 SID 이다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'DB_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 명의 종류 (DATABASE, SERVICE_NAME, SID). Oracle 접속 URL 을 만들 때 구분한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'DB_NM_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 에 접속하는 계정 이름. 메타데이터를 읽을 권한만 있는 계정을 권장한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'CNTN_USER_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '접속 비밀번호의 암호문 (AES-256-GCM, v1:키아이디:값 형식). 원문은 저장하거나 응답하지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'CNTN_PSWD_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '허용 목록에 있는 접속 옵션 JSON (sslmode / sslMode 만).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'CNTN_OPTN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 객체 이름을 비교하는 방식 (DBMS_DEFAULT : DBMS 규칙대로 대소문자 접기, CASE_SENSITIVE, CASE_INSENSITIVE).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'IDNTFR_CMPR_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '접속에 대한 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'CNTN_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '접속 사용 여부. 비활성 접속으로는 수집하지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNTN' and COL_NM = 'ACTVTN_YN' and TASK_EXPLN is null;
-- TB_SRC_SCHM
update TB_COL set TASK_EXPLN = '출처 스키마를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마를 수집한 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마가 속한 카탈로그(데이터베이스) 이름. DBMS 가 카탈로그를 쓰지 않으면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and COL_NM = 'CTLG_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 스키마 이름 (수집한 그대로).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and COL_NM = 'SCHM_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '카탈로그와 스키마를 합친 경로. 접속 안에서 스키마를 찾는 키이다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and COL_NM = 'PATH_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막 수집에서 출처 DB 에 아직 있었는지 여부. 사라진 스키마도 이력을 위해 행은 남긴다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and COL_NM = 'EXST_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '같은 이름의 스키마가 사라졌다 다시 생길 때마다 늘어나는 생성 차수. 예전 객체와 새 객체를 구분한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM' and COL_NM = 'CRT_CYCL' and TASK_EXPLN is null;
-- TB_SRC_TBL
update TB_COL set TASK_EXPLN = '출처 테이블을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블을 수집한 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블이 속한 출처 스키마.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 테이블 이름 (수집한 그대로).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'TBL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '비교용 이름. DBMS 의 대소문자 규칙에 맞춰 접은 이름으로, 같은 객체인지 판단하는 키이다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'CMPR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '따옴표로 감싼 식별자인지 여부. 수집 결과가 알려 주지 않으면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'QUOT_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 객체 종류 (TABLE, VIEW).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'TBL_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 테이블 COMMENT.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'ANTN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블 정의(컬럼·제약조건·인덱스·관계)의 정규화 해시 (v1: + SHA-256). 바뀌지 않은 테이블은 수집 반영을 건너뛴다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'HASH_VL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막 수집에서 출처 DB 에 아직 있었는지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'EXST_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '같은 이름의 테이블이 사라졌다 다시 생길 때마다 늘어나는 생성 차수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL' and COL_NM = 'CRT_CYCL' and TASK_EXPLN is null;
-- TB_SRC_COL
update TB_COL set TASK_EXPLN = '출처 컬럼을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'SRC_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼이 속한 출처 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 컬럼 이름 (수집한 그대로).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'COL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '비교용 이름. DBMS 의 대소문자 규칙에 맞춰 접은 이름이다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'CMPR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '따옴표로 감싼 식별자인지 여부. 알 수 없으면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'QUOT_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 테이블에서 컬럼의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 가 알려 준 자료 유형 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'DATA_TYPE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '문자형 최대 길이.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'MAX_LEN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '숫자형 정밀도.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'DATA_PRCSN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '숫자형 소수점 이하 자릿수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'DCPT_LEN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 에서 NULL 을 허용하는지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'NULL_ALWNC_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 기본값 표현식 (수집한 그대로).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'BSC_VL_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 컬럼 COMMENT. 물리 모델과 비교하거나 출처 가져오기에서 출처 주석으로 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'ANTN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 정의의 정규화 해시.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'HASH_VL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막 수집에서 출처 DB 에 아직 있었는지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_COL' and COL_NM = 'EXST_YN' and TASK_EXPLN is null;
-- TB_SRC_CNSTRT
update TB_COL set TASK_EXPLN = '출처 제약조건을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT' and COL_NM = 'SRC_CNSTRT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건이 걸린 출처 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 제약조건 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT' and COL_NM = 'CNSTRT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건 종류 (PRIMARY_KEY, UNIQUE, CHECK).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT' and COL_NM = 'CNSTRT_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'CHECK 제약조건의 검사식 (수집한 그대로).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT' and COL_NM = 'INSP_CND_CN' and TASK_EXPLN is null;
-- TB_SRC_CNSTRT_COL
update TB_COL set TASK_EXPLN = '행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT_COL' and COL_NM = 'SRC_CNSTRT_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼이 속한 출처 제약조건.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT_COL' and COL_NM = 'SRC_CNSTRT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건에 들어가는 출처 컬럼.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT_COL' and COL_NM = 'SRC_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제약조건 안에서 컬럼의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CNSTRT_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
-- TB_SRC_IDX
update TB_COL set TASK_EXPLN = '출처 인덱스를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX' and COL_NM = 'SRC_IDX_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스가 있는 출처 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 인덱스 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX' and COL_NM = 'IDX_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 인덱스 방식 (예: btree).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX' and COL_NM = 'IDX_TYPE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '고유 인덱스인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX' and COL_NM = 'UNQ_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '부분 인덱스의 조건식.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX' and COL_NM = 'CND_CN' and TASK_EXPLN is null;
-- TB_SRC_IDX_COL
update TB_COL set TASK_EXPLN = '행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and COL_NM = 'SRC_IDX_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '항목이 속한 출처 인덱스.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and COL_NM = 'SRC_IDX_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스에 들어가는 출처 컬럼. 표현식 항목이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and COL_NM = 'SRC_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '인덱스 안에서 항목의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 대신 쓰인 표현식.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and COL_NM = 'EXPR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '항목의 정렬 방향 (ASC, DESC).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IDX_COL' and COL_NM = 'SORT_CD' and TASK_EXPLN is null;
-- TB_SRC_REL
update TB_COL set TASK_EXPLN = '출처 관계를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'SRC_REL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계를 수집한 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '외래키를 가진 출처 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조되는 출처 테이블. 수집 범위 밖이면 비어 있고 이름 컬럼으로만 남는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'RFRNC_SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조되는 테이블의 스키마 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'RFRNC_SCHM_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조되는 테이블 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'RFRNC_TBL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 DB 의 외래키 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'REL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 외래키의 ON DELETE 규칙. 출처 가져오기가 새 관계를 만들 때 그대로 옮긴다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'DEL_RULE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '출처 외래키의 ON UPDATE 규칙.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'MDFCN_RULE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조되는 테이블의 카탈로그 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL' and COL_NM = 'RFRNC_CTLG_NM' and TASK_EXPLN is null;
-- TB_SRC_REL_COL
update TB_COL set TASK_EXPLN = '행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL_COL' and COL_NM = 'SRC_REL_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼 쌍이 속한 출처 관계.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL_COL' and COL_NM = 'SRC_REL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '외래키 쪽 출처 컬럼.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL_COL' and COL_NM = 'SRC_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조되는 출처 컬럼. 수집 범위 밖이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL_COL' and COL_NM = 'RFRNC_SRC_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조되는 컬럼 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL_COL' and COL_NM = 'RFRNC_COL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '관계 안에서 컬럼 쌍의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_REL_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
-- TB_SRC_CLCT_EXCN
update TB_COL set TASK_EXPLN = '수집 실행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'SRC_CLCT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집한 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행을 요청한 작업의 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'JOB_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행 주체 (USER : 사용자 요청, ASYNC : 비동기 작업, SCHEDULED : 예약 실행).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'EXCN_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행을 요청한 계정. 예약 실행이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집 범위 (FULL : 접속 전체, PARTIAL : 선택한 스키마만).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'CLCT_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행 상태 (CREATED, RUNNING, COMPLETED, FAILED, CANCELLED).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'EXCN_STTS_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '완료된 실행에만 붙는 접속별 반영 순서. 시점을 비교하는 유일한 기준이다 (일련번호나 일시로 비교하지 않는다).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'RFLT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '진행 중인 동안만 채우는 접속 번호. 한 접속에서 수집이 동시에 둘 이상 돌지 않게 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'PRGRS_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행을 맡은 서버 인스턴스의 아이디. 서버가 멈추면 다른 서버가 이 실행을 정리한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'EXCN_SRVR_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행을 시작한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'BGNG_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행 중인 서버가 마지막으로 살아 있음을 알린 일시. 오래 갱신되지 않으면 실패로 정리한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'LAST_IDNTY_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행이 끝난 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'END_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집 결과를 카탈로그에 반영한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'RFLT_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집한 테이블 수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'TBL_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집한 컬럼 수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'COL_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이번 반영으로 새로 만든 테이블·스키마 버전 수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'VER_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이번 반영으로 기록한 출처 변경 이력 수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'CHG_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실패했을 때의 오류 코드.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'ERR_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실패했을 때의 오류 내용. 접속 정보는 남기지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_EXCN' and COL_NM = 'ERR_CN' and TASK_EXPLN is null;
-- TB_SRC_CLCT_TRGT
update TB_COL set TASK_EXPLN = '수집 대상 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'SRC_CLCT_TRGT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '대상이 속한 수집 실행.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'SRC_CLCT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집할 스키마 경로 (카탈로그 + 스키마).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'PATH_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집할 카탈로그 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'CTLG_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '수집할 스키마 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'SCHM_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '반영한 출처 스키마. 반영 전이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마 수집 결과 (COMPLETED, FAILED, NOT_FOUND, SKIPPED).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'CLCT_RSLT_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 스키마에서 수집한 테이블 수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'TBL_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 스키마에서 수집한 컬럼 수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'COL_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이 스키마를 수집하지 못했을 때의 오류 코드.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CLCT_TRGT' and COL_NM = 'ERR_CD' and TASK_EXPLN is null;
-- TB_SRC_SCHM_VER
update TB_COL set TASK_EXPLN = '버전 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'SRC_SCHM_VER_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전이 속한 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전을 만든 출처 스키마.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전을 만든 수집 실행.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'SRC_CLCT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전을 만든 실행의 반영 순서. 어느 시점의 버전인지 가린다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'RFLT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마 경로.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'PATH_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마의 생성 차수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'CRT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '직전 확정 버전과 비교한 변경 종류 (BASE : 첫 버전, ADDED, MODIFIED, REMOVED).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'CHG_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마 내용의 정규화 해시.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'HASH_VL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '스키마 내용 JSON 의 형식 버전.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'FRMT_VER_NO' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '그 시점 스키마 내용 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_VER' and COL_NM = 'SCHM_CN' and TASK_EXPLN is null;
-- TB_SRC_TBL_VER
update TB_COL set TASK_EXPLN = '버전 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'SRC_TBL_VER_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전이 속한 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블이 속한 출처 스키마.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전을 만든 출처 테이블.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전을 만든 수집 실행.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'SRC_CLCT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '버전을 만든 실행의 반영 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'RFLT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블이 속한 스키마 경로.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'PATH_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블의 비교용 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'CMPR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블의 생성 차수.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'CRT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '직전 확정 버전과 비교한 변경 종류 (BASE, ADDED, MODIFIED, REMOVED).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'CHG_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블 정의의 정규화 해시. 직전 버전과 같으면 새 버전을 만들지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'HASH_VL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '테이블 내용 JSON 의 형식 버전.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'FRMT_VER_NO' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '그 시점 테이블 정의 JSON (컬럼·제약조건·인덱스·관계).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_VER' and COL_NM = 'TBL_CN' and TASK_EXPLN is null;
-- TB_SRC_CHG_HSTRY
update TB_COL set TASK_EXPLN = '이력 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'SRC_CHG_HSTRY_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경이 일어난 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경을 발견한 수집 실행.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'SRC_CLCT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경을 발견한 실행의 반영 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'RFLT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경이 일어난 출처 스키마.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경이 일어난 출처 테이블. 스키마 단위 변경이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바뀐 객체 종류 (SCHEMA, TABLE, COLUMN, CONSTRAINT, INDEX, RELATION).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'TRGT_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바뀐 객체를 가리키는 식별자 (경로와 비교용 이름).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'TRGT_IDNTFR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바뀐 객체의 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'TRGT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경이 일어난 스키마·테이블의 생성 차수. 사라졌다 다시 생긴 같은 이름의 객체를 구분한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'CRT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 종류 (ADDED, MODIFIED, REMOVED).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'CHG_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바뀐 항목 목록 JSON 배열.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'CHG_ARTCL_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 전 값 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'CHG_BFR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 후 값 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_CHG_HSTRY' and COL_NM = 'CHG_AFTR_CN' and TASK_EXPLN is null;
-- TB_IDX_CHG_DMND
update TB_COL set TASK_EXPLN = '변경 요청을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_IDX_CHG_DMND' and COL_NM = 'IDX_CHG_DMND_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경을 요청한 인덱스.' where PRJ_SN = 0 and TBL_NM = 'TB_IDX_CHG_DMND' and COL_NM = 'IDX_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청 당시 승인된 인덱스 정의 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_IDX_CHG_DMND' and COL_NM = 'CHG_BFR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바꾸려는 인덱스 정의 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_IDX_CHG_DMND' and COL_NM = 'CHG_AFTR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청 상태 (REQUESTED : 승인 대기, APPROVED, REJECTED).' where PRJ_SN = 0 and TBL_NM = 'TB_IDX_CHG_DMND' and COL_NM = 'DMND_STTS_CD' and TASK_EXPLN is null;
-- TB_VIEW
update TB_COL set TASK_EXPLN = '뷰를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and COL_NM = 'VIEW_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰가 속한 스키마. 같은 스키마의 테이블과 이름이 겹칠 수 없다.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and COL_NM = 'SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'DB 에 만들 뷰 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and COL_NM = 'VIEW_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰의 한글 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and COL_NM = 'VIEW_KORN_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰에 대한 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and COL_NM = 'VIEW_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰를 정의하는 SELECT 문.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and COL_NM = 'VIEW_DFN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인된 뷰인지 여부. 승인된 뷰를 고치면 뷰 변경 요청으로 승인을 기다린다.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW' and COL_NM = 'APRV_YN' and TASK_EXPLN is null;
-- TB_VIEW_COL
update TB_COL set TASK_EXPLN = '뷰 컬럼을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and COL_NM = 'VIEW_COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '컬럼이 속한 뷰.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and COL_NM = 'VIEW_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰 출력 컬럼 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and COL_NM = 'COL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰 출력 컬럼의 한글 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and COL_NM = 'COL_KORN_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰 출력 컬럼의 자료 유형.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and COL_NM = 'DATA_TYPE_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰 안에서 컬럼의 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and COL_NM = 'COL_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '뷰 출력 컬럼 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_COL' and COL_NM = 'COL_EXPLN' and TASK_EXPLN is null;
-- TB_VIEW_RFRNC
update TB_COL set TASK_EXPLN = '참조 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and COL_NM = 'VIEW_RFRNC_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조를 가진 뷰.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and COL_NM = 'VIEW_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조하는 객체 종류 (TABLE, VIEW).' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and COL_NM = 'RFRNC_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조하는 모델 테이블. 테이블 참조가 아니거나 모델에 없으면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and COL_NM = 'RFRNC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조하는 모델 뷰. 뷰 참조가 아니거나 모델에 없으면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and COL_NM = 'RFRNC_VIEW_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조하는 객체의 스키마 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and COL_NM = 'RFRNC_SCHM_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '참조하는 객체의 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_RFRNC' and COL_NM = 'RFRNC_TRGT_NM' and TASK_EXPLN is null;
-- TB_VIEW_CHG_DMND
update TB_COL set TASK_EXPLN = '변경 요청을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_CHG_DMND' and COL_NM = 'VIEW_CHG_DMND_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경을 요청한 뷰.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_CHG_DMND' and COL_NM = 'VIEW_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청 당시 승인된 뷰 정의 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_CHG_DMND' and COL_NM = 'CHG_BFR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바꾸려는 뷰 정의 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_CHG_DMND' and COL_NM = 'CHG_AFTR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청 상태 (REQUESTED : 승인 대기, APPROVED, REJECTED).' where PRJ_SN = 0 and TBL_NM = 'TB_VIEW_CHG_DMND' and COL_NM = 'DMND_STTS_CD' and TASK_EXPLN is null;
-- TB_PRJ_SRC_CNTN
update TB_COL set TASK_EXPLN = '행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ_SRC_CNTN' and COL_NM = 'PRJ_SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '기준으로 정한 출처 접속. 접속을 지우면 기준도 함께 지운다.' where PRJ_SN = 0 and TBL_NM = 'TB_PRJ_SRC_CNTN' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
-- TB_SRC_SCHM_MPNG
update TB_COL set TASK_EXPLN = '매핑 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'SRC_SCHM_MPNG_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 스키마의 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 스키마. 출처에서 사라지면 비어 있고 경로로 남는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 스키마의 경로.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'PATH_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 스키마 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'SCHM_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑 당시 출처 스키마의 생성 차수. 다시 만들어진 스키마면 매핑을 다시 확인하게 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'CRT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑할 때 기준으로 삼은 수집 반영 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'RFLT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '대응하는 모델 스키마.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_SCHM_MPNG' and COL_NM = 'SCHM_SN' and TASK_EXPLN is null;
-- TB_SRC_TBL_MPNG
update TB_COL set TASK_EXPLN = '매핑 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'SRC_TBL_MPNG_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 테이블의 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 테이블의 스키마.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'SRC_SCHM_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 테이블. 출처에서 사라지면 비어 있고 경로·비교용 이름으로 남는다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'SRC_TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 테이블의 스키마 경로.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'PATH_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑한 출처 테이블의 비교용 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'CMPR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑 당시 출처 테이블의 생성 차수. 다시 만들어진 테이블이면 매핑을 다시 확인하게 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'CRT_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '매핑할 때 기준으로 삼은 수집 반영 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'RFLT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '대응하는 모델 테이블. 비어 있으면 아직 모델에 없는 테이블이다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '비교·가져오기에서 제외한 출처 테이블인지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_TBL_MPNG' and COL_NM = 'MPNG_EXCL_YN' and TASK_EXPLN is null;
-- TB_SRC_IMPRT_EXCN
update TB_COL set TASK_EXPLN = '가져오기 실행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IMPRT_EXCN' and COL_NM = 'SRC_IMPRT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '가져온 출처 접속.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IMPRT_EXCN' and COL_NM = 'SRC_CNTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '가져올 때 기준으로 한 수집 실행.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IMPRT_EXCN' and COL_NM = 'SRC_CLCT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '기준 수집 실행의 반영 순서.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IMPRT_EXCN' and COL_NM = 'RFLT_ORD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '가져오기 요청 아이디. 모델 변경 이력의 변경 그룹과 같고, 같은 요청을 두 번 적용하지 않게 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IMPRT_EXCN' and COL_NM = 'CHG_GROUP_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '적용 요약 JSON (항목 종류별 수, 대상 테이블, 선택한 항목).' where PRJ_SN = 0 and TBL_NM = 'TB_SRC_IMPRT_EXCN' and COL_NM = 'IMPRT_CN' and TASK_EXPLN is null;
-- TB_EXTRL_CNTN_RULE
update TB_COL set TASK_EXPLN = '규칙을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and COL_NM = 'EXTRL_CNTN_RULE_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '규칙을 적용할 연결 종류 (JDBC : 출처 DB, AI_HTTP : AI 서버).' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and COL_NM = 'CNTN_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '허용할 호스트 이름. 비어 있으면 호스트 이름은 따지지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and COL_NM = 'HOST_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '허용할 IP 대역 (CIDR). 호스트 이름을 풀어 나온 주소가 모두 이 대역 안에 있어야 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and COL_NM = 'ALWNC_ADDR' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '허용할 포트. 비어 있으면 포트는 따지지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and COL_NM = 'PORT_NO' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '규칙 사용 여부. 비활성 규칙은 판정에 쓰지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and COL_NM = 'ACTVTN_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '규칙을 둔 이유 등 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE' and COL_NM = 'RULE_EXPLN' and TASK_EXPLN is null;
-- TB_EXTRL_CNTN_RULE_HSTRY
update TB_COL set TASK_EXPLN = '이력 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE_HSTRY' and COL_NM = 'EXTRL_CNTN_RULE_HSTRY_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '바뀐 규칙. 규칙을 지운 뒤에도 이력은 남는다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE_HSTRY' and COL_NM = 'EXTRL_CNTN_RULE_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 종류 (CREATE, UPDATE, DELETE, ENABLE, DISABLE).' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE_HSTRY' and COL_NM = 'CHG_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 전 규칙 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE_HSTRY' and COL_NM = 'CHG_BFR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경 후 규칙 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE_HSTRY' and COL_NM = 'CHG_AFTR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '변경을 처리한 API 요청의 아이디. 요청 로그와 맞춰 볼 때 쓴다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_RULE_HSTRY' and COL_NM = 'DMND_ID' and TASK_EXPLN is null;
-- TB_EXTRL_CNTN_HSTRY
update TB_COL set TASK_EXPLN = '기록 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'EXTRL_CNTN_HSTRY_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '연결 종류 (JDBC, AI_HTTP).' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'CNTN_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '연결하려던 호스트.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'HOST_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '연결하려던 포트.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'PORT_NO' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '판정 때 호스트를 풀어 검증한 IP 주소 목록 (쉼표 구분). 실제 연결은 이 주소에만 한다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'CNTN_ADDR_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '허용 판정에 쓰인 규칙. 차단이면 비어 있을 수 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'EXTRL_CNTN_RULE_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '결과 (ALLOWED, DENIED, CONNECTED, FAILED, BLOCKED).' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'RSLT_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '차단·실패 사유 (NO_RULE, PORT_NOT_ALLOWED, BLOCKED_ADDRESS, DNS_FAILED, CONNECT_FAILED 등).' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'RSN_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '연결을 시도한 실행 주체 (USER, ASYNC, SCHEDULED).' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'EXCN_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '연결을 일으킨 요청 또는 작업의 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_EXTRL_CNTN_HSTRY' and COL_NM = 'DMND_ID' and TASK_EXPLN is null;
-- TB_AI_PRVDR
update TB_COL set TASK_EXPLN = 'AI 제공자를 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and COL_NM = 'AI_PRVDR_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '화면에 표시하는 제공자 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and COL_NM = 'PRVDR_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'AI 서버 종류 (현재 OLLAMA 만).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and COL_NM = 'PRVDR_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'AI 서버 주소 (예: http://ollama:11434). 외부 접속 규칙(AI_HTTP)이 이 주소를 허용해야 호출된다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and COL_NM = 'BSC_URL_ADDR' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '호출할 모델 이름. 다른 모델로 자동으로 바꾸지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and COL_NM = 'BSC_MDL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제공자 사용 여부. 비활성이면 기본 제공자로 지정돼 있어도 AI 기능을 쓸 수 없다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and COL_NM = 'ACTVTN_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제공자에 대한 설명.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_PRVDR' and COL_NM = 'PRVDR_EXPLN' and TASK_EXPLN is null;
-- TB_AI_STNG
update TB_COL set TASK_EXPLN = '설정 행 번호. 항상 1 이다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_STNG' and COL_NM = 'AI_STNG_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'AI 도우미·AI 설명 제안이 호출하는 시스템 기본 AI 제공자. 비어 있으면 AI 기능이 꺼진 상태이며 다른 제공자로 대신하지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_STNG' and COL_NM = 'BSC_AI_PRVDR_SN' and TASK_EXPLN is null;
-- TB_AI_EXPLN_SGSTN
update TB_COL set TASK_EXPLN = '제안을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'AI_EXPLN_SGSTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '설명할 대상 종류 (TABLE, COLUMN).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'TRGT_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '대상 테이블 (컬럼 대상이면 그 컬럼의 테이블). 모델 테이블과 외래키로 묶지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '대상 컬럼. 테이블 대상이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '생성 당시 대상 이름 (테이블명 또는 테이블명.컬럼명).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'TRGT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '같은 대상에 대해 몇 번째로 만든 제안인지 나타내는 차수.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'SGSTN_CYCL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'AI 가 제안한 업무 설명 문구.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'SGSTN_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '확실성 (VERIFIED, INFERRED, UNKNOWN). 근거 검증 결과에 따라 서버가 낮출 수만 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'CRTNTY_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '검토 상태 (PROPOSED, REVIEWED, APPROVED, REJECTED, ON_HOLD, REVIEW_REQUIRED, SUPERSEDED, CLOSED).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'RVW_STTS_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '상세 JSON : 이름 해석, 표준 용어·도메인 추천과 검증 결과, 연관 객체, 불확실한 부분, 확인 질문.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'SGSTN_DTL_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = 'AI 가 든 근거와 서버의 근거 검증 결과 JSON.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'EVDNC_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '생성 시점 기준 버전 JSON (대상·근거의 버전, 그때의 업무 설명, 출처 카탈로그 값). 승인할 때 지금 값과 비교한다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'CRTR_VER_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제안을 만든 AI 제공자.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'AI_PRVDR_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제안을 만든 모델 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'MDL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '사용한 프롬프트 버전 (예: dictionary-draft/2).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'PRMPT_VER_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '사용한 프롬프트 본문의 SHA-256 해시.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'PRMPT_HASH_VL' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '생성 방식 (TOOL_CALLING, SERVER_CONTEXT). 설명 초안은 모델이 도구를 부르지 않고 서버가 모은 메타데이터 문맥으로 만든다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'CRT_MTHD_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '일괄 생성 실행으로 만든 제안이면 그 실행. 단건 생성이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'AI_EXPLN_CRT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 검토·승인·반려한 사용자 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'RVWR_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '마지막으로 검토한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'RVW_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인으로 업무 설명에 반영한 문구. 고쳐서 승인했으면 사용자가 고친 문구이다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'CFMTN_EXPLN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '반려·보류·종결·재확인 때 남긴 사유.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'RVW_RSN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인 뒤 근거나 출처 COMMENT 가 바뀌어 업무 설명을 다시 볼 필요가 있는지 여부. 상태와 업무 설명은 그대로 둔다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'RRVW_NEED_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '동시 검토를 막는 버전 번호. 화면이 읽은 번호와 다르면 승인·반려를 거부한다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN' and COL_NM = 'VER_NO' and TASK_EXPLN is null;
-- TB_AI_EXPLN_SGSTN_HSTRY
update TB_COL set TASK_EXPLN = '이력 행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'AI_EXPLN_SGSTN_HSTRY_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리한 제안.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'AI_EXPLN_SGSTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리 종류 (GENERATE, REVIEW, APPROVE, APPROVE_EDITED, REJECT, HOLD, REVIEW_REQUIRED, SUPERSEDE, REVALIDATE, CLOSE 등).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'PRCS_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리 전 검토 상태.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'BFR_STTS_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리 후 검토 상태.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'AFTR_STTS_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리한 사용자 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'PRCR_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'PRCS_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '처리 사유.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'PRCS_RSN_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인 처리일 때 업무 설명에 반영한 문구.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_SGSTN_HSTRY' and COL_NM = 'CFMTN_EXPLN' and TASK_EXPLN is null;
-- TB_AI_EXPLN_CRT_EXCN
update TB_COL set TASK_EXPLN = '일괄 실행을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'AI_EXPLN_CRT_EXCN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행을 요청한 계정.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'ACNT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행 상태 (PENDING, RUNNING, COMPLETED, PARTIAL_FAILED, FAILED, INTERRUPTED).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'EXCN_STTS_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '요청 시점에 서버가 정한 대상 목록 JSON (대상 종류, 테이블, 컬럼).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'TRGT_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '전체 대상 수.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'TRGT_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제안을 만든 대상 수.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'CMPTN_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실패한 대상 수.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'FAIL_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '이미 열린 제안이 있는 등 이유로 건너뛴 대상 수.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'SKIP_CNT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행을 시작한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'BGNG_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행이 끝난 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'END_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실행 전체가 실패했을 때의 오류 내용. AI 서버 응답 본문은 남기지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_CRT_EXCN' and COL_NM = 'ERR_CN' and TASK_EXPLN is null;
-- TB_AI_EXPLN_EVL_TRGT
update TB_COL set TASK_EXPLN = '평가 대상을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'AI_EXPLN_EVL_TRGT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '평가 대상 종류 (TABLE, COLUMN).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'TRGT_TYPE_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '평가 대상 테이블 (컬럼 대상이면 그 컬럼의 테이블).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'TBL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '평가 대상 컬럼. 테이블 대상이면 비어 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'COL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '등록 당시 대상 이름.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'TRGT_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '대상의 평가 범주 JSON 배열 (CLEAR, NAME_ONLY, STATUS_CODE, AMOUNT, DATE_TIME 등). 범주별 지표를 나눠 본다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'EVL_CTGRY_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '업무 담당자·검토자가 확인한 실제 업무 의미. 정확성 판정의 기준이며 AI 에 보내지 않는다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'ACTL_MNNG_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실제 의미 확인 상태 (PENDING, CONFIRMED, UNCONFIRMABLE). 정확성 판정은 CONFIRMED 대상만 할 수 있다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'MNNG_IDNTY_STTS_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '등록 시점 대상의 메타데이터 버전 JSON (수정 일시와 마지막 모델 변경 이력 번호).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'CRTR_VER_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실제 의미를 확인한 사용자 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'RVWR_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '실제 의미를 확인한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'RVW_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '동시 수정을 막는 버전 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL_TRGT' and COL_NM = 'VER_NO' and TASK_EXPLN is null;
-- TB_AI_EXPLN_EVL
update TB_COL set TASK_EXPLN = '판정을 구분하는 내부 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'AI_EXPLN_EVL_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '판정한 평가 대상.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'AI_EXPLN_EVL_TRGT_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '판정한 AI 설명 제안.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'AI_EXPLN_SGSTN_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제안을 만든 모델 이름. 모델별 지표를 나눠 본다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'MDL_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제안을 만든 프롬프트 버전.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'PRMPT_VER_NM' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '업무적 정확성 판정 (CORRECT, PARTIAL, INCORRECT, NOT_EVALUABLE).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'JGMT_RSLT_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '근거 없이 지어낸 주장이 있었는지 여부.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'UNFND_CLM_YN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제안에서 빠진 업무 정보.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'OMSN_INFO_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '불확실성 처리 판정 (APPROPRIATE, MISSED : 모르는 것을 단정, EXCESSIVE : 아는 것도 불확실하다고 함, NOT_APPLICABLE).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'UNCRT_JGMT_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '표준 용어 추천 판정 (APPROPRIATE, INAPPROPRIATE, NO_RECOMMENDATION, NOT_EVALUABLE).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'TRM_RCMDTN_JGMT_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '표준 도메인 추천 판정 (APPROPRIATE, INAPPROPRIATE, NO_RECOMMENDATION, NOT_EVALUABLE).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'DMN_RCMDTN_JGMT_CD' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '승인하려면 고쳐야 했던 부분.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'MDFCN_NEED_CN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '제안을 검토하는 데 든 시간 (분).' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'RVW_REQ_HR' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '사람이 처음부터 쓰는 데 든(든다고 본) 시간 (분). 검토 시간과 비교해 절감 효과를 본다.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'WRT_REQ_HR' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '판정한 사용자 아이디.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'RVWR_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '판정한 일시.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'RVW_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '동시 수정을 막는 버전 번호.' where PRJ_SN = 0 and TBL_NM = 'TB_AI_EXPLN_EVL' and COL_NM = 'VER_NO' and TASK_EXPLN is null;

-- ---------- 여러 테이블에 공통으로 있는 컬럼 (위에서 따로 적지 않은 것만 채운다) ----------
update TB_COL set TASK_EXPLN = '이 행이 속한 프로젝트. 조회와 변경은 로그인할 때 고른 프로젝트 안으로 제한되며, 프로젝트를 지우면 함께 지운다.' where PRJ_SN = 0 and COL_NM = 'PRJ_SN' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '행을 처음 등록한 일시. 저장할 때 자동으로 채운다.' where PRJ_SN = 0 and COL_NM = 'REG_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '행을 처음 등록한 사용자 아이디. 로그인 세션이 없는 작업이나 초기 데이터는 SYSTEM 같은 실행 주체 이름이 들어간다.' where PRJ_SN = 0 and COL_NM = 'RGTR_ID' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '행을 마지막으로 수정한 일시. 저장할 때 자동으로 갱신한다.' where PRJ_SN = 0 and COL_NM = 'MDFCN_DT' and TASK_EXPLN is null;
update TB_COL set TASK_EXPLN = '행을 마지막으로 수정한 사용자 아이디.' where PRJ_SN = 0 and COL_NM = 'MDFR_ID' and TASK_EXPLN is null;
