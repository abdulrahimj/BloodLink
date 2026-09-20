package com.bloodlink.request.dto;

/**
 * What a blood bank can do with a request — Business Rule 20.
 *
 * Two actions, not the old three. APPROVE and FULFIL were a promise followed by a
 * delivery, and the promise is what claimed the request exclusively; CONTRIBUTE
 * collapses them into the single act that actually matters — units leaving this
 * bank's shelf — and leaves the request open to other banks for whatever is still
 * outstanding.
 *
 * Still an action verb rather than a target status: contributing moves stock, and a
 * client that could post {@code status: FULFILLED} would be able to close a request
 * without any units ever moving.
 */
public enum RequestAction {

    /**
     * Issue units towards the request, from the acting bank's own stock. Takes the
     * request to PARTIALLY_FULFILLED, or to FULFILLED if nothing is left
     * outstanding afterwards.
     */
    CONTRIBUTE,

    /**
     * This bank cannot help. Per bank, never shared (Business Rule 11): the request
     * leaves this bank's queue and stays live for every other bank, closing only if
     * every bank declines it while it is still untouched.
     */
    REJECT
}
