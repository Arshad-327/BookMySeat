package com.bookmyseat.auth.mapper;

import com.bookmyseat.auth.dto.response.InternalUserResponse;
import com.bookmyseat.auth.dto.response.UserResponse;
import com.bookmyseat.auth.entity.User;

/**
 * Hand-written mapping, per CLAUDE.md. No mapping framework.
 */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole().name(),
                user.getCreatedAt()
        );
    }

    /**
     * The narrow projection served to other services, for GET /api/internal/users/{id}.
     *
     * <p>Kept as its own method rather than a filter over {@link #toResponse}, so that the
     * fields an unauthenticated endpoint discloses are written out in one place and can be
     * read at a glance. See {@link InternalUserResponse} for why reuse would be the wrong
     * kind of brevity.
     */
    public static InternalUserResponse toInternalResponse(User user) {
        return new InternalUserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName()
        );
    }
}
