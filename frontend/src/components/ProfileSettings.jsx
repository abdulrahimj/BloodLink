import React, { useEffect, useState } from "react";
import { changeMyPassword, getMyAccount, updateMyDetails } from "../api/account.js";
import { initials } from "../lib/dates.js";

/* BloodLink — Profile Settings (Business Rule 16), one screen for all four roles: editable name and email,
   read-only phone/role/facility, and password change. Styles scoped under .ps-root. */

const ROLE_LABEL = {
  DONOR: "Donor",
  HOSPITAL_STAFF: "Hospital Staff",
  BLOOD_BANK_STAFF: "Blood Bank Staff",
  ADMIN: "Administrator",
};

/** Registration's rule, repeated here only to warn before a round trip — the
 *  server enforces it either way (ChangePasswordPayload). */
const MIN_PASSWORD_LENGTH = 8;

/**
 * The banner text for a failed request, or null when it would only repeat a message under one of this form's inputs;
 * errors for fields not on this form still show here.
 */
function bannerFor(err, visibleFields) {
  const fieldErrors = err.fieldErrors ?? {};
  const alreadyShown = visibleFields.some((field) => fieldErrors[field] === err.message);
  return alreadyShown ? null : err.message;
}

/**
 * onUserUpdated is called with { fullName, email } after a save, so the header and session update without sign-out.
 */
export default function ProfileSettings({ onUserUpdated }) {
  const [account, setAccount] = useState(null);
  const [loadError, setLoadError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    getMyAccount()
      .then((data) => { if (!cancelled) setAccount(data); })
      .catch((err) => { if (!cancelled) setLoadError(err.message); });
    return () => { cancelled = true; };
  }, []);

  return (
    <div className="ps-root">
      <style>{STYLES}</style>

      {loadError && (
        <div className="ps-card ps-load-error" role="alert">{loadError}</div>
      )}

      {!loadError && !account && (
        <div className="ps-loading">Loading your account…</div>
      )}

      {account && (
        <div className="ps-grid">
          <div className="ps-col">
            <DetailsCard
              account={account}
              onSaved={(saved) => {
                setAccount(saved);
                onUserUpdated?.({ fullName: saved.fullName, email: saved.email });
              }}
            />
            <ManagedFactsCard account={account} />
          </div>
          <div className="ps-col">
            <PasswordCard />
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Full name and email, editable by every role without a current password; Save enables only after a change.
 */
function DetailsCard({ account, onSaved }) {
  // A donor may have no email (null); the input always holds a string, so "none" is "" here.
  const storedEmail = account.email ?? "";
  const emailOptional = account.role === "DONOR";

  const [fullName, setFullName] = useState(account.fullName);
  const [email, setEmail] = useState(storedEmail);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [saved, setSaved] = useState(false);

  const changed = fullName.trim() !== account.fullName || email.trim().toLowerCase() !== storedEmail;

  async function handleSubmit(event) {
    event.preventDefault();
    if (saving || !changed) return;

    setSaving(true);
    setError(null);
    setFieldErrors({});
    setSaved(false);
    try {
      // Blank is sent as-is: for a donor the server stores it as "no email"; for
      // any other role it is refused with a message under the box.
      const updated = await updateMyDetails({ fullName: fullName.trim(), email: email.trim() });
      // Re-seed the inputs from what the server stored (e.g. the lower-cased
      // email), so "changed" goes back to false and Save disables again.
      setFullName(updated.fullName);
      setEmail(updated.email ?? "");
      setSaved(true);
      onSaved(updated);
    } catch (err) {
      setError(bannerFor(err, ["fullName", "email"]));
      setFieldErrors(err.fieldErrors ?? {});
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="ps-card" onSubmit={handleSubmit} noValidate>
      <div className="ps-card-head">
        {/* The app's shared initials() — the same one every portal's header avatar
            uses, so the two circles on this screen always show the same letters. */}
        <div className="ps-avatar" aria-hidden="true">{initials(account.fullName)}</div>
        <div>
          <div className="ps-card-title">Your details</div>
          <div className="ps-card-sub">How you appear across BloodLink.</div>
        </div>
      </div>

      {error && <div className="ps-alert" role="alert">{error}</div>}
      {saved && !error && <div className="ps-ok" role="status">Your details were saved.</div>}

      <Field label="Full name" htmlFor="ps-name" error={fieldErrors.fullName}>
        <input
          id="ps-name" className={inputClass(fieldErrors.fullName)} type="text" autoComplete="name"
          value={fullName} onChange={(e) => { setFullName(e.target.value); setSaved(false); }}
        />
      </Field>

      <Field
        label={emailOptional ? "Email address (optional)" : "Email address"}
        htmlFor="ps-email" error={fieldErrors.email}
      >
        <input
          id="ps-email" className={inputClass(fieldErrors.email)} type="email" autoComplete="email"
          value={email} onChange={(e) => { setEmail(e.target.value); setSaved(false); }}
        />
      </Field>

      <div className="ps-actions">
        <button type="submit" className="ps-btn" disabled={saving || !changed}>
          {saving ? "Saving…" : "Save changes"}
        </button>
      </div>
    </form>
  );
}

/**
 * Phone, role and facility, shown read-only with who can change them; facility only for staff roles.
 */
function ManagedFactsCard({ account }) {
  const facility = account.hospitalName ?? account.bloodBankName;
  const facilityLabel = account.hospitalName ? "Hospital" : "Blood bank";

  return (
    <div className="ps-card">
      {/* Worded as "set up for you", since no screen anywhere edits a phone, role or facility after creation. */}
      <div className="ps-card-title">Managed by your administrator</div>
      <div className="ps-card-sub">
        Set when your account was created. Contact your system administrator if any of these need to change.
      </div>

      <dl className="ps-facts">
        <div className="ps-fact">
          <dt><LockIcon /> Phone number</dt>
          <dd>
            <span className="ps-mono">{account.phone}</span>
            <span className="ps-fact-note">This is how you sign in, so it cannot be changed here.</span>
          </dd>
        </div>
        <div className="ps-fact">
          <dt><LockIcon /> Role</dt>
          <dd>{ROLE_LABEL[account.role] ?? account.role}</dd>
        </div>
        {facility && (
          <div className="ps-fact">
            <dt><LockIcon /> {facilityLabel}</dt>
            <dd>{facility}</dd>
          </div>
        )}
      </dl>
    </div>
  );
}

/**
 * Change password: current password first (so an open session can't lock the owner out), then the new one twice.
 * A wrong current password is a field 401 that keeps the session; the confirm field is checked only here.
 */
function PasswordCard() {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [changed, setChanged] = useState(false);

  function localErrors() {
    const errors = {};
    if (!currentPassword) errors.currentPassword = "Enter your current password.";
    if (newPassword.length < MIN_PASSWORD_LENGTH) {
      errors.newPassword = `New password must be at least ${MIN_PASSWORD_LENGTH} characters.`;
    }
    if (confirmPassword !== newPassword) errors.confirmPassword = "The new passwords do not match.";
    return errors;
  }

  async function handleSubmit(event) {
    event.preventDefault();
    if (saving) return;

    setChanged(false);
    const errors = localErrors();
    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      setError(null);
      return;
    }

    setSaving(true);
    setError(null);
    setFieldErrors({});
    try {
      await changeMyPassword({ currentPassword, newPassword });
      // Cleared on success: three passwords sitting in form state, or in the
      // browser's view of the page, serve nobody once the change is made.
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setChanged(true);
    } catch (err) {
      setError(bannerFor(err, ["currentPassword", "newPassword", "confirmPassword"]));
      setFieldErrors(err.fieldErrors ?? {});
      // The current password was wrong, or refused: clear only that box, so the
      // user retypes the one thing that failed and keeps the new one they chose.
      if (err.fieldErrors?.currentPassword) setCurrentPassword("");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form className="ps-card" onSubmit={handleSubmit} noValidate>
      <div className="ps-card-title">Change password</div>
      <div className="ps-card-sub">
        For your security, enter your current password before choosing a new one.
      </div>

      {error && <div className="ps-alert" role="alert">{error}</div>}
      {changed && (
        <div className="ps-ok" role="status">
          Your password was changed. Use the new one the next time you sign in.
        </div>
      )}

      <Field label="Current password" htmlFor="ps-current" error={fieldErrors.currentPassword}>
        <input
          id="ps-current" className={inputClass(fieldErrors.currentPassword)} type="password"
          autoComplete="current-password"
          value={currentPassword} onChange={(e) => setCurrentPassword(e.target.value)}
        />
      </Field>

      <Field
        label="New password" htmlFor="ps-new" error={fieldErrors.newPassword}
        hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
      >
        <input
          id="ps-new" className={inputClass(fieldErrors.newPassword)} type="password"
          autoComplete="new-password"
          value={newPassword} onChange={(e) => setNewPassword(e.target.value)}
        />
      </Field>

      <Field label="Confirm new password" htmlFor="ps-confirm" error={fieldErrors.confirmPassword}>
        <input
          id="ps-confirm" className={inputClass(fieldErrors.confirmPassword)} type="password"
          autoComplete="new-password"
          value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)}
        />
      </Field>

      <div className="ps-actions">
        <button type="submit" className="ps-btn" disabled={saving}>
          {saving ? "Updating…" : "Update password"}
        </button>
      </div>
    </form>
  );
}

/** A labelled input with its error (or hint) underneath. */
function Field({ label, htmlFor, error, hint, children }) {
  return (
    <div className="ps-field">
      <label className="ps-label" htmlFor={htmlFor}>{label}</label>
      {children}
      {error ? <span className="ps-err">{error}</span> : hint && <span className="ps-hint">{hint}</span>}
    </div>
  );
}

function inputClass(error) {
  return `ps-input${error ? " ps-input--bad" : ""}`;
}

function LockIcon() {
  return (
    <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor"
         strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </svg>
  );
}

const STYLES = `
  .ps-root {
    --navy: #1C3A5E; --navy-soft: #2A4E77;
    --red: #C8102E; --green: #1E7F45; --amber: #D98A1F;
    --card-bg: #FFFFFF; --ink: #111111; --ink-muted: #7A7A7A;
    --line: #E6E8EB; --radius: 10px; --shadow: 0 2px 10px rgba(0,0,0,0.07);

    flex: 1 1 auto; min-height: 0; overflow-y: auto;
    font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
    color: var(--ink);
  }
  .ps-root *, .ps-root *::before, .ps-root *::after { box-sizing: border-box; }

  /* Two columns where there is room, one where there is not. */
  .ps-grid { display: grid; grid-template-columns: 1.1fr 1fr; gap: clamp(10px, 1.2vw, 18px); align-items: start; }
  .ps-col { display: flex; flex-direction: column; gap: clamp(10px, 1.2vw, 18px); min-width: 0; }
  @media (max-width: 900px) { .ps-grid { grid-template-columns: 1fr; } }

  .ps-card {
    background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
    padding: clamp(14px, 2vh, 22px) clamp(14px, 1.4vw, 24px);
    display: flex; flex-direction: column; gap: clamp(9px, 1.3vh, 14px);
  }
  .ps-card-head { display: flex; align-items: center; gap: 12px; }
  .ps-avatar {
    flex: 0 0 auto; width: 42px; height: 42px; border-radius: 50%;
    background: var(--navy); color: #FFF; display: grid; place-items: center;
    font-weight: 700; font-size: 14px;
  }
  .ps-card-title { font-size: clamp(13px, 1.9vh, 16px); font-weight: 700; letter-spacing: -.01em; }
  .ps-card-sub { font-size: clamp(10px, 1.45vh, 12.5px); color: var(--ink-muted); margin-top: 2px; line-height: 1.45; }

  .ps-field { display: flex; flex-direction: column; gap: 4px; }
  .ps-label { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 700; color: #3A3A3A; }
  .ps-input {
    font-family: inherit; font-size: clamp(11px, 1.55vh, 13.5px);
    padding: clamp(7px, 1.1vh, 10px) 11px;
    border: 1.5px solid var(--line); border-radius: 7px; background: #FFF; color: var(--ink); width: 100%;
  }
  .ps-input:focus { outline: none; border-color: var(--navy); }
  .ps-input--bad { border-color: var(--red); }
  .ps-err { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--red); font-weight: 600; }
  .ps-hint { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); }

  .ps-alert {
    background: #FBE9EC; color: var(--red); font-weight: 600; line-height: 1.45;
    font-size: clamp(10px, 1.4vh, 12.5px); padding: clamp(7px, 1.1vh, 10px) 11px; border-radius: 7px;
  }
  .ps-ok {
    background: #E7F2EB; color: var(--green); font-weight: 600; line-height: 1.45;
    font-size: clamp(10px, 1.4vh, 12.5px); padding: clamp(7px, 1.1vh, 10px) 11px; border-radius: 7px;
  }

  .ps-actions { display: flex; justify-content: flex-end; }
  .ps-btn {
    background: var(--navy); color: #FFF; border: none; border-radius: 7px; cursor: pointer;
    font-family: inherit; font-weight: 700; font-size: clamp(11px, 1.55vh, 13.5px);
    padding: clamp(8px, 1.15vh, 11px) clamp(14px, 1.4vw, 22px);
  }
  .ps-btn:hover { background: var(--navy-soft); }
  .ps-btn:disabled { opacity: .5; cursor: not-allowed; background: var(--navy); }
  .ps-btn:focus-visible, .ps-input:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }

  /* Read-only facts: a quiet grey panel with a lock beside each label, so they
     read as information rather than as inputs that failed to load. */
  .ps-facts { margin: 0; display: flex; flex-direction: column; gap: 2px; }
  .ps-fact {
    display: grid; grid-template-columns: minmax(110px, 38%) 1fr; gap: 10px; align-items: baseline;
    padding: clamp(7px, 1.05vh, 10px) 0; border-top: 1px solid var(--line);
  }
  .ps-fact:first-child { border-top: none; }
  .ps-fact dt {
    display: flex; align-items: center; gap: 6px;
    font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 700; color: var(--ink-muted);
  }
  .ps-fact dd { margin: 0; font-size: clamp(11px, 1.55vh, 13.5px); font-weight: 600; display: flex; flex-direction: column; gap: 3px; min-width: 0; }
  .ps-fact-note { font-size: clamp(8.5px, 1.2vh, 11px); color: var(--ink-muted); font-weight: 500; line-height: 1.4; }
  .ps-mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: .02em; }

  .ps-loading { color: var(--ink-muted); font-size: clamp(11px, 1.55vh, 13.5px); padding: 20px 2px; }
  .ps-load-error { color: var(--red); font-weight: 600; }

  @media (prefers-reduced-motion: reduce) { .ps-root * { transition: none !important; } }
`;
