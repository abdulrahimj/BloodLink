package com.bloodlink.bloodbank;

import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.bloodlink.domain.BloodBank;
import com.bloodlink.domain.User;
import com.bloodlink.domain.enums.Role;
import com.bloodlink.repository.BloodBankRepository;
import com.bloodlink.repository.UserRepository;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.NotFoundException;

/**
 * Answers one question for every action a blood bank takes: which bank is the
 * caller acting as?
 *
 * The rule is the same wherever it is asked, and is Business Rule 8 restated as
 * behaviour:
 *
 *   • BLOOD_BANK_STAFF act as the bank on their own User row and nothing else. Any
 *     bank id in the request body is ignored outright, so it cannot be used to act
 *     on another bank's behalf — the frontend is never the authority on this.
 *   • ADMIN belongs to no bank and so must name one. That is a 400 when they do not
 *     (an administrator may do this, they just have not said for whom), never a 403.
 *   • Everyone else is refused. Hospitals raise requests; they do not decide their
 *     own request's fate, and donors have no part in this at all.
 *
 * Extracted from RequestService when donation appeals became the second action
 * needing it. An access-control rule copied into two services is a rule that will
 * eventually be fixed in one of them and not the other, which is the failure mode
 * this class exists to make impossible.
 */
@Component
public class ActingBankResolver {

    private final UserRepository userRepository;
    private final BloodBankRepository bloodBankRepository;

    // Public rather than package-private (as most services here are) so a test in
    // another package can build a real resolver over mocked repositories, instead of
    // stubbing this rule out and losing the coverage it already has.
    public ActingBankResolver(UserRepository userRepository, BloodBankRepository bloodBankRepository) {
        this.userRepository = userRepository;
        this.bloodBankRepository = bloodBankRepository;
    }

    /**
     * @param requestedBankId the bank named in the request body, if any. Meaningful
     *                        only for an ADMIN; ignored entirely for bank staff.
     * @param principal       the signed-in caller.
     * @param action          what they are trying to do, phrased to complete the
     *                        sentence "Only blood bank staff can ..." — e.g.
     *                        "act on a blood request", "send a donation appeal".
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
}
