package com.lexorma.core.network;

import java.util.List;

/**
 * 등록된 외부 연결 목적지 (SEC-1, 읽기 전용 계약). 원본 DB 연결(lexorma-source)이 구현한다 : 관리자 화면이 "허용 규칙이 없는 등록 연결" 을 안내할 때 쓴다.
 * 기본 차단으로 바꿀 때 규칙을 자동으로 만들지 않으므로, 관리자가 어떤 목적지에 규칙이 필요한지 볼 수 있어야 한다.
 */
public interface ExternalConnectionTargets {

    /** @param label 화면 표시용 (예 : 프로젝트 · 연결 이름) */
    record Target(ConnectionType connectionType, String host, int port, String label) {
    }

    List<Target> targets();
}
