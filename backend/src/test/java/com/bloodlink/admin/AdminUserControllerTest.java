package com.bloodlink.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

import com.bloodlink.admin.dto.AdminUserPageResponse;
import com.bloodlink.web.error.GlobalExceptionHandler;

/**
 * Query-parameter validation on GET /api/admin/users in standalone MockMvc (real constraints and error handler).
 * Access control is proven in AdminUserSearchIT.
 */
@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock private AdminUserService adminUserService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserController(adminUserService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    private static AdminUserPageResponse emptyPage(int page, int size) {
        return new AdminUserPageResponse(List.of(), page, size, 0, 0);
    }

    @Test
    void defaultsArePageZeroSizeThirteenSortedByNameAscending() throws Exception {
        when(adminUserService.searchUsers(0, 13, null, null, null, "name", "asc")).thenReturn(emptyPage(0, 13));

        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(13))
            .andExpect(jsonPath("$.content").isArray());

        verify(adminUserService).searchUsers(0, 13, null, null, null, "name", "asc");
    }

    @Test
    void sizeOfExactlyOneHundredIsAccepted() throws Exception {
        when(adminUserService.searchUsers(eq(0), eq(100), isNull(), isNull(), isNull(), eq("name"), eq("asc")))
            .thenReturn(emptyPage(0, 100));

        mockMvc.perform(get("/api/admin/users").param("size", "100")).andExpect(status().isOk());
    }

    @Test
    void sizeOfOneHundredAndOneIsRefused() throws Exception {
        expectFieldError("size", "101", "size");
    }

    @Test
    void sizeOfZeroIsRefused() throws Exception {
        expectFieldError("size", "0", "size");
    }

    @Test
    void aNegativePageIsRefused() throws Exception {
        expectFieldError("page", "-1", "page");
    }

    @Test
    void aNonNumericPageIsRefusedNamingTheField() throws Exception {
        expectFieldError("page", "two", "page");
    }

    @Test
    void aSortOutsideTheWhitelistIsRefused() throws Exception {
        expectFieldError("sort", "passwordHash", "sort");
    }

    @Test
    void anOrderByInjectionAttemptIsRefusedAsAnUnknownSort() throws Exception {
        expectFieldError("sort", "name; drop table users", "sort");
    }

    @Test
    void aDirectionOtherThanAscOrDescIsRefused() throws Exception {
        expectFieldError("direction", "up", "direction");
    }

    @Test
    void anUnknownRoleIsRefused() throws Exception {
        expectFieldError("role", "SUPERUSER", "role");
    }

    @Test
    void aLowerCaseRoleIsRefusedRatherThanGuessedAt() throws Exception {
        expectFieldError("role", "donor", "role");
    }

    @Test
    void anUnknownStatusIsRefused() throws Exception {
        expectFieldError("status", "DISABLED", "status");
    }

    @Test
    void searchFiltersAndSortTravelTogetherAsOneRequest() throws Exception {
        when(adminUserService.searchUsers(1, 10, "Kamara", "DONOR", "ACTIVE", "createdAt", "desc"))
            .thenReturn(emptyPage(1, 10));

        mockMvc.perform(get("/api/admin/users")
                .param("page", "1").param("size", "10").param("q", "Kamara")
                .param("role", "DONOR").param("status", "ACTIVE")
                .param("sort", "createdAt").param("direction", "desc"))
            .andExpect(status().isOk());

        verify(adminUserService).searchUsers(1, 10, "Kamara", "DONOR", "ACTIVE", "createdAt", "desc");
    }

    /** One bad parameter: a 400 whose fieldErrors names it, and the service is
     *  never reached — a bad value is refused, never corrected and passed on. */
    private void expectFieldError(String param, String value, String field) throws Exception {
        mockMvc.perform(get("/api/admin/users").param(param, value))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors." + field).exists());
        verify(adminUserService, never()).searchUsers(anyInt(), anyInt(), any(), any(), any(), anyString(), anyString());
    }
}
