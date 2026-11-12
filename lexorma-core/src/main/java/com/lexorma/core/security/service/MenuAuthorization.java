
package com.lexorma.core.security.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 사이드바 메뉴 노출 권한
 *
 * <p>
 * 접근할 수 없는 화면은 메뉴에서 아예 보이지 않도록,
 * 화면별 최소 권한을 한 곳에서 관리한다.
 *
 * 최소 권한은 "화면 진입 권한"과
 * "화면이 호출하는 그리드 API 권한" 중 높은 쪽이다.
 * (예: 컬럼 관리는 등록·수정 화면이라 USER — 컬럼 조회 API(목록·상세)는 테이블 상세가 쓰므로 VIEWER 지만, 화면의 등록 폼·변경 API 가 USER 다)
 *
 * <p>
 * 권한 계층(RoleHierarchyConfig)을 적용하므로
 * 상위 권한은 하위 권한 메뉴를 모두 볼 수 있다.
 *
 * Thymeleaf 에서
 * {@code th:if="${@menuAuth.canView('user-manage')}"}
 * 형태로 사용한다.
 */
@Component("menuAuth")
@RequiredArgsConstructor
public class MenuAuthorization {

    /** 메뉴 ID(사이드바 data-menu-id / data-submenu-id) → 최소 권한 */
    public static final Map<String, String> MENU_ROLES = Map.ofEntries(
            Map.entry("dashboard", "NOT_APPROVE"),

            // 시스템 관리
            Map.entry("user-manage", "ADMIN"),
            Map.entry("project-manage", "DBA"),
            Map.entry("system-log", "DBA"),

            // 운영 관리
            Map.entry("notice", "NOT_APPROVE"),
            // 화면 메뉴가 아니라 공지 등록·수정·삭제 버튼 노출용 : 공지 변경 API 의 권한과 같다 (현재 프로젝트의 관리자여야 하는 조건은 /api/selectNotice/permission)
            Map.entry("notice-edit", "MANAGER"),

            // 데이터 사전
            Map.entry("standard-domain", "VIEWER"),
            Map.entry("standard-word", "VIEWER"),
            Map.entry("standard-term", "VIEWER"),
            // 화면 메뉴가 아니라 표준 도메인·단어·용어의 등록·수정·삭제·엑셀 버튼 노출용 : 데이터 사전 변경 API·엑셀 API 의 권한과 같다
            Map.entry("dictionary-edit", "USER"),
            // 승인·승인취소 버튼 : 데이터 사전 승인 API 의 역할. 서버는 여기에 승인 권한(AuthUtil.isApprovalAvailable)도 요구하고, 화면도 approvalAvailable 을 함께 본다
            Map.entry("dictionary-approve", "DBA"),

            // 논리 모델 관리
            Map.entry("logical-model", "VIEWER"),
            Map.entry("logical-erd", "VIEWER"),
            // 화면 메뉴가 아니라 논리 모델 변경 버튼(등록·수정·삭제) 노출용 : 논리 모델 변경 API 의 권한과 같다
            Map.entry("logical-model-edit", "USER"),
            // 논리·물리 매핑 : 화면(매핑·정합성 조회)은 VIEWER, 매핑 등록·변경·해제 버튼은 USER (매핑 API 의 권한과 같다)
            Map.entry("model-mapping", "VIEWER"),
            Map.entry("model-mapping-edit", "USER"),

            // 물리 모델 관리
            Map.entry("schema-manage", "VIEWER"),
            Map.entry("table-manage", "VIEWER"),
            Map.entry("column-manage", "USER"),
            Map.entry("physical-erd", "VIEWER"),
            // 화면 메뉴가 아니라 물리 모델 변경 버튼(스키마·테이블 관리의 등록·수정·삭제·엑셀, 물리 ERD 편집 모드) 노출용 : 물리 모델 변경 API 의 권한과 같다
            Map.entry("physical-model-edit", "USER"),

            // 데이터 품질 관리
            Map.entry("standardization-quality", "VIEWER"),
            // 모델 변경 이력 (M-9) : 조회만 있는 화면. 이력 조회 API 의 권한과 같다
            Map.entry("model-history", "VIEWER"),
            // 모델 버전 (M-9 3차) : 화면(목록·과거 버전 조회·비교)은 VIEWER, [버전 생성] 버튼은 USER (버전 생성 API 의 권한과 같다)
            Map.entry("model-version", "VIEWER"),
            Map.entry("model-version-create", "USER")
    );

    /**
     * 1뎁스 메뉴 그룹 → 하위 메뉴 ID
     * 하위 메뉴가 하나라도 보이면 그룹을 노출한다.
     */
    public static final Map<String, String[]> MENU_GROUPS = Map.of(
            "system-management",
            new String[]{"user-manage", "project-manage", "system-log"},

            "operation-management",
            new String[]{"notice"},

            "data-dictionary",
            new String[]{"standard-domain", "standard-word", "standard-term"},

            "logical-modeling",
            new String[]{"logical-model", "logical-erd", "model-mapping"},

            "data-quality",
            new String[]{"standardization-quality", "model-history", "model-version"},

            "physical-modeling",
            new String[]{"schema-manage", "table-manage", "column-manage", "physical-erd"}
    );

    private final RoleHierarchy roleHierarchy;

    /**
     * 메뉴(또는 메뉴 그룹)를 현재 사용자에게 보여줄지 여부
     */
    public boolean canView(String menuId) {
        String[] children = MENU_GROUPS.get(menuId);
        if (children != null) {
            for (String child : children) {
                if (canView(child)) {
                    return true;
                }
            }
            return false;
        }

        String role = MENU_ROLES.get(menuId);
        return role != null && hasRole(role);
    }

    /**
     * 권한 계층을 적용하여 현재 사용자가 해당 권한을 가졌는지 확인한다.
     *
     * @param role ROLE_ 접두사를 뺀 권한명 (예: USER)
     */
    public boolean hasRole(String role) {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        String target = "ROLE_" + role;

        return roleHierarchy
                .getReachableGrantedAuthorities(authentication.getAuthorities())
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(target::equals);
    }
}
