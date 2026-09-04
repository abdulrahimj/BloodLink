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
 * Seeded, not self-registering: "Makeni Regional Blood Bank", "Holy Spirit Blood Bank".
 */
@Entity
@Table(name = "blood_banks")
@Getter
@Setter
@NoArgsConstructor
public class BloodBank {

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
