package com.koboolean.metamora.common.exception;

import com.koboolean.metamora.common.exception.domain.ErrorCode;
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
                ex.getCustomMessage() // customMessage를 우선 사용
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
        return new ResponseEntity<>(new ErrorResponse(HttpStatus.NOT_FOUND.value(), "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다."),
                HttpStatus.NOT_FOUND);
    }

    // 에러 응답을 위한 DTO 클래스
    public static class ErrorResponse {
        private final int status;
        private final String code;
        private final String message;

        public ErrorResponse(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
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
