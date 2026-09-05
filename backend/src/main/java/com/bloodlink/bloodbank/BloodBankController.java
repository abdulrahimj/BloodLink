package com.bloodlink.bloodbank;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.bloodbank.dto.BloodBankResponse;
import com.bloodlink.bloodbank.dto.DashboardResponse;

/**
 * Blood bank reference data and the Blood Bank Dashboard's aggregate view.
 * SecurityConfig opens the plain listing to every signed-in role (hospitals and
 * donors both need to know what banks exist) and reserves /dashboard for
 * BLOOD_BANK_STAFF and ADMIN.
 */
@RestController
@RequestMapping("/api/blood-banks")
public class BloodBankController {

    private final BloodBankService bloodBankService;

    BloodBankController(BloodBankService bloodBankService) {
        this.bloodBankService = bloodBankService;
    }

    /** The two seeded banks (or however many exist) — for pickers and read-only display. */
    @GetMapping
    public List<BloodBankResponse> listBanks() {
        return bloodBankService.listAll();
    }

    /** Stat cards and the requests panel for the Blood Bank Dashboard home view. */
    @GetMapping("/{bloodBankId}/dashboard")
    public DashboardResponse dashboard(@PathVariable UUID bloodBankId) {
        return bloodBankService.getDashboard(bloodBankId);
    }
}
