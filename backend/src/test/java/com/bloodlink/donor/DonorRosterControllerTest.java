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
 * Query-parameter validation on GET /api/donors/roster (Business Rule 21) in standalone MockMvc.
 * Search and mapping behaviour are tested in DonorRosterServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class DonorRosterControllerTest {

    @Mock private DonorRosterService donorRosterService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Standalone MockMvc has no Spring Security resolvers, so @AuthenticationPrincipal needs this to resolve.
        mockMvc = MockMvcBuilders.standaloneSetup(new DonorRosterController(donorRosterService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .build();

        // No filter chain in standalone MockMvc, so the principal is placed in the context directly;
        // role gating is proven in the integration test.
        AuthenticatedUser principal = new AuthenticatedUser(
            UUID.randomUUID(), "+23276900001", "hash", Role.BLOOD_BANK_STAFF);
        SecurityContextHolder.setContext(new SecurityContextImpl(
            new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority("ROLE_BLOOD_BANK_STAFF")))));
    }

    /** SecurityContextHolder is thread-local; clear it so it doesn't leak into the next test. */
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
