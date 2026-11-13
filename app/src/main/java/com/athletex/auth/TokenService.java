package com.athletex.auth;

import java.time.Duration;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final JwtEncoder jwtEncoder;
    private final AdminDirectory admins;

    public TokenService(JwtEncoder jwtEncoder, AdminDirectory admins) {
        this.jwtEncoder = jwtEncoder;
        this.admins = admins;
    }

    public String issue(UserEntity user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder builder = JwtClaimsSet.builder()
                .issuer("athlete-exchange")
                .subject(user.getId().toString())
                .claim("username", user.getUsername());
        if (admins.isAdmin(user.getUsername())) {
            builder.claim("scope", "admin"); // mapped to the SCOPE_admin authority by the resource server
        }
        JwtClaimsSet claims = builder
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
