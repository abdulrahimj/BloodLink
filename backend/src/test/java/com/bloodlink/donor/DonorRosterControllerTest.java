package com.bloodlink.donor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bloodlink.domain.enums.Role;
import com.bloodlink.donor.dto.DonorRosterPageResponse;
import com.bloodlink.security.AuthenticatedUser;
import com.bloodlink.web.error.GlobalExceptionHandler;

/**
 * Query-parameter validation on {@code GET /api/donors/roster} (Business Rule
 * 21), the same standalone-MockMvc style as {@code AuditLogControllerTest} —
 * fast, no Spring context, no database, while still exercising the real
 * {@code @RequestParam} constraint annotations (Spring MVC's native method
 * validation is active in a standalone builder by default).
 *
 * Search behaviour (how a typed phone or name becomes a repository filter) and
 * the donationsHere/eligibility mapping are covered in DonorRosterServiceTest;
 * this class only has to prove the controller refuses what it should and
 * passes everything else through unchanged.
 */
@ExtendWith(MockitoExtension.class)
class DonorRosterControllerTest {

    @Mock private DonorRosterService donorRosterService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // A standalone builder registers none of Spring Security's own argument
        // resolvers by default (those come from the full web security
        // auto-configuration a real request goes through) — without this,
        // @AuthenticationPrincipal would not resolve at all.
        mockMvc = MockMvcBuilders.standaloneSetup(new DonorRosterController(donorRosterService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        // @AuthenticationPrincipal resolves from the security context in a real
        // request; standalone MockMvc has no filter chain to populate it, so a
        // principal is placed here directly — the controller's own role gate is
        // SecurityConfig's job (proved end to end in the integration test), not
        // this class's.
        AuthenticatedUser principal = new AuthenticatedUser(
            UUID.randomUUID(), "+23276900001", "hash", Role.BLOOD_BANK_STAFF);
        SecurityContextHolder.setContext(new SecurityContextImpl(
            new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_BLOOD_BANK_STAFF")))));
    }

    /** SecurityContextHolder is a thread-local shared across this JVM's test
     *  run; left set, it would leak into whichever test happens to share the
     *  thread next. */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static DonorRosterPageResponse emptyPage(int page, int size) {
        return new DonorRosterPageResponse(List.of(), page, size, 0, 0);
    }

    @Test
    void defaultsArePageZeroSizeTwentySortLastDonation() throws Exception {
        when(donorRosterService.listRoster(
            eq(0), eq(20), isNull(), isNull(), isNull(), eq("lastDonation"), isNull(), any()))
            .thenReturn(emptyPage(0, 20));

        mockMvc.perform(get("/api/donors/roster"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20));

        verify(donorRosterService).listRoster(
            eq(0), eq(20), isNull(), isNull(), isNull(), eq("lastDonation"), isNull(), any());
    }

    @Test
    void sizeOfExactlyOneHundredIsAccepted() throws Exception {
        when(donorRosterService.listRoster(
            eq(0), eq(100), isNull(), isNull(), isNull(), eq("lastDonation"), isNull(), any()))
            .thenReturn(emptyPage(0, 100));

        mockMvc.perform(get("/api/donors/roster").param("size", "100"))
            .andExpect(status().isOk());
    }

    @Test
    void sizeOfOneHundredAndOneIsRefused() throws Exception {
        mockMvc.perform(get("/api/donors/roster").param("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.size").exists());
    }

    @Test
    void aNegativePageIsRefused() throws Exception {
        mockMvc.perform(get("/api/donors/roster").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.page").exists());
    }

    @Test
    void aNonNumericPageIsRefusedNamingTheField() throws Exception {
        mockMvc.perform(get("/api/donors/roster").param("page", "not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.page").exists());
    }

    @Test
    void anUnrecognisedSortValueIsRefused() throws Exception {
        mockMvc.perform(get("/api/donors/roster").param("sort", "oldest"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.sort").exists());
    }

    @Test
    void anUnrecognisedEligibilityValueIsRefused() throws Exception {
        mockMvc.perform(get("/api/donors/roster").param("eligibility", "MAYBE"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.eligibility").exists());
    }

    @Test
    void anUnrecognisedBloodGroupIsRefused() throws Exception {
        mockMvc.perform(get("/api/donors/roster").param("bloodType", "Z+"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.bloodType").exists());
    }

    @Test
    void aValidBloodGroupEligibilityAndSortArePassedThroughUnchanged() throws Exception {
        when(donorRosterService.listRoster(
            eq(1), eq(50), eq("Kamara"), eq("O-"), eq("ELIGIBLE"), eq("name"), any(), any()))
            .thenReturn(emptyPage(1, 50));

        mockMvc.perform(get("/api/donors/roster")
                .param("page", "1").param("size", "50").param("q", "Kamara")
                .param("bloodType", "O-").param("eligibility", "ELIGIBLE").param("sort", "name"))
            .andExpect(status().isOk());
    }
}
