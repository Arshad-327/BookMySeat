package com.bookmyseat.auth.exception;

/**
 * No user with that id. Mapped to <b>404</b> by GlobalExceptionHandler.
 *
 * <h2>404 here, where /api/auth/me answers 401 for the same condition</h2>
 * {@code AuthService#getCurrentUser} throws {@link InvalidCredentialsException} - a 401 - when
 * the user behind a valid token no longer exists. That is right for a <i>client</i>: it was
 * told its credential is no longer good for anything.
 *
 * <p>It is wrong for a <i>service</i> asking about a third party. A caller of
 * /api/internal/users/{id} is not presenting a credential at all, so "unauthorised" describes
 * nothing that happened; the accurate answer is that the resource is absent.
 *
 * <p>The distinction is load-bearing downstream, not cosmetic. notification-service
 * classifies this endpoint's failures to decide whether a confirmation email can ever be
 * sent: 404 means the user is deleted and the message is permanently undeliverable, while
 * 401/403 means notification-service itself is misconfigured and a human must fix it. Were a
 * missing user to answer 401, every deleted user would be logged - and chased - as a
 * configuration fault.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long userId) {
        super("User " + userId + " not found");
    }
}
