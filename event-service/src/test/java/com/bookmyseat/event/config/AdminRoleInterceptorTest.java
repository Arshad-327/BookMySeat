package com.bookmyseat.event.config;

import com.bookmyseat.event.exception.ForbiddenException;
import com.bookmyseat.event.exception.MissingRoleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminRoleInterceptorTest {

    private final AdminRoleInterceptor interceptor = new AdminRoleInterceptor();

    @Test
    @DisplayName("allows the request when the role header is ADMIN")
    void allowsAdmin() {
        assertThat(preHandleWithRole("ADMIN")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "Admin", " ADMIN "})
    @DisplayName("role match is case-insensitive and trimmed")
    void allowsAdminRegardlessOfCaseAndPadding(String role) {
        assertThat(preHandleWithRole(role)).isTrue();
    }

    @Test
    @DisplayName("absent header is 401, not 403: the caller sent no identity at all")
    void rejectsAbsentHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/venues");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(MissingRoleException.class)
                .hasMessageContaining(AdminRoleInterceptor.ROLE_HEADER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("blank header counts as absent")
    void rejectsBlankHeader(String role) {
        assertThatThrownBy(() -> preHandleWithRole(role))
                .isInstanceOf(MissingRoleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ADMINISTRATOR", "ADMIN_READONLY", "ROLE_ADMIN"})
    @DisplayName("a non-ADMIN role is 403, including near-misses that must not match")
    void rejectsNonAdminRole(String role) {
        assertThatThrownBy(() -> preHandleWithRole(role))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ADMIN");
    }

    private boolean preHandleWithRole(String role) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/venues");
        request.addHeader(AdminRoleInterceptor.ROLE_HEADER, role);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }
}
