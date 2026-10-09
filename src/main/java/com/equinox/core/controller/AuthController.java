package com.equinox.core.controller;

import com.equinox.core.dto.AuthenticationRequest;
import com.equinox.core.dto.AuthenticationResponse;
import com.equinox.core.dto.RegisterRequest;
import com.equinox.core.entity.RefreshToken;
import com.equinox.core.repository.UserRepository;
import com.equinox.core.service.AuthService;
import com.equinox.core.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<AuthenticationResponse> register(@RequestBody RegisterRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        AuthenticationResponse authResponse = authService.register(request, httpRequest);

        var user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());

        setRefreshTokenCookie(response, refreshToken.getToken());
        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/authenticate")
    public ResponseEntity<AuthenticationResponse> authenticate(@RequestBody AuthenticationRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        AuthenticationResponse authResponse = authService.authenticate(request, httpRequest);

        var user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user.getId());

        setRefreshTokenCookie(response, refreshToken.getToken());
        return ResponseEntity.ok(authResponse);
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthenticationResponse> refreshToken(HttpServletRequest request) {
        String refreshTokenString = getRefreshTokenFromCookies(request);

        if (refreshTokenString == null) {
            throw new RuntimeException("Refresh token is missing");
        }

        return refreshTokenService.findByToken(refreshTokenString)
                .map(refreshTokenService::verifyExpiration)
                .map(RefreshToken::getUser)
                .map(user -> {
                    AuthenticationResponse authResponse = authService.generateNewAccessToken(user.getUsername(), request);
                    return ResponseEntity.ok(authResponse);
                })
                .orElseThrow(() -> new RuntimeException("Refresh token invalid"));
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String token) {
        Cookie cookie = new Cookie("refresh_token", token);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/api/v2/auth/refresh");
        cookie.setMaxAge(7 * 24 * 60 * 60);
        response.addCookie(cookie);
    }

    private String getRefreshTokenFromCookies(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (cookie.getName().equals("refresh_token")) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}