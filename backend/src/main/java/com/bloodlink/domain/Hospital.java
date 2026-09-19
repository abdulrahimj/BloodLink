package com.bloodlink.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Seeded (V6): "Makeni Regional Hospital", "Holy Spirit Hospital". Never
 * self-registering; further hospitals are added only by an administrator
 * (POST /api/admin/hospitals, Business Rule 19). Name is unique, case-insensitively (V14).
 */
@Entity
@Table(name = "hospitals")
@Getter
@Setter
@NoArgsConstructor
public class Hospital {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "location", nullable = false)
    private String location;

    @Column(name = "contact_phone", nullable = false)
    private String contactPhone;
}
