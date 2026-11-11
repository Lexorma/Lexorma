
package com.koboolean.metamora.core.security.service;

import com.koboolean.metamora.core.security.conf.RoleHierarchyConfig;
import com.koboolean.metamora.core.support.SecurityTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MenuAuthorizationTest {

    private final MenuAuthorization menuAuth =
            new MenuAuthorization(RoleHierarchyConfig.roleHierarchy());

    @AfterEach
    void tearDown() {
        SecurityTestSupport.clear();
    }

    @Test
    @DisplayName("미승인 사용자는 대시보드와 공지사항(운영 관리)만 볼 수 있다")
    void notApprove() {
        SecurityTestSupport.loginAs(1L, "9", "ROLE_NOT_APPROVE");

        assertThat(menuAuth.canView("dashboard")).isTrue();
        assertThat(menuAuth.canView("operation-management")).isTrue();
        assertThat(menuAuth.canView("notice")).isTrue();

        assertThat(menuAuth.canView("system-management")).isFalse();
        assertThat(menuAuth.canView("data-dictionary")).isFalse();
        assertThat(menuAuth.canView("logical-modeling")).isFalse();
        assertThat(menuAuth.canView("physical-modeling")).isFalse();
        assertThat(menuAuth.canView("standardization-quality")).isFalse();
        assertThat(menuAuth.canView("data-quality")).isFalse();
        assertThat(menuAuth.canView("model-mapping")).isFalse();
        assertThat(menuAuth.canView("model-mapping-edit")).isFalse();
    }

    @Test
    @DisplayName("VIEWER 는 조회 화면만 보이고, USER 권한 화면과 시스템 관리 메뉴는 숨겨진다")
    void viewer() {
        SecurityTestSupport.loginAs(1L, "8", "ROLE_VIEWER");

        assertThat(menuAuth.canView("data-dictionary")).isTrue();
        assertThat(menuAuth.canView("table-manage")).isTrue();
        assertThat(menuAuth.canView("schema-manage")).isTrue();
        assertThat(menuAuth.canView("physical-erd")).isTrue();
        assertThat(menuAuth.canView("physical-modeling")).isTrue();
        assertThat(menuAuth.canView("logical-model")).isTrue();
        assertThat(menuAuth.canView("logical-erd")).isTrue();
        assertThat(menuAuth.canView("logical-modeling")).isTrue();
        assertThat(menuAuth.canView("model-mapping")).isTrue();
        assertThat(menuAuth.canView("standardization-quality")).isTrue();
        assertThat(menuAuth.canView("data-quality")).isTrue();
        // 데이터 사전은 그룹이고 표준 도메인·단어·용어가 각각 화면이다
        assertThat(menuAuth.canView("standard-domain")).isTrue();
        assertThat(menuAuth.canView("standard-word")).isTrue();
        assertThat(menuAuth.canView("standard-term")).isTrue();

        // 논리 모델 변경 버튼은 USER 부터
        assertThat(menuAuth.canView("logical-model-edit")).isFalse();
        // 매핑 등록·변경·해제 버튼도 USER 부터
        assertThat(menuAuth.canView("model-mapping-edit")).isFalse();
        assertThat(menuAuth.canView("column-manage")).isFalse();
        assertThat(menuAuth.canView("system-log")).isFalse();

        // 시스템 관리 메뉴는 DBA 이상
        assertThat(menuAuth.canView("user-manage")).isFalse();
        assertThat(menuAuth.canView("project-manage")).isFalse();
        assertThat(menuAuth.canView("system-management")).isFalse();
    }

    @Test
    @DisplayName("DBA 는 프로젝트 관리·시스템 로그가 보이고, 사용자 관리(ADMIN)는 숨겨진다")
    void dba() {
        SecurityTestSupport.loginAs(1L, "3", "ROLE_DBA");

        assertThat(menuAuth.canView("system-management")).isTrue();
        assertThat(menuAuth.canView("project-manage")).isTrue();
        assertThat(menuAuth.canView("system-log")).isTrue();
        assertThat(menuAuth.canView("physical-modeling")).isTrue();
        assertThat(menuAuth.canView("user-manage")).isFalse();
    }

    @Test
    @DisplayName("MANAGER 는 권한 계층(MANAGER > DBA)으로 프로젝트 관리가 보이고, 사용자 관리는 숨겨진다")
    void manager() {
        SecurityTestSupport.loginAs(1L, "4", "ROLE_MANAGER");

        assertThat(menuAuth.canView("project-manage")).isTrue();
        assertThat(menuAuth.canView("physical-modeling")).isTrue();
        assertThat(menuAuth.canView("user-manage")).isFalse();
    }

    @Test
    @DisplayName("USER 는 컬럼 관리와 논리 모델 변경 버튼이 보이고 시스템 로그는 숨겨진다")
    void user() {
        SecurityTestSupport.loginAs(1L, "2", "ROLE_USER");

        assertThat(menuAuth.canView("column-manage")).isTrue();
        assertThat(menuAuth.canView("logical-model-edit")).isTrue();
        assertThat(menuAuth.canView("model-mapping")).isTrue();
        assertThat(menuAuth.canView("model-mapping-edit")).isTrue();
        assertThat(menuAuth.canView("system-log")).isFalse();
    }

    @Test
    @DisplayName("권한 계층 적용 : ADMIN 은 모든 메뉴를 볼 수 있다")
    void admin() {
        SecurityTestSupport.loginAsAdmin(1L);

        MenuAuthorization.MENU_ROLES.keySet().forEach(menuId ->
                assertThat(menuAuth.canView(menuId)).as(menuId).isTrue());

        MenuAuthorization.MENU_GROUPS.keySet().forEach(groupId ->
                assertThat(menuAuth.canView(groupId)).as(groupId).isTrue());

        assertThat(menuAuth.canView("physical-modeling")).isTrue();
    }

    @Test
    @DisplayName("로그인하지 않았거나 등록되지 않은 메뉴는 보이지 않는다")
    void anonymousOrUnknown() {
        assertThat(menuAuth.canView("dashboard")).isFalse();
        assertThat(menuAuth.canView("logical-modeling")).isFalse();

        // 없앤 메뉴 그룹 ID 는 더 이상 내려가지 않는다
        SecurityTestSupport.loginAsAdmin(1L);
        assertThat(menuAuth.canView("data-management")).isFalse();
        assertThat(menuAuth.canView("data-modeling")).isFalse();
        SecurityTestSupport.clear();

        SecurityTestSupport.loginAsAdmin(1L);
        assertThat(menuAuth.canView("unknown-menu")).isFalse();
    }
}
