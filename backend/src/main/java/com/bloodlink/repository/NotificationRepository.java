package com.bloodlink.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bloodlink.domain.Notification;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
}
