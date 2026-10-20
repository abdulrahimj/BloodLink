package com.bloodlink.bloodbank;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.AccountStatus;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * Which bank is the caller acting as (Business Rule 8)? Bank staff: their own bank, any id in the body ignored;
 * ADMIN must name one (resolve, no-blood actions only); resolveForStockChange has no ADMIN branch; others refused.
 */
@Component
public class ActingBankResolver {

    private final UserRepository userRepository;
    private final BloodBankRepository bloodBankRepository;

    // Public so tests in other packages can build a real resolver over mocked repositories.
    public ActingBankResolver(UserRepository userRepository, BloodBankRepository bloodBankRepository) {
        this.userRepository = userRepository;
        this.bloodBankRepository = bloodBankRepository;
    }

    /**
     * requestedBankId: the bank in the body, used only for an ADMIN; principal: the caller;
     * action completes "Only blood bank staff can ...".
     */
    public BloodBank resolve(UUID requestedBankId, AuthenticatedUser principal, String action) {
        if (principal.getRole() == Role.BLOOD_BANK_STAFF) {
            User user = userRepository.findById(principal.getId())
                .orElseThrow(() -> new NotFoundException("Account not found."));
            if (user.getBloodBank() == null) {
                // An account no administrator has attached to a bank yet has access
                // to nothing, rather than to everything or to an arbitrary default.
                throw new AccessDeniedException("This account is not assigned to a blood bank.");
            }
            return user.getBloodBank();
        }

        if (principal.getRole() == Role.ADMIN) {
            if (requestedBankId == null) {
                throw new IllegalArgumentException("A blood bank must be named when acting as an administrator.");
            }
            return bloodBankRepository.findById(requestedBankId)
                .orElseThrow(() -> new NotFoundException("No blood bank found for this id."));
        }

        throw new AccessDeniedException("Only blood bank staff can " + action + ".");
    }

    /**
     * The bank whose stock the caller is changing (Add New Batch, write-off, Record Donation, CONTRIBUTE): active
     * BLOOD_BANK_STAFF only, acting as their own bank, never ADMIN; the role is checked before anything is read.
     */
    public BloodBank resolveForStockChange(AuthenticatedUser principal, String action) {
        refuseUnlessBankStaff(principal, action);
        User user = userRepository.findById(principal.getId())
            .orElseThrow(() -> new NotFoundException("Account not found."));
        if (user.getStatus() != AccountStatus.ACTIVE) {
            throw new AccessDeniedException("This account is suspended.");
        }
        if (user.getBloodBank() == null) {
            throw new AccessDeniedException("This account is not assigned to a blood bank.");
        }
        return user.getBloodBank();
    }

    /**
     * The role half of resolveForStockChange alone, reading nothing, for callers that must refuse before any lookup.
     */
    public void refuseUnlessBankStaff(AuthenticatedUser principal, String action) {
        if (principal.getRole() != Role.BLOOD_BANK_STAFF) {
            throw new AccessDeniedException("Only the blood bank's own staff can " + action + ".");
        }
    }
}
