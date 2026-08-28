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
 * <h2>TODO - api-gateway</h2>
 * X-User-Role is expected to be injected by api-gateway after it validates the
 * auth-service JWT and reads the role claim. api-gateway does not exist yet.
 *
 * <p><b>When it is built, the security of this endpoint depends on one thing that
 * is easy to get wrong.</b> The gateway must <i>strip</i> any client-supplied
 * X-User-Role from the inbound request before setting its own. A gateway that only
 * sets the header - the obvious implementation - leaves it trivially spoofable,
 * because a caller can simply send their own and, depending on the proxy, have it
 * survive or arrive as a second value. Strip first, then set.
 *
 * <p><b>Until then this is not authentication.</b> Anyone who can reach port 8082
 * is an admin for the price of one curl flag:
 * {@code curl -H "X-User-Role: ADMIN" http://localhost:8082/api/admin/venues}.
 * event-service must not be exposed beyond the developer's machine in this state,
 * and this class is not a substitute for the gateway - it is the half that trusts
 * the gateway to have done its job.
 *
 * <p>Deliberately not Spring Security: reading one header does not justify pulling
 * a security starter and a filter chain into this service, and the real decision
 * will move to the gateway anyway.
 */
public class AdminRoleInterceptor implements HandlerInterceptor {

    /** Set by api-gateway from the validated JWT. See the class TODO. */
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
