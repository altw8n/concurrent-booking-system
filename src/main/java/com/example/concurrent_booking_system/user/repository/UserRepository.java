package com.example.concurrent_booking_system.user.repository;

import com.example.concurrent_booking_system.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}