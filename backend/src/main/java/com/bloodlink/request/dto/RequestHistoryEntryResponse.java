package com.bloodlink.request.dto;

/**
 * One finished request on the Request history page (Business Rule 36). The two bank fields say what this bank did;
 * both are null in the hospital's view, where they mean nothing.
 */
public record RequestHistoryEntryResponse(
    BloodRequestResponse request,
    /** Units the viewing bank contributed (0 if it gave none); null for a hospital. */
    Integer unitsFromYourBank,
    /** Whether the viewing bank declined it; null for a hospital. */
    Boolean declinedByYourBank
) {
}
