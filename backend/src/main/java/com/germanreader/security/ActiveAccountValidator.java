package com.germanreader.security;

import com.germanreader.repository.AppUserRepository;
import java.util.UUID;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class ActiveAccountValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error UNKNOWN_ACCOUNT =
            new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "The account no longer exists", null);

    private final AppUserRepository users;

    public ActiveAccountValidator(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        try {
            return users.existsById(UUID.fromString(token.getSubject()))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(UNKNOWN_ACCOUNT);
        } catch (IllegalArgumentException | NullPointerException e) {
            return OAuth2TokenValidatorResult.failure(UNKNOWN_ACCOUNT);
        }
    }
}
