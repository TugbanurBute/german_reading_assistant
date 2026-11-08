package com.germanreader.repository;

import com.germanreader.entity.AppUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    /** The email must already be normalized (trimmed, lower-case), as it is stored. */
    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);
}
