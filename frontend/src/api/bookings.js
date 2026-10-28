/* BloodLink — donation visit calls (Business Rule 29): a donor books, reads and cancels their own visit;
   bank staff read their bank's upcoming visits. Role-checked server-side. */

import { apiRequest } from './client.js';

/** POST /api/bookings: { bloodBankId, bookingDate "YYYY-MM-DD", timeOfDay MORNING|AFTERNOON } -> the booking. */
export function createBooking({ bloodBankId, bookingDate, timeOfDay }) {
  return apiRequest('/api/bookings', { method: 'POST', body: { bloodBankId, bookingDate, timeOfDay } });
}

/** GET /api/bookings/me: the donor's open booking, or null when there is none (204). */
export function getMyBooking() {
  return apiRequest('/api/bookings/me');
}

/** POST /api/bookings/me/cancel: cancels the donor's open booking while its date is today or later. */
export function cancelMyBooking() {
  return apiRequest('/api/bookings/me/cancel', { method: 'POST' });
}

/** GET /api/bookings/upcoming: this bank's booked visits from today, for its own staff. */
export function getUpcomingBookings() {
  return apiRequest('/api/bookings/upcoming');
}
