/** Label and accessible description for the bank's per-group appeal action. */
export function appealButtonText(item) {
  if (item.appealAvailability === "COOLING_DOWN" && item.appealAccepted) return "Donor Responded";
  return item.appealAvailability === "COOLING_DOWN" ? "Appeal Sent." : "Send Appeal";
}

export function appealButtonLabel(item) {
  if (item.appealAvailability === "COOLING_DOWN") {
    if (item.appealAccepted) return `A donor accepted your appeal for ${item.bloodType}`;
    return `An appeal for ${item.bloodType} was sent recently — not available yet`;
  }
  if (item.appealAvailability === "NO_SHORTAGE") {
    return `${item.bloodType} is not short enough to appeal for`;
  }
  return `Send a donation appeal for ${item.bloodType}`;
}
