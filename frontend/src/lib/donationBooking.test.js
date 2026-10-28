import { test } from "node:test";
import assert from "node:assert/strict";

import {
  BOOKING_MAX_DAYS_AHEAD, addDays, bookingDateRange, bookingFormProblems, canCancelBooking, describeBookingQuestion,
  describeBookingWhen, describeCancelQuestion, formatBookingDate, refreshesBookings, timeOfDayLabel,
  upcomingBloodGroupLabel, utcToday,
} from "./donationBooking.js";

// 28 Oct 2026 at 23:30 UTC: late enough that a clock east of UTC would already read the 29th.
const NOW = new Date("2026-10-28T23:30:00Z");
const VALID = { bloodBankId: "bank-1", bookingDate: "2026-11-02", timeOfDay: "MORNING" };

// ---------- date limits ----------

test("today is the UTC date, and the picker allows today to today + 30", () => {
  assert.equal(utcToday(NOW), "2026-10-28");
  assert.deepEqual(bookingDateRange(NOW), { min: "2026-10-28", max: "2026-11-27" });
  assert.equal(BOOKING_MAX_DAYS_AHEAD, 30);
});

test("adding days crosses month and year ends without drifting", () => {
  assert.equal(addDays("2026-12-20", 30), "2027-01-19");
  assert.equal(addDays("2027-02-27", 2), "2027-03-01");
});

// ---------- the form's checks ----------

test("a complete booking inside the window has no problems, at both limits too", () => {
  assert.deepEqual(bookingFormProblems(VALID, NOW), {});
  assert.deepEqual(bookingFormProblems({ ...VALID, bookingDate: "2026-10-28" }, NOW), {});
  assert.deepEqual(bookingFormProblems({ ...VALID, bookingDate: "2026-11-27", timeOfDay: "AFTERNOON" }, NOW), {});
});

test("an empty form names every field", () => {
  assert.deepEqual(bookingFormProblems({ bloodBankId: "", bookingDate: "", timeOfDay: "" }, NOW), {
    bloodBankId: "Choose a blood bank",
    bookingDate: "Choose a date",
    timeOfDay: "Choose morning or afternoon",
  });
});

test("yesterday and day 31 are refused with the server's wording", () => {
  assert.deepEqual(bookingFormProblems({ ...VALID, bookingDate: "2026-10-27" }, NOW), {
    bookingDate: "The date cannot be in the past",
  });
  assert.deepEqual(bookingFormProblems({ ...VALID, bookingDate: "2026-11-28" }, NOW), {
    bookingDate: "Choose a date within the next 30 days",
  });
});

test("only MORNING and AFTERNOON are times of day", () => {
  assert.deepEqual(bookingFormProblems({ ...VALID, timeOfDay: "EVENING" }, NOW), {
    timeOfDay: "Choose morning or afternoon",
  });
});

// ---------- wording ----------

test("dates read as plain days and times as words", () => {
  assert.equal(formatBookingDate("2026-10-31"), "31 Oct 2026");
  assert.equal(timeOfDayLabel("MORNING"), "Morning");
  assert.equal(timeOfDayLabel("AFTERNOON"), "Afternoon");
  assert.equal(describeBookingWhen({ bookingDate: "2026-11-02", timeOfDay: "AFTERNOON" }), "2 Nov 2026 · Afternoon");
});

test("the booking and cancel questions name the bank, the date and the half-day", () => {
  assert.equal(describeBookingQuestion("Holy Spirit Blood Bank", "2026-11-02", "MORNING"),
    "Book a donation at Holy Spirit Blood Bank on 2 Nov 2026, morning?");
  assert.equal(describeCancelQuestion({ bloodBankName: "Holy Spirit Blood Bank", bookingDate: "2026-11-02",
    timeOfDay: "AFTERNOON" }), "Cancel your donation visit to Holy Spirit Blood Bank on 2 Nov 2026, afternoon?");
});

test("cancel is offered while booked and dated today or later, never after", () => {
  assert.equal(canCancelBooking({ status: "BOOKED", bookingDate: "2026-10-28" }, NOW), true);
  assert.equal(canCancelBooking({ status: "BOOKED", bookingDate: "2026-10-27" }, NOW), false);
  assert.equal(canCancelBooking({ status: "CANCELLED", bookingDate: "2026-11-02" }, NOW), false);
  assert.equal(canCancelBooking(null, NOW), false);
});

test("the dashboard shows a missing blood group as Not recorded and reloads on booking news only", () => {
  assert.equal(upcomingBloodGroupLabel(null), "Not recorded");
  assert.equal(upcomingBloodGroupLabel("AB-"), "AB-");
  assert.equal(refreshesBookings("DONATION_BOOKED"), true);
  assert.equal(refreshesBookings("DONATION_BOOKING_CANCELLED"), true);
  assert.equal(refreshesBookings("DONATION_BOOKING_REMINDER"), false);
  assert.equal(refreshesBookings("LOW_STOCK_ALERT"), false);
});
