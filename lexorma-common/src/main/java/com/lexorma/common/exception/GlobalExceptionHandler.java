package com.lexorma.common.exception;

import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ErrorResponse> handleCustomException(CustomException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        ErrorResponse response = new ErrorResponse(
                errorCode.getHttpStatus().value(),
                errorCode.getCode(),
                ex.getCustomMessage(), // customMessage를 우선 사용
                ex.getReason()
        );
        return new ResponseEntity<>(response, errorCode.getHttpStatus());
    }

    /**
     * 화면 폼 검증 오류 (회원가입 등) : CustomException 과 같은 JSON 형태
     */
    @ExceptionHandler(CustomFormException.class)
    public ResponseEntity<ErrorResponse> handleCustomFormException(CustomFormException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        return new ResponseEntity<>(new ErrorResponse(errorCode.getHttpStatus().value(), errorCode.getCode(), errorCode.getMessage()),
                errorCode.getHttpStatus());
    }

    /**
     * 없는 경로 : 화면(React) 경로는 SpaForwardController 가 index.html 로 보내므로 여기 오는 것은 없는 API / 정적 파일이다.
     * HTML 오류 화면 대신 JSON 404 를 반환한다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        return new ResponseEntity<>(new ErrorResponse(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", Messages.error("NOT_FOUND")),
                HttpStatus.NOT_FOUND);
    }

    // 에러 응답을 위한 DTO 클래스
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public static class ErrorResponse {
        private final int status;
        private final String code;
        private final String message;
        /** 409 원인 (M8-C4c-2) : "LOCKED" 이거나 없음 (선택 칸 — 기존 클라이언트는 무시해도 된다) */
        private final String reason;

        public ErrorResponse(int status, String code, String message) {
            this(status, code, message, null);
        }

        public ErrorResponse(int status, String code, String message, String reason) {
            this.status = status;
            this.code = code;
            this.message = message;
            this.reason = reason;
        }

        public String getReason() {
            return reason;
        }

        public int getStatus() {
            return status;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }
    }
}
