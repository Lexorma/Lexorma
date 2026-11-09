/* 데이터 사전 약어 사용 여부가 비어 있던 프로젝트(초기 MAIN 등)는 사용(true)으로 둔다.
   업로드 시 false 면 용어 영문명을 단어 영문명으로 만들므로, 기존 프로젝트는 지금처럼 엑셀의 영문약어명을 그대로 쓰게 한다. */
update project set is_dic_abbr_used = true where is_dic_abbr_used is null;
