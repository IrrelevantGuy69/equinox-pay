package com.equinox;

import com.equinox.core.entity.Role;
import com.equinox.core.entity.User;
import com.equinox.core.security.JwtService;
import com.equinox.core.security.SecurityUtil;
import com.equinox.core.service.FraudVelocityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class EdgeDefenseSecurityTest {

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private FraudVelocityService fraudVelocityService;

    @Test
    @DisplayName("Invariant 3: Asymmetric RS256 JWT token generation and validation")
    void testAsymmetricJwtGenerationAndValidation() {
        User user = User.builder()
                .username("test_engineer_" + UUID.randomUUID())
                .role(Role.MERCHANT)
                .build();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");
        request.addHeader("User-Agent", "FintechRunner/1.0");

        String fgp = securityUtil.generateDeviceFingerprint(request);
        Map<String, Object> claims = new HashMap<>();
        claims.put("fgp", fgp);

        String token = jwtService.generateToken(claims, user);
        assertNotNull(token);

        assertEquals(user.getUsername(), jwtService.extractUsername(token));
        assertTrue(jwtService.isTokenValid(token, user));

        String extractedFgp = jwtService.extractClaim(token, c -> c.get("fgp", String.class));
        assertEquals(fgp, extractedFgp);
    }

    @Test
    @DisplayName("Invariant 3: Device context fingerprint detects impossible travel / context changes")
    void testDeviceFingerprintMismatch() {
        MockHttpServletRequest originalReq = new MockHttpServletRequest();
        originalReq.setRemoteAddr("10.0.0.1");
        originalReq.addHeader("User-Agent", "SecureBrowser/1.0");

        MockHttpServletRequest hijackedReq = new MockHttpServletRequest();
        hijackedReq.setRemoteAddr("203.0.113.5");
        hijackedReq.addHeader("User-Agent", "UnknownBrowser/2.0");

        String originalFgp = securityUtil.generateDeviceFingerprint(originalReq);
        String hijackedFgp = securityUtil.generateDeviceFingerprint(hijackedReq);

        assertNotEquals(originalFgp, hijackedFgp);
    }

    @Test
    @DisplayName("Invariant 3: Redis-backed sliding-window fraud velocity engine blocks > 5 transactions/min")
    void testRedisFraudVelocityHighFrequency() {
        String testAccount = "acc_" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            boolean fraud = fraudVelocityService.isFraudulent(testAccount, "txn_" + i, new BigDecimal("10.00"));
            assertFalse(fraud, "First 5 transactions must be permitted");
        }

        boolean sixthTxn = fraudVelocityService.isFraudulent(testAccount, "txn_6", new BigDecimal("10.00"));
        assertTrue(sixthTxn, "6th transaction within 60s must be flagged as velocity abuse (429)");
    }

    @Test
    @DisplayName("Invariant 3: Redis-backed fraud velocity engine blocks volume > 10,000 in window")
    void testRedisFraudVelocityHighVolume() {
        String testAccount = "acc_vol_" + UUID.randomUUID();

        boolean firstTxn = fraudVelocityService.isFraudulent(testAccount, "txn_v1", new BigDecimal("5000.00"));
        assertFalse(firstTxn);

        boolean secondTxn = fraudVelocityService.isFraudulent(testAccount, "txn_v2", new BigDecimal("6000.00"));
        assertTrue(secondTxn, "Exceeding cumulative 10,000 volume limit must be blocked");
    }
}
