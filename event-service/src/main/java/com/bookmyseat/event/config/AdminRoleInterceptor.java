package com.bookmyseat.event.config;

import com.bookmyseat.event.exception.ForbiddenException;
import com.bookmyseat.event.exception.MissingRoleException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Gates /api/admin/** on a caller role carried in a request header.
 *
 * <h2>Where X-User-Role comes from, and why port 8082 must never be exposed</h2>
 * Through api-gateway, X-User-Role is injected from a validated access token. The
 * gateway's JwtAuthenticationFilter strips every client-supplied X-User-* header, in any
 * letter case, then sets X-User-Role from the token's role claim. A request that arrives
 * through the gateway cannot choose its own role.
 *
 * <p><b>A client reaching port 8082 directly bypasses all of that.</b> This class checks
 * the header's value, not where it came from, so a direct caller is an admin for the
 * price of one curl flag:
 * {@code curl -H "X-User-Role: ADMIN" http://localhost:8082/api/admin/venues}.
 * That is why 8081-8083 must never be exposed outside the internal network. This class
 * is not a substitute for the gateway - it is the half that trusts the gateway to have
 * done its job.
 *
 * <p>Deliberately not Spring Security: reading one header does not justify pulling
 * a security starter and a filter chain into this service, and the real decision
 * - is this token valid - is made at the gateway.
 */
public class AdminRoleInterceptor implements HandlerInterceptor {

    /** Set by api-gateway from the validated JWT. See the class note on direct callers. */
    public static final String ROLE_HEADER = "X-User-Role";

    public static final String REQUIRED_ROLE = "ADMIN";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String role = request.getHeader(ROLE_HEADER);

        // Absent and wrong are reported differently: absent means the caller sent
        // no identity at all (401), wrong means they sent one that is not
        // permitted (403). Collapsing both to 403 hides a misrouted gateway.
        if (!StringUtils.hasText(role)) {
            throw new MissingRoleException(ROLE_HEADER);
        }
        if (!REQUIRED_ROLE.equalsIgnoreCase(role.trim())) {
            throw new ForbiddenException(REQUIRED_ROLE);
        }
        return true;
    }
}
