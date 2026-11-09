package com.koboolean.metamora.common.exception;

import com.koboolean.metamora.common.exception.domain.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CustomFormException extends RuntimeException {
    ErrorCode errorCode;
}
