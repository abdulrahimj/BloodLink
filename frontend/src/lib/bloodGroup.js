/* BloodLink — a donor's blood group follows the blood bank's test (Business Rule 28): the Record Donation messages,
   the Edit blood group form's checks and question, and the Donor Portal's notice, as plain tested functions. */

/** ApiError.code on the 409 for a tested group that differs from the profile's, sent unconfirmed. */
export const BLOOD_GROUP_CHANGE_UNCONFIRMED = "BLOOD_GROUP_CHANGE_UNCONFIRMED";

/** The longest note the server accepts on a manual correction. */
export const BLOOD_GROUP_NOTE_MAX = 500;

/** True when recording this tested group would replace a different group already on the profile. */
export function needsBloodGroupChangeConfirmation(profileBloodType, testedBloodType) {
  return Boolean(profileBloodType && testedBloodType && profileBloodType !== testedBloodType);
}

/**
 * The line under Record Donation's group picker: a note when the profile has no group, a warning when the test
 * differs, nothing when they agree or no group is chosen yet.
 */
export function describeDonationGroupCheck(profileBloodType, testedBloodType) {
  if (!testedBloodType) return null;
  if (!profileBloodType) {
    return { tone: "info", text: "This donor's blood group will be saved to their profile." };
  }
  if (profileBloodType === testedBloodType) return null;
  return {
    tone: "warn",
    text: `This donor's profile says ${profileBloodType}, but the tested group is ${testedBloodType}. `
      + `You will be asked to confirm changing their profile to ${testedBloodType}.`,
  };
}

/** The question asked before a donation that changes the profile group is sent with the confirmation. */
export function describeDonationGroupChangeQuestion(donorName, profileBloodType, testedBloodType) {
  return `Change ${donorName}'s blood group from ${profileBloodType} to ${testedBloodType} `
    + "and record this donation?";
}

/** True for the server's refusal of an unconfirmed change, which the form answers by asking to confirm. */
export function isUnconfirmedBloodGroupChange(error) {
  return error?.status === 409 && error?.code === BLOOD_GROUP_CHANGE_UNCONFIRMED;
}

/**
 * The Edit blood group form's checks, mirroring the server's (which still decides): a group that differs from
 * the current one, and a note of 1–500 characters. Returns { field: message }, empty when fine.
 */
export function bloodGroupEditProblems({ currentBloodType, bloodType, note }) {
  const problems = {};
  if (!bloodType) {
    problems.bloodType = "Choose the tested blood group";
  } else if (bloodType === currentBloodType) {
    problems.bloodType = `Their blood group is already ${bloodType}`;
  }
  const trimmed = (note ?? "").trim();
  if (!trimmed) {
    problems.note = "Add a note saying how or where the test was done";
  } else if (trimmed.length > BLOOD_GROUP_NOTE_MAX) {
    problems.note = `The note must be ${BLOOD_GROUP_NOTE_MAX} characters or fewer`;
  }
  return problems;
}

/** The confirmation step's question: a change when a group is on file, otherwise setting one. */
export function describeBloodGroupEditQuestion(donorName, currentBloodType, newBloodType) {
  return currentBloodType
    ? `Change ${donorName}'s blood group from ${currentBloodType} to ${newBloodType}?`
    : `Set ${donorName}'s blood group to ${newBloodType}?`;
}

/** The Donor Portal's blood group card: the group, or "Not recorded" with a notice saying how it gets added. */
export function describeDonorPortalBloodGroup(bloodType) {
  if (bloodType) return { figure: bloodType, notice: null };
  return {
    figure: "Not recorded",
    notice: "Your blood group isn't recorded yet. Visit a blood bank to be tested, "
      + "and it will be added after your first donation.",
  };
}

/** The Inventory page's notice after Record Donation, adding the profile change when there was one. */
export function describeDonationRecorded(receipt) {
  const units = `${receipt.unitsDonated} unit${receipt.unitsDonated === 1 ? "" : "s"}`;
  const base = `Donation recorded — ${units} of ${receipt.bloodType} from ${receipt.donorName}. `
    + `${receipt.bloodType} stock is now ${receipt.stockAfter} units.`;
  return receipt.profileBloodTypeUpdated
    ? `${base} Their profile blood group is now ${receipt.bloodType}.`
    : base;
}
