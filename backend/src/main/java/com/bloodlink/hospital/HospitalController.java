package com.bloodlink.hospital;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.hospital.dto.HospitalResponse;
import com.bloodlink.repository.HospitalRepository;

/**
 * The hospital reference list, readable by any signed-in role; added to only by an administrator (Business Rule 19).
 * No service behind it: there is no business logic to put there.
 */
@RestController
@RequestMapping("/api/hospitals")
public class HospitalController {

    private final HospitalRepository hospitalRepository;

    HospitalController(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<HospitalResponse> listHospitals() {
        return hospitalRepository.findAllByOrderByNameAsc().stream()
            .map(hospital -> new HospitalResponse(
                hospital.getId(), hospital.getName(), hospital.getLocation(), hospital.getContactPhone()))
            .toList();
    }
}
