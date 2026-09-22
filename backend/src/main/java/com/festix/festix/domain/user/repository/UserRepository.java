package com.festix.festix.domain.user.repository;

import com.festix.festix.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByCustomId(String customId);
}
