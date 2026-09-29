package com.docgen.onlyoffice;

import com.docgen.config.OnlyOfficeProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Signs the editor-config token OnlyOffice requires, and verifies the token
 * it sends back on each save callback — both no-ops when
 * {@code app.onlyoffice.jwt-secret} is unset (local-dev only; production
 * must set it to match the Document Server's own JWT_SECRET).
 */
@Service
public class OnlyOfficeJwtService {

    private final OnlyOfficeProperties properties;
    private final SecretKey key;

    public OnlyOfficeJwtService(OnlyOfficeProperties properties) {
        this.properties = properties;
        this.key = properties.jwtEnabled()
                ? Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8))
                : null;
    }

    /** Signs the whole editor config object, as OnlyOffice's JS API expects. */
    public String signConfig(Map<String, Object> config) {
        if (!properties.jwtEnabled()) {
            return null;
        }
        return Jwts.builder().claims(config).signWith(key).compact();
    }

    /**
     * Verifies a callback token (from the {@code Authorization} header or the
     * body's {@code token} field). Returns true when JWT is disabled.
     */
    public boolean verifyCallbackToken(String token) {
        if (!properties.jwtEnabled()) {
            return true;
        }
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(stripBearer(token));
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /** For extracting a nested-claim JWT (OnlyOffice wraps the payload under "payload" in some versions). */
    public Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(stripBearer(token)).getPayload();
    }

    private static String stripBearer(String token) {
        return token.startsWith("Bearer ") ? token.substring(7) : token;
    }
}
