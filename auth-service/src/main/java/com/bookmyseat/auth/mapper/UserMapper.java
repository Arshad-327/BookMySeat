package com.bookmyseat.auth.mapper;

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
}
