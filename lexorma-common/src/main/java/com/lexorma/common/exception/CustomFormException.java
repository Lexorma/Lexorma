package com.lexorma.common.exception;

import com.lexorma.common.exception.domain.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CustomFormException extends RuntimeException {
    ErrorCode errorCode;
}
