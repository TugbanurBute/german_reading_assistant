package com.germanreader.service;

import com.germanreader.dto.auth.RegisterRequest;
import com.germanreader.entity.AppUser;
import com.germanreader.exception.AuthException;
import com.germanreader.exception.ErrorCode;
import com.germanreader.repository.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login. Logs user ids only: never emails, passwords or tokens.
 */
@Slf4j
@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AppUser register(String email, String password) {
        if (tooLong(password)) {
            throw new AuthException(ErrorCode.VALIDATION_ERROR, "Password longer than 72 bytes");
        }
        String normalized = normalizeEmail(email);
        if (users.existsByEmail(normalized)) {
            throw new AuthException(ErrorCode.EMAIL_TAKEN, "Email already registered");
        }
        try {
            AppUser user = users.saveAndFlush(new AppUser(normalized, passwordEncoder.encode(password)));
            log.info("Registered user {}", user.getId());
            return user;
        } catch (DataIntegrityViolationException e) {
            // The same email registered at the same moment (unique index).
            throw new AuthException(ErrorCode.EMAIL_TAKEN, "Email already registered");
        }
    }

    /** A wrong password and an unknown email give the same answer. */
    @Transactional(readOnly = true)
    public AppUser login(String email, String password) {
        return users.findByEmail(normalizeEmail(email))
                // Registration refuses passwords over 72 bytes, and BCrypt can't check them.
                .filter(user -> !tooLong(password) && passwordEncoder.matches(password, user.getPasswordHash()))
                .orElseThrow(() -> new AuthException(ErrorCode.INVALID_CREDENTIALS, "Login failed"));
    }

    @Transactional(readOnly = true)
    public AppUser currentUser(UUID id) {
        return users.findById(id).orElseThrow(() -> new AuthException(ErrorCode.UNAUTHENTICATED, "Token for unknown user"));
    }


    @Transactional
    public void deleteAccount(UUID id, String password) {
        AppUser user = currentUser(id);
        if (tooLong(password) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new AuthException(ErrorCode.WRONG_PASSWORD, "Wrong password for account deletion");
        }
        users.delete(user);
        users.flush();
        log.info("Deleted user {} and all their data", id);
    }

    private static boolean tooLong(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > RegisterRequest.MAX_PASSWORD_BYTES;
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
