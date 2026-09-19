package com.bloodlink.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.admin.dto.CreateFacilityPayload;
import com.bloodlink.bloodbank.dto.BloodBankResponse;
import com.bloodlink.hospital.dto.HospitalResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Adding blood banks and hospitals — Business Rule 19.
 *
 * Only POST lives here. Listing stays on the existing public-to-any-signed-in-role
 * GET /api/blood-banks and GET /api/hospitals, which the Admin Console's Blood
 * Banks and Hospitals pages read just as every picker in the app already does; a
 * second, admin-only listing would be a copy of the same data. There is no PATCH
 * or DELETE: editing or removing a facility is deliberately out of scope.
 *
 * No role check in this class: SecurityConfig gates all of /api/admin/** on ADMIN,
 * so any other token is refused with a 403 in the filter chain before reaching it.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminFacilityController {

    private final AdminFacilityService adminFacilityService;

    AdminFacilityController(AdminFacilityService adminFacilityService) {
        this.adminFacilityService = adminFacilityService;
    }

    /** Creates a blood bank. 201 with the new row, in GET /api/blood-banks' shape. */
    @PostMapping("/blood-banks")
    public ResponseEntity<BloodBankResponse> createBloodBank(
        @Valid @RequestBody CreateFacilityPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminFacilityService.createBloodBank(payload, principal));
    }

    /** Creates a hospital. 201 with the new row, in GET /api/hospitals' shape. */
    @PostMapping("/hospitals")
    public ResponseEntity<HospitalResponse> createHospital(
        @Valid @RequestBody CreateFacilityPayload payload,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminFacilityService.createHospital(payload, principal));
    }
}
