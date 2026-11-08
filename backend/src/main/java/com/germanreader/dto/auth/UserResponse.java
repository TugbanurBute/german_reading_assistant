package com.germanreader.dto.auth;

import com.germanreader.entity.AppUser;
import java.util.UUID;

public record UserResponse(UUID id, String email) {

    public static UserResponse from(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail());
    }
}
