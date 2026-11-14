package com.lexorma.core.network;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 외부 연결 목적지의 주소 규칙 (SEC-1, docs/external-connection-security-sec1.md). JDBC·AI HTTP 가 같이 쓴다.
 * <ul>
 *   <li><b>절대 차단</b> : 관리자 허용 규칙으로도 열리지 않는다. 미지정(0.0.0.0/8, ::), 링크 로컬(169.254.0.0/16 — 클라우드 메타데이터 169.254.169.254 포함, fe80::/10),
 *       멀티캐스트, 예약·브로드캐스트(240.0.0.0/4), 클라우드 메타데이터 주소(fd00:ec2::254, 100.100.100.200, 168.63.129.16), Teredo(2001::/32),
 *       IPv4 호환 IPv6(::a.b.c.d), 그리고 IPv4 를 품은 IPv6(IPv4-mapped ::ffff:, NAT64 64:ff9b::/96, 6to4 2002::/16)는 품은 IPv4 로 다시 본다</li>
 *   <li><b>사설·루프백</b> : 127.0.0.0/8, ::1, 10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7, fec0::/10 — 관리자 규칙이 그 대역을 명시해야 열린다 (기본 차단)</li>
 *   <li><b>주소 표기</b> : 숫자 주소는 점 네 개의 십진수(0 으로 시작하는 자리 없음)와 IPv6 만 받는다. 2130706433·127.1·0x7f.1 같은 표기는 받지 않는다</li>
 * </ul>
 * 이 클래스는 이름을 풀지 않는다 (DNS 는 {@link HostResolver}).
 */
public final class NetworkAddresses {

    // 표기 규칙을 먼저 둔다 : 아래 대역 목록이 이것으로 만들어진다
    private static final Pattern IPV4 = Pattern.compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]{2,45}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final List<Cidr> BLOCKED = Cidr.parseList(
            "0.0.0.0/8,169.254.0.0/16,240.0.0.0/4,100.100.100.200/32,168.63.129.16/32,fe80::/10,fd00:ec2::254/128,2001::/32,ff00::/8");
    private static final List<Cidr> PRIVATE = Cidr.parseList("127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,100.64.0.0/10,fc00::/7,fec0::/10");
    /** 클라우드 메타데이터 이름 (주소로도 막지만 이름으로도 막는다) */
    private static final Set<String> BLOCKED_HOSTS = Set.of("metadata", "metadata.google.internal", "metadata.goog", "instance-data", "instance-data.ec2.internal");


    private NetworkAddresses() {
    }

    /** 관리자 규칙으로도 열 수 없는 주소 */
    public static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) return true;
        if (BLOCKED.stream().anyMatch(network -> network.contains(address))) return true;
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            if (isZero(bytes, 0, 12) && !address.isLoopbackAddress()) return true; // IPv4 호환 IPv6 (폐지된 표기)
            InetAddress embedded = embeddedIpv4(bytes);
            return embedded != null && isBlocked(embedded);
        }
        return false;
    }

    /** 사설·루프백 (관리자 규칙이 대역을 명시해야 열린다. 운영자 상한도 이 주소에만 적용한다) */
    public static boolean isPrivateOrLoopback(InetAddress address) {
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || PRIVATE.stream().anyMatch(network -> network.contains(address));
    }

    /** 클라우드 메타데이터 이름 */
    public static boolean isBlockedHostName(String host) {
        return host != null && BLOCKED_HOSTS.contains(normalizeHost(host));
    }

    /**
     * 비교용 호스트 표기 : 공백 제거, 소문자, 끝의 '.' 와 IPv6 대괄호 제거. 숫자 IPv6 주소는 RFC 5952 표기로 맞춘다
     * (::1 · 0:0:0:0:0:0:0:1 · [::1] 이 같다). 이름은 풀지 않는다 — 숫자 표기만 바꾸고, IPv4-mapped 처럼 IPv4 로 읽히는 표기와 zone(%) 은 그대로 둔다
     */
    public static String normalizeHost(String host) {
        if (host == null) return null;
        String value = host.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.contains(":") && IPV6.matcher(value).matches()) {
            try {
                if (InetAddress.getByName(value) instanceof Inet6Address v6) return text(v6);
            } catch (UnknownHostException ignored) {
                // 숫자 주소가 아니다 : 그대로 둔다 (isValidHost 가 거부한다)
            }
        }
        return value;
    }

    /** 숫자 주소이면 그 주소 (이름을 풀지 않는다). 숫자 주소가 아니거나 표준이 아닌 표기면 null */
    public static InetAddress literal(String host) {
        String value = normalizeHost(host);
        if (value == null || value.isEmpty()) return null;
        boolean v4 = IPV4.matcher(value).matches();
        boolean v6 = value.contains(":") && IPV6.matcher(value).matches();
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 숫자 주소(표준 표기) 또는 호스트명 (RFC 1123 : 마지막 이름은 숫자로만 될 수 없다 — 127.1·2130706433 같은 비표준 숫자 표기를 막는다) */
    public static boolean isValidHost(String host) {
        String value = normalizeHost(host);
        if (value == null || value.isEmpty() || value.length() > 253) return false;
        if (literal(value) != null) return true;
        if (value.contains(":")) return false;
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) return false;
        }
        return !labels[labels.length - 1].matches("\\d+") && !value.matches("(?i).*\\b0x[0-9a-f]+.*");
    }

    private static boolean isZero(byte[] bytes, int from, int to) {
        for (int i = from; i < to; i++) {
            if (bytes[i] != 0) return false;
        }
        return true;
    }

    /** IPv6 안에 든 IPv4 : IPv4-mapped(::ffff:0:0/96), NAT64(64:ff9b::/96), 6to4(2002::/16) */
    private static InetAddress embeddedIpv4(byte[] bytes) {
        try {
            if (isZero(bytes, 0, 10) && (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) return Inet4Address.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
            if ((bytes[0] & 0xFF) == 0x00 && (bytes[1] & 0xFF) == 0x64 && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B && isZero(bytes, 4, 12)) {
                return Inet4Address.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
            }
            if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) return Inet4Address.getByAddress(Arrays.copyOfRange(bytes, 2, 6));
            return null;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** 주소 대역 (CIDR). 숫자 주소만 받고, 호스트 부분은 0 으로 맞춘다 (10.10.1.5/16 → 10.10.0.0/16) */
    public record Cidr(byte[] base, int prefix) {

        public static Cidr parse(String value) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("empty");
            String text = value.trim();
            int slash = text.indexOf('/');
            InetAddress address = literal(slash < 0 ? text : text.substring(0, slash));
            if (address == null) throw new IllegalArgumentException("not a numeric address");
            byte[] bytes = address.getAddress();
            int prefix;
            try {
                prefix = slash < 0 ? bytes.length * 8 : Integer.parseInt(text.substring(slash + 1));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid prefix");
            }
            if (prefix < 0 || prefix > bytes.length * 8) throw new IllegalArgumentException("invalid prefix");
            for (int bit = prefix; bit < bytes.length * 8; bit++) bytes[bit / 8] &= (byte) ~(0x80 >>> (bit % 8));
            return new Cidr(bytes, prefix);
        }

        public static List<Cidr> parseList(String value) {
            List<Cidr> list = new ArrayList<>();
            if (value == null) return list;
            for (String entry : value.split(",")) {
                if (!entry.isBlank()) list.add(parse(entry));
            }
            return List.copyOf(list);
        }

        public boolean ipv4() {
            return base.length == 4;
        }

        public boolean contains(InetAddress address) {
            byte[] bytes = address.getAddress();
            if (bytes.length != base.length) return false;
            int full = prefix / 8;
            for (int i = 0; i < full; i++) {
                if (bytes[i] != base[i]) return false;
            }
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (bytes[full] & mask) == (base[full] & mask);
        }

        /** 이 대역 전체가 other 안에 있다 */
        public boolean within(Cidr other) {
            return base.length == other.base.length && other.prefix <= prefix && other.contains(first());
        }

        /** 대역의 첫 주소 */
        public InetAddress first() {
            try {
                return InetAddress.getByAddress(base);
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }

        /** 표시·저장 표기 : IPv6 는 RFC 5952 처럼 줄인다 (fe80::/10) */
        @Override
        public String toString() {
            return text(first()) + "/" + prefix;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Cidr cidr && prefix == cidr.prefix && Arrays.equals(base, cidr.base);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(base) + prefix;
        }
    }

    /** 주소 표기 : IPv4 는 그대로, IPv6 는 가장 긴 0 묶음(2개 이상)을 :: 로 줄인 소문자 (RFC 5952) */
    public static String text(InetAddress address) {
        if (!(address instanceof Inet6Address)) return address.getHostAddress();
        byte[] bytes = address.getAddress();
        int[] groups = new int[8];
        for (int i = 0; i < 8; i++) groups[i] = ((bytes[2 * i] & 0xFF) << 8) | (bytes[2 * i + 1] & 0xFF);
        int bestStart = -1;
        int bestLength = 0;
        for (int i = 0; i < 8; ) {
            if (groups[i] != 0) {
                i++;
                continue;
            }
            int j = i;
            while (j < 8 && groups[j] == 0) j++;
            if (j - i > bestLength && j - i >= 2) {
                bestStart = i;
                bestLength = j - i;
            }
            i = j;
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == bestStart) {
                text.append("::");
                i += bestLength - 1;
                continue;
            }
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ':') text.append(':');
            text.append(Integer.toHexString(groups[i]));
        }
        return text.toString();
    }

    /** 절대 차단 대역 (관리자 화면 안내·규칙 검증) */
    public static List<Cidr> blockedNetworks() {
        return BLOCKED;
    }
}
