package com.bloodlink.request.dto;

/**
 * What a bank can do with a request (Business Rule 20): CONTRIBUTE replaced APPROVE and FULFIL, or REJECT.
 * Action verbs, not target statuses, so no client can close a request without units moving.
 */
public enum RequestAction {

    /**
     * Issue units from the acting bank's own stock; the request becomes PARTIALLY_FULFILLED or FULFILLED.
     */
    CONTRIBUTE,

    /**
     * This bank cannot help (Business Rule 11, per bank): the request leaves this bank's queue only,
     * closing if every bank declines while it is untouched.
     */
    REJECT
}
