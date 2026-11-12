package com.lexorma.common.exception;

import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.common.i18n.Messages;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
public class CustomException extends RuntimeException {
    @Getter
    ErrorCode errorCode;
    private final String customMessage;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.customMessage = null;
    }

    /**
     * @param customMessage 사유 (한국어 원문, 값이 들어갈 자리는 {0}, {1})
     * @param args          {0}, {1} 에 들어갈 값
     */
    public CustomException(ErrorCode errorCode, String customMessage, Object... args) {
        this(errorCode, Messages.get(customMessage, args), errorCode.getMessage());
    }

    private CustomException(ErrorCode errorCode, String reason, String message) {
        super(message + " - " + reason);
        this.errorCode = errorCode;
        this.customMessage = message + " \n[" + reason + "]";
    }

    public String getCustomMessage() {
        return customMessage != null ? customMessage : errorCode.getMessage();
    }
}
