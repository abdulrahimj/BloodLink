package com.bloodlink.inventory;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bloodlink.inventory.dto.AddBatchRequest;
import com.bloodlink.inventory.dto.BatchHistoryResponse;
import com.bloodlink.inventory.dto.BatchResponse;
import com.bloodlink.inventory.dto.BatchWriteOffResponse;
import com.bloodlink.inventory.dto.WriteOffBatchRequest;
import com.bloodlink.inventory.dto.InventoryItemResponse;
import com.bloodlink.inventory.dto.InventorySummaryResponse;
import com.bloodlink.security.AuthenticatedUser;

import jakarta.validation.Valid;

/**
 * Per-bank, per-blood-group stock. The per-group totals are readable by any
 * signed-in role (SecurityConfig: hospitals need a live view across banks); batch
 * detail is for bank staff and administrators only, and writing (adding a batch,
 * writing one off) is role-gated to BLOOD_BANK_STAFF/ADMIN there and further scoped
 * to the caller's own bank inside InventoryService.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;
    private final BatchWriteOffService batchWriteOffService;

    InventoryController(InventoryService inventoryService, BatchWriteOffService batchWriteOffService) {
        this.inventoryService = inventoryService;
        this.batchWriteOffService = batchWriteOffService;
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
     * Writes units of one batch off, with a reason (Business Rule 24) — expired
     * batches included, which is the main use. Bank staff of that bank and ADMIN
     * only; 201 with the write-off and the batch as it now stands.
     */
    @PostMapping("/{bloodBankId}/batches/{batchId}/write-offs")
    @ResponseStatus(HttpStatus.CREATED)
    public BatchWriteOffResponse writeOff(
        @PathVariable UUID bloodBankId,
        @PathVariable UUID batchId,
        @Valid @RequestBody WriteOffBatchRequest request,
        @AuthenticationPrincipal AuthenticatedUser principal
    ) {
        return batchWriteOffService.writeOff(bloodBankId, batchId, request, principal);
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
