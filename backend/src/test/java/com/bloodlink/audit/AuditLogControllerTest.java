package com.bloodlink.audit;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.bloodlink.audit.dto.AuditLogPageResponse;
import com.bloodlink.web.error.GlobalExceptionHandler;

/**
 * Audit-log pagination in standalone MockMvc with the real @RequestParam constraints and error handler.
 * Page rendering is tested in auditLogPaging.test.js; the database round trip in AdminEditAndAuditLogIT.
 */
@ExtendWith(MockitoExtension.class)
class AuditLogControllerTest {

    @Mock private AuditLogService auditLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditLogController(auditLogService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static AuditLogPageResponse emptyPage(int page, int size, long totalElements, int totalPages) {
        return new AuditLogPageResponse(List.of(), page, size, totalElements, totalPages);
    }

    @Test
    void noQueryParametersMeansPageZeroAndSizeTwenty() throws Exception {
        when(auditLogService.list(0, 20)).thenReturn(emptyPage(0, 20, 0, 0));

        mockMvc.perform(get("/api/admin/audit-log"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(20));

        verify(auditLogService).list(0, 20);
    }

    @Test
    void sizeOfExactlyOneHundredIsAccepted() throws Exception {
        when(auditLogService.list(0, 100)).thenReturn(emptyPage(0, 100, 0, 0));

        mockMvc.perform(get("/api/admin/audit-log").param("size", "100"))
            .andExpect(status().isOk());

        verify(auditLogService).list(eq(0), eq(100));
    }

    @Test
    void sizeOfOneHundredAndOneIsRefused() throws Exception {
        mockMvc.perform(get("/api/admin/audit-log").param("size", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.size").exists());
    }

    @Test
    void sizeOfZeroIsRefused() throws Exception {
        mockMvc.perform(get("/api/admin/audit-log").param("size", "0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.size").exists());
    }

    @Test
    void aNegativePageIsRefused() throws Exception {
        mockMvc.perform(get("/api/admin/audit-log").param("page", "-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.page").exists());
    }

    @Test
    void aNonNumericPageIsRefusedNamingTheField() throws Exception {
        mockMvc.perform(get("/api/admin/audit-log").param("page", "not-a-number"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.page").exists());
    }

    @Test
    void aNonNumericSizeIsRefusedNamingTheField() throws Exception {
        mockMvc.perform(get("/api/admin/audit-log").param("size", "lots"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors.size").exists());
    }

    @Test
    void badInputIsNeverSilentlyCorrected() throws Exception {
        // A request that fails validation must never fall through to the service
        // with some "corrected" value substituted in its place.
        mockMvc.perform(get("/api/admin/audit-log").param("size", "500"))
            .andExpect(status().isBadRequest());

        verify(auditLogService, never()).list(anyInt(), anyInt());
    }

    @Test
    void aValidPageAndSizeArePassedThroughUnchanged() throws Exception {
        when(auditLogService.list(2, 50)).thenReturn(emptyPage(2, 50, 120, 3));

        mockMvc.perform(get("/api/admin/audit-log").param("page", "2").param("size", "50"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(2))
            .andExpect(jsonPath("$.size").value(50))
            .andExpect(jsonPath("$.totalElements").value(120))
            .andExpect(jsonPath("$.totalPages").value(3));

        verify(auditLogService).list(2, 50);
    }
}
