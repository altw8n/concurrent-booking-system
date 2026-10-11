package com.example.concurrent_booking_system.event.repository;

import com.example.concurrent_booking_system.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<Event, Long> {
}