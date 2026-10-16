package com.bloodlink.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.BatchHistoryResponse;
import com.bloodlink.inventory.dto.BatchResponse;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.inventory.dto.InventorySummaryResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Per-bank, per-blood-group stock. The per-group totals are readable by any
 * signed-in role (SecurityConfig: hospitals need a live view across banks); batch
 * detail is for bank staff and administrators only, and writing is role-gated to
 * BLOOD_BANK_STAFF/ADMIN there and further scoped to the caller's own bank inside
 * InventoryService.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** All eight blood groups' usable stock at one bank, with status computed per group. */
    @GetMapping("/{bloodBankId}")
    public List<InventoryItemResponse> listInventory(@PathVariable UUID bloodBankId) {
        return inventoryService.listInventory(bloodBankId);
    }

    /** Totals, expiring-soon and expired units, critical-group count and composition. */
    @GetMapping("/{bloodBankId}/summary")
    public InventorySummaryResponse summary(@PathVariable UUID bloodBankId) {
        return inventoryService.getSummary(bloodBankId);
    }

    /**
     * One bank's live batches, optionally for one blood group ({@code ?bloodType=O-},
     * URL-encoded so "+" survives). Bank staff of that bank and ADMIN only.
     */
    @GetMapping("/{bloodBankId}/batches")
    public List<BatchResponse> listBatches(
        @PathVariable UUID bloodBankId,
        @RequestParam(required = false) String bloodType,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return inventoryService.listBatches(bloodBankId, bloodType, principal);
    }

    /** One batch's history: units issued to requests and units written off. */
    @GetMapping("/{bloodBankId}/batches/{batchId}")
    public BatchHistoryResponse batchHistory(
        @PathVariable UUID bloodBankId,
        @PathVariable UUID batchId,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return inventoryService.batchHistory(bloodBankId, batchId, principal);
    }

    /**
     * "Add New Batch". {bloodType} is the clinical label ("A+", "O-") — the client
     * must URL-encode it (encodeURIComponent) so "+" survives as a literal
     * character rather than being read as an encoded space.
     */
    @PatchMapping("/{bloodBankId}/{bloodType}")
    public InventoryItemResponse addBatch(
        @PathVariable UUID bloodBankId,
        @PathVariable String bloodType,
        @Valid @RequestBody AddBatchRequest request,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return inventoryService.addBatch(bloodBankId, bloodType, request, principal);
    }
}
