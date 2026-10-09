package com.equinox.core.repository;

import com.equinox.core.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    // Spring Data JPA will automatically join the tables and find the User by their Account ID
    java.util.Optional<User> findByAccountId(java.util.UUID accountId);
}
