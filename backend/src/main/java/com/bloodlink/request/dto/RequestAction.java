package com.bloodlink.request.dto;

/**
 * The transitions a blood bank can drive, one per edge of Business Rule 5's
 * lifecycle: PENDING -> APPROVED -> FULFILLED, or PENDING -> REJECTED.
 *
 * Not the same set as RequestStatus, and intentionally so — there is no action that
 * puts a request back into PENDING, because nothing in the agreed lifecycle
 * un-approves or un-fulfils one.
 */
public enum RequestAction {
    /** PENDING -> APPROVED. Claims the request for the acting bank. */
    APPROVE,
    /** PENDING -> REJECTED. Terminal. */
    REJECT,
    /** APPROVED -> FULFILLED. Deducts the units from the acting bank's stock. */
    FULFIL
}
