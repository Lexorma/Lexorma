-- 메신저(채팅)·코드규칙 기능 제거 : 사용하지 않는 테이블/시퀀스 삭제
-- (V1에서 생성. baseline 처리된 기존 DB에는 없을 수 있으므로 IF EXISTS, 참조하는 테이블부터 삭제)
DROP TABLE IF EXISTS chat_message_read;
DROP TABLE IF EXISTS chat_message;

DROP TABLE IF EXISTS code_rule_detail;
DROP TABLE IF EXISTS code_rule;
DROP TABLE IF EXISTS project_template_type;
DROP TABLE IF EXISTS template;

DROP SEQUENCE IF EXISTS chat_message_read_seq;
DROP SEQUENCE IF EXISTS code_rule_seq;
DROP SEQUENCE IF EXISTS template_seq;
