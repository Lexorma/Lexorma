package com.lexorma.source.catalog.service;

import com.lexorma.core.crypto.SecretScope;

/** VIEW 정의 원문의 암호화 범위 (DEC-16) : 목적·프로젝트·정의 행 ID·항목이 AAD 로 묶여 다른 행·프로젝트로 옮긴 암호문은 풀리지 않는다 */
public final class SourceViewDefinitionCipher {

    public static final String PURPOSE = "source-view-definition";

    private SourceViewDefinitionCipher() {
    }

    public static SecretScope scope(Long projectId, Long definitionId) {
        return SecretScope.ofProject(PURPOSE, projectId, definitionId, "definition");
    }
}
