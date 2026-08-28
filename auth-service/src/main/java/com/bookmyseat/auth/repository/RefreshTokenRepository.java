package com.bookmyseat.auth.repository;

import com.bookmyseat.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Derived delete: needs @Modifying + @Transactional to run as a bulk
     * statement rather than a no-op outside a transaction.
     */
    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
