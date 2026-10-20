package com.bloodlink.donor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.common.PhoneNumber;
import com.bloodlink.donor.dto.DonorLookupResponse;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Finds the donor a bank staff member is about to record a donation for; separate from DonorService,
 * which only resolves the caller. Exact phone match only, so it cannot be used to browse the register.
 */
@Service
public class DonorLookupService {

    private final DonorRepository donorRepository;

    DonorLookupService(DonorRepository donorRepository) {
        this.donorRepository = donorRepository;
    }

    /**
     * The donor registered against this phone (404 if none), matched after PhoneNumber.normalize, so either format works.
     * A number that cannot be normalised is a 400 on phone, a different problem from no match.
     */
    @Transactional(readOnly = true)
    public DonorLookupResponse lookupByPhone(String phone) {
        return donorRepository.findByUserPhoneWithUser(PhoneNumber.normalize(phone))
            .map(DonorLookupResponse::from)
            .orElseThrow(() -> new NotFoundException(
                "No donor is registered with that phone number. Check the number as it was registered, "
                    + "or register the donor first."
            ));
    }
}
