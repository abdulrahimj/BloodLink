/* BloodLink — donation visits (Business Rule 29): the booking form's date limits and checks, and the wording on the
   Donor Portal and the Blood Bank Dashboard, as plain tested functions. The server still decides everything. */

/** The furthest ahead a visit may be booked, matching DonationBookingService.MAX_DAYS_AHEAD. */
export const BOOKING_MAX_DAYS_AHEAD = 30;

const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const TIMES_OF_DAY = ["MORNING", "AFTERNOON"];

/** Today's date in UTC as "YYYY-MM-DD": the server judges dates in UTC (Sierra Leone's own time). */
export function utcToday(now = new Date()) {
  return now.toISOString().slice(0, 10);
}

/** "YYYY-MM-DD" plus a number of days, computed in UTC so no local offset can shift it. */
export function addDays(isoDate, days) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day + days)).toISOString().slice(0, 10);
}

/** The date picker's limits: today to today + 30, both included. */
export function bookingDateRange(now = new Date()) {
  const min = utcToday(now);
  return { min, max: addDays(min, BOOKING_MAX_DAYS_AHEAD) };
}

/** The form's checks, mirroring the server's: returns { field: message }, empty when the booking can be sent. */
export function bookingFormProblems({ bloodBankId, bookingDate, timeOfDay }, now = new Date()) {
  const problems = {};
  const { min, max } = bookingDateRange(now);
  if (!bloodBankId) problems.bloodBankId = "Choose a blood bank";
  if (!bookingDate) {
    problems.bookingDate = "Choose a date";
  } else if (bookingDate < min) {
    problems.bookingDate = "The date cannot be in the past";
  } else if (bookingDate > max) {
    problems.bookingDate = `Choose a date within the next ${BOOKING_MAX_DAYS_AHEAD} days`;
  }
  if (!TIMES_OF_DAY.includes(timeOfDay)) problems.timeOfDay = "Choose morning or afternoon";
  return problems;
}

/** "31 Oct 2026" from "2026-10-31", read as a plain date (never shifted by a time zone). */
export function formatBookingDate(isoDate) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return `${day} ${MONTHS[month - 1]} ${year}`;
}

/** "Morning" / "Afternoon". */
export function timeOfDayLabel(timeOfDay) {
  return timeOfDay === "MORNING" ? "Morning" : "Afternoon";
}

/** "31 Oct 2026 · Morning": when a visit is, on the booking card and the dashboard list. */
export function describeBookingWhen({ bookingDate, timeOfDay }) {
  return `${formatBookingDate(bookingDate)} · ${timeOfDayLabel(timeOfDay)}`;
}

/** The booking form's confirmation question. */
export function describeBookingQuestion(bankName, bookingDate, timeOfDay) {
  return `Book a donation at ${bankName} on ${formatBookingDate(bookingDate)}, ${timeOfDayLabel(timeOfDay).toLowerCase()}?`;
}

/** The cancel step's question on the booking card. */
export function describeCancelQuestion(booking) {
  return `Cancel your donation visit to ${booking.bloodBankName} on ${formatBookingDate(booking.bookingDate)}, `
    + `${timeOfDayLabel(booking.timeOfDay).toLowerCase()}?`;
}

/** Cancel is offered only while the visit is booked and its date is today or later, as the server allows. */
export function canCancelBooking(booking, now = new Date()) {
  return Boolean(booking) && booking.status === "BOOKED" && booking.bookingDate >= utcToday(now);
}

/** The dashboard list's blood group: the donor's recorded group, or "Not recorded". */
export function upcomingBloodGroupLabel(bloodType) {
  return bloodType ?? "Not recorded";
}

/** Notifications that change the dashboard's visit list, so it is re-read when one arrives. */
export function refreshesBookings(notificationType) {
  return notificationType === "DONATION_BOOKED" || notificationType === "DONATION_BOOKING_CANCELLED";
}
