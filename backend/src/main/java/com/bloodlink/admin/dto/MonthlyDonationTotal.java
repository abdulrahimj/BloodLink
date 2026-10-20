package com.bloodlink.admin.dto;

/**
 * One month's count on the Donation Trends chart; month is a short label ("Mar"),
 * unambiguous without a year because a response always covers six consecutive months.
 */
public record MonthlyDonationTotal(String month, long totalDonations) {}
