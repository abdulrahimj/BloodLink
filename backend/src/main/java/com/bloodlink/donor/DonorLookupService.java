package com.bloodlink.donor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bloodlink.common.PhoneNumber;
import com.bloodlink.donor.dto.DonorLookupResponse;
import com.bloodlink.repository.DonorRepository;
import com.bloodlink.web.error.NotFoundException;

/**
 * Finds the donor a blood bank staff member is about to record a donation for.
 *
 * Separate from {@link DonorService} on purpose. That class is the donor's own
 * read side and states plainly that nothing in it resolves a donor other than
 * the caller — this is the one lookup that does, so it lives apart rather than
 * quietly breaking that guarantee.
 *
 * Exact phone match, and only phone. There is no "list all donors" and no
 * partial-name search here, which keeps this from becoming a way to browse the
 * donor register: a staff member has to already know the number of the person
 * in front of them, which they do, because that person is standing there.
 */
@Service
public class DonorLookupService {

    private final DonorRepository donorRepository;

    DonorLookupService(DonorRepository donorRepository) {
        this.donorRepository = donorRepository;
    }

    /**
     * The donor registered against this phone number.
     *
     * A 404 rather than an empty result: the caller asked about a specific
     * person, and "no such donor" is a dead end they need to act on (check the
     * number, or register them first), not a list that happens to be empty.
     *
     * The number typed is normalised through the same {@link PhoneNumber#normalize}
     * registration stored it with, so the match is between two values in one format.
     * Without this, the staff member would have to guess which of the accepted
     * formats that donor happened to sign up in — and after V11 converted every
     * stored number to the international form, typing the local form here would
     * have found nobody at all. A number that cannot be normalised is a 400 naming
     * the phone field rather than this method's 404: "that is not a phone number"
     * and "no donor has that number" are different problems with different fixes.
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
