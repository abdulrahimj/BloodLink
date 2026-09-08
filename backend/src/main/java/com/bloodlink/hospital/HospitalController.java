package com.bloodlink.hospital;

import java.util.List;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.hospital.dto.HospitalResponse;
import com.bloodlink.repository.HospitalRepository;

/**
 * The fixed hospital reference list, seeded by V6 and never self-registering.
 *
 * Deliberately a controller with no service behind it: unlike blood banks there is
 * no aggregate view or business rule attached to a hospital yet — this is a plain
 * projection of two seeded rows, and inventing a HospitalService to pass it through
 * would add a layer with nothing in it. Open to any signed-in role (SecurityConfig),
 * because the Hospital Portal's bank-unassigned fallback picker and any future
 * admin screen both need to know what hospitals exist.
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
