package com.lexorma.core.network.http;

import com.lexorma.common.exception.CustomException;
import com.lexorma.common.exception.domain.ErrorCode;
import com.lexorma.core.network.domain.dto.ExternalConnectionDto.Reason;

/**
 * SEC-1 정책으로 HTTP 연결을 열지 않았다 ({@link ExternalHttpClients#open}). 응답·오류 문구는 {@link ErrorCode#EXTERNAL_CONNECTION_BLOCKED} 와 같고,
 * 부른 쪽이 사유를 구분해 보여 줄 수 있도록 차단 사유 코드를 함께 담는다 (AI-3 : 허용 규칙 없음 · 절대 차단 · 이름 풀기 실패 등).
 */
public class ExternalConnectionBlockedException extends CustomException {

    private final Reason reason;
    private final String reasonText;

    ExternalConnectionBlockedException(Reason reason, String reasonText) {
        super(ErrorCode.EXTERNAL_CONNECTION_BLOCKED, "{0}", reasonText);
        this.reason = reason;
        this.reasonText = reasonText;
    }

    /** 차단 사유. 주소 형식 오류(스킴·계정 정보·호스트 없음)는 {@link Reason#INVALID_HOST} */
    public Reason reason() {
        return reason;
    }

    /** 차단 사유 문구 (요청 언어) */
    public String reasonText() {
        return reasonText;
    }
}
