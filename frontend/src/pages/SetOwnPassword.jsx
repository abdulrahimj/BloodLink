import React, { useState } from "react";
import { changeMyPassword } from "../api/account.js";
import { MIN_PASSWORD_LENGTH, passwordChangeErrors } from "../lib/passwordChange.js";

/* BloodLink — "Set your own password" (Business Rule 27): the only screen an account created with a temporary
   password can reach, until it chooses its own. The server refuses everything else anyway. Styles under .sp-root. */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

export default function SetOwnPassword({ user, onPasswordChanged, onSignOut }) {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});

  /** Checks locally, then changes the password; on success the app shell opens the user's normal portal. */
  async function handleSubmit(event) {
    event.preventDefault();
    if (saving) return;

    const errors = passwordChangeErrors({ currentPassword, newPassword, confirmPassword });
    setError(null);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) return;

    setSaving(true);
    try {
      await changeMyPassword({ currentPassword, newPassword });
      onPasswordChanged();
    } catch (err) {
      // A wrong temporary password is a 401 on currentPassword, which keeps the session (client.js).
      setFieldErrors(err.fieldErrors ?? {});
      setError(Object.keys(err.fieldErrors ?? {}).length > 0 ? null : err.message);
      if (err.fieldErrors?.currentPassword) setCurrentPassword("");
      setSaving(false);
    }
  }

  return (
    <div className="sp-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&display=swap');

        .sp-root {
          --navy: #1C3A5E;
          --red: #C8102E;
          --red-dark: #A50E26;
          --ink: #111111;
          --ink-muted: #6B7280;
          --line: #E1E4E8;
          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
          color: var(--ink);
          background: var(--navy);
          min-height: 100vh;
          display: flex; align-items: center; justify-content: center;
          padding: 24px 16px;
          box-sizing: border-box;
          -webkit-font-smoothing: antialiased;
        }
        .sp-root *, .sp-root *::before, .sp-root *::after { box-sizing: border-box; }

        .sp-card {
          width: 100%; max-width: 440px;
          background: #FFFFFF; border-radius: 14px;
          box-shadow: 0 10px 40px rgba(0,0,0,.18);
          padding: 32px 28px;
        }
        .sp-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 22px; }
        .sp-brand-name { font-size: 20px; font-weight: 800; letter-spacing: -.02em; }
        .sp-h { margin: 0; font-size: 24px; font-weight: 800; letter-spacing: -.02em; }
        .sp-p { margin: 8px 0 20px; font-size: 14px; line-height: 1.55; color: var(--ink-muted); }

        .sp-field { display: flex; flex-direction: column; gap: 5px; margin-bottom: 14px; }
        .sp-label { font-size: 12.5px; font-weight: 600; color: #3D3D3D; }
        .sp-hint { font-size: 11.5px; color: var(--ink-muted); }
        .sp-inwrap { position: relative; display: flex; align-items: center; }
        .sp-inwrap svg { position: absolute; left: 13px; width: 16px; height: 16px; color: #9AA0A6; }
        .sp-input {
          width: 100%; font-family: inherit; font-size: 14px; font-weight: 500; color: var(--ink);
          padding: 11px 14px 11px 40px;
          border: 1.5px solid var(--line); border-radius: 8px; background: #FBFBFC;
        }
        .sp-input:focus { outline: none; border-color: var(--navy); background: #FFF; }
        .sp-input--bad { border-color: var(--red); }
        .sp-err { font-size: 11.5px; font-weight: 500; color: var(--red); }
        .sp-alert {
          background: rgba(200,16,46,.07); border: 1.5px solid rgba(200,16,46,.28); color: #8E0B21;
          border-radius: 8px; padding: 10px 12px; font-size: 13px; font-weight: 500; line-height: 1.45;
          margin-bottom: 14px;
        }

        .sp-submit {
          width: 100%; border: none; border-radius: 8px; cursor: pointer;
          background: var(--red); color: #FFF; font-family: inherit; font-weight: 700; font-size: 15px;
          padding: 13px; margin-top: 6px; transition: background .15s ease;
        }
        .sp-submit:hover { background: var(--red-dark); }
        .sp-submit:disabled { opacity: .68; cursor: progress; }
        .sp-submit:focus-visible, .sp-signout:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }

        .sp-foot { margin-top: 18px; text-align: center; font-size: 13px; color: var(--ink-muted); }
        .sp-signout {
          background: none; border: none; padding: 0; cursor: pointer;
          font-family: inherit; font-size: 13px; font-weight: 700; color: var(--red);
        }
        .sp-signout:hover { text-decoration: underline; }
        @media (prefers-reduced-motion: reduce) { .sp-root * { transition: none !important; } }
      `}</style>

      <form className="sp-card" onSubmit={handleSubmit} noValidate>
        <div className="sp-brand">
          <svg width="28" height="32" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF"
                  strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div className="sp-brand-name">
            <span style={{ color: "#C8102E" }}>Blood</span><span style={{ color: "#1C3A5E" }}>Link</span>
          </div>
        </div>

        <h1 className="sp-h">Set your own password</h1>
        <p className="sp-p">
          Welcome{user?.fullName ? `, ${user.fullName}` : ""}. Your account was created with a temporary password.
          Choose a password only you know to continue to BloodLink.
        </p>

        {error && <div className="sp-alert" role="alert">{error}</div>}

        <PasswordField
          id="sp-current" label="Temporary password" autoComplete="current-password"
          value={currentPassword} onChange={setCurrentPassword} error={fieldErrors.currentPassword}
        />
        <PasswordField
          id="sp-new" label="New password" autoComplete="new-password"
          hint={`At least ${MIN_PASSWORD_LENGTH} characters.`}
          value={newPassword} onChange={setNewPassword} error={fieldErrors.newPassword}
        />
        <PasswordField
          id="sp-confirm" label="Confirm new password" autoComplete="new-password"
          value={confirmPassword} onChange={setConfirmPassword} error={fieldErrors.confirmPassword}
        />

        <button type="submit" className="sp-submit" disabled={saving}>
          {saving ? "Saving…" : "Set password and continue"}
        </button>

        <div className="sp-foot">
          Not you, or finishing later?{" "}
          <button type="button" className="sp-signout" onClick={onSignOut}>Sign out</button>
        </div>
      </form>
    </div>
  );
}

/** One labelled password input with its hint and field error. */
function PasswordField({ id, label, hint, autoComplete, value, onChange, error }) {
  return (
    <div className="sp-field">
      <label className="sp-label" htmlFor={id}>{label}</label>
      <div className="sp-inwrap">
        <svg viewBox="0 0 24 24" {...ICON} aria-hidden="true">
          <rect x="3.5" y="10.5" width="17" height="11" rx="2.5" /><path d="M7.5 10.5V7a4.5 4.5 0 0 1 9 0v3.5" />
        </svg>
        <input
          id={id} className={`sp-input${error ? " sp-input--bad" : ""}`} type="password"
          autoComplete={autoComplete} value={value} onChange={(e) => onChange(e.target.value)}
          aria-invalid={Boolean(error)}
        />
      </div>
      {hint && !error && <span className="sp-hint">{hint}</span>}
      {error && <span className="sp-err">{error}</span>}
    </div>
  );
}
