package com.equinox.core.service;

import com.equinox.core.dto.AuthenticationRequest;
import com.equinox.core.dto.AuthenticationResponse;
import com.equinox.core.dto.RegisterRequest;
import com.equinox.core.entity.Account;
import com.equinox.core.entity.User;
import com.equinox.core.repository.UserRepository;
import com.equinox.core.security.JwtService;
import com.equinox.core.security.SecurityUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final SecurityUtil securityUtil;

    @Transactional
    public AuthenticationResponse register(RegisterRequest request, HttpServletRequest httpRequest) {
        Account newAccount = new Account();
        newAccount.setBalance(new BigDecimal("1000.00"));


        User user = User.builder()
                .username(request.getUsername())
                .password(passwordEncoder.encode(request.getPassword()))
                .role(request.getRole() != null ? request.getRole() : com.equinox.core.entity.Role.USER)
                .account(newAccount)
                .build();

        userRepository.save(user);

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String jwtToken = jwtService.generateToken(buildClaims(httpRequest), userDetails);

        return AuthenticationResponse.builder().token(jwtToken).build();
    }

    public AuthenticationResponse authenticate(AuthenticationRequest request, HttpServletRequest httpRequest) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        UserDetails userDetails = userDetailsService.loadUserByUsername(request.getUsername());
        String jwtToken = jwtService.generateToken(buildClaims(httpRequest), userDetails);

        return AuthenticationResponse.builder().token(jwtToken).build();
    }

    public AuthenticationResponse generateNewAccessToken(String username, HttpServletRequest httpRequest) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        String jwtToken = jwtService.generateToken(buildClaims(httpRequest), userDetails);
        return AuthenticationResponse.builder().token(jwtToken).build();
    }

    private Map<String, Object> buildClaims(HttpServletRequest request) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("fgp", securityUtil.generateDeviceFingerprint(request));
        return claims;
    }
}