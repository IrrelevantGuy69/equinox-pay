package com.equinox.core.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(unique = true)
    private String apiKey;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "account_id", referencedColumnName = "id")
    private Account account;

    // The external URL where the merchant listens for payment events
    // e.g., "https://coolhoodies.com/api/equinox-webhooks"
    @Column(name = "webhook_url")
    private String webhookUrl;

    // The cryptographic key used to sign the webhook payloads
    @Column(name = "webhook_secret")
    private String webhookSecret;

    // ========================================================
    // SPRING SECURITY USERDETAILS METHODS
    // ========================================================

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // If the role is null (from old database entries), default to USER
        if (this.role == null) {
            return List.of(new SimpleGrantedAuthority("ROLE_USER"));
        }
        // Properly attach the "ROLE_" prefix that Spring Security expects
        return List.of(new SimpleGrantedAuthority("ROLE_" + this.role.name()));
    }

    // You can customize these later, but for now we leave them true so the account isn't locked
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
