package com.bloodlink.admin.dto;

/**
 * One month's donation count on the "Donation Trends" chart.
 *
 * {@code month} is a short display label ("Mar", "Apr") rather than a full date,
 * matching how the mockup's own TRENDS fixture and chart already render it. No
 * year travels with it: AdminReportService always returns six *consecutive*
 * calendar months, so within one response no two rows can ever share a month
 * name, and the label stays unambiguous without one.
 */
public record MonthlyDonationTotal(String month, long totalDonations) {}
