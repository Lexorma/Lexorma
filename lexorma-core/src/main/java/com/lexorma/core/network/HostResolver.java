package com.lexorma.core.network;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 외부 연결 목적지의 이름 풀기 (SEC-1). 정책 판정이 한 번 풀고, 실제 연결은 그 결과로 고정한다 ({@link ConnectionPin}) : 연결할 때 다시 풀지 않는다.
 * 운영에서는 JVM 의 이름 풀기를 쓰고, 테스트는 DNS 응답(혼합·재바인딩)을 흉내 내는 구현으로 바꾼다.
 */
@FunctionalInterface
public interface HostResolver {

    InetAddress[] resolve(String host) throws UnknownHostException;

    HostResolver SYSTEM = InetAddress::getAllByName;
}
