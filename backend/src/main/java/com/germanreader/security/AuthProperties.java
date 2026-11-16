package com.germanreader.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        @DefaultValue("") String jwtSecret,
        @DefaultValue("8h") Duration tokenTtl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("false") boolean allowGeneratedSecret
) {

    @Override
    public String toString() {
        return "AuthProperties[jwtSecret=***, tokenTtl=%s, cookieSecure=%s, allowGeneratedSecret=%s]"
                .formatted(tokenTtl, cookieSecure, allowGeneratedSecret);
    }
}
