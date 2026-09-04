import React, { useState } from "react";
import { registerDonor } from "../api/auth.js";

/* ================================================================
   BloodLink — Register (Voluntary Donor Sign-up)

   Fields follow the User and Donor entities from the Chapter Three
   ERD: full name, phone, email, date of birth, gender, blood group,
   area and password.

   Wired to POST /api/auth/register, which creates the User and its
   linked Donor row in one transaction and signs the donor straight in.
   The layout, styling and fields are the mockup's, unchanged; only the
   submit behaviour is real. The selects gained `value` attributes so
   their options map onto the backend enums — the visible option text is
   untouched — and two on-brand states were added, an error banner and a
   busy submit button, because a form with no feedback cannot be used.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"];

/**
 * Sentinel for the "I don't know yet" option. It needs a value distinct from the
 * empty-string placeholder, or the select could not tell the two apart and would
 * snap back to the disabled "Select" entry. Sent to the API as null.
 */
const BLOOD_GROUP_UNKNOWN = "UNKNOWN";

/** Blank fields the form starts from, and resets to are never needed — kept for clarity. */
const EMPTY_FORM = {
  fullName: "",
  phone: "",
  email: "",
  dateOfBirth: "2000-01-01",
  gender: "",
  bloodType: "",
  address: "",
  password: "",
  confirmPassword: "",
};

export default function Register({ onSignedIn }) {
  const [form, setForm] = useState(EMPTY_FORM);
  const [agreed, setAgreed] = useState(false);

  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);

  /** One change handler for every input, keyed by the field name. */
  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }));
  }

  /** False for both the unchosen placeholder and the explicit "I don't know yet". */
  function isBloodGroupKnown(value) {
    return value !== "" && value !== BLOOD_GROUP_UNKNOWN;
  }

  /**
   * Checks the two things only the browser can know, before spending a round trip:
   * that the passwords match, and that the consent box is ticked. Everything else
   * (format, length, uniqueness) is validated server-side, which is the real gate.
   */
  function validateLocally() {
    const errors = {};
    if (!agreed) {
      errors.terms = "Please accept the terms and conditions to continue.";
    }
    if (form.password !== form.confirmPassword) {
      errors.confirmPassword = "Passwords do not match.";
    }
    return errors;
  }

  /**
   * Creates the donor account and hands the resulting session up to the app shell.
   *
   * The payload is mapped to the API's field names here rather than in the inputs,
   * so the form keeps the mockup's naming.
   */
  async function handleSubmit(event) {
    event.preventDefault();
    if (submitting) return;

    const localErrors = validateLocally();
    if (Object.keys(localErrors).length > 0) {
      setFieldErrors(localErrors);
      setError(localErrors.terms ?? "Please correct the highlighted fields.");
      return;
    }

    setError(null);
    setFieldErrors({});
    setSubmitting(true);

    try {
      const auth = await registerDonor({
        fullName: form.fullName.trim(),
        phone: form.phone.trim(),
        email: form.email.trim(),
        dateOfBirth: form.dateOfBirth,
        gender: form.gender,
        // "I don't know yet", or nothing chosen at all, is sent as null — the group
        // is established from the first tested donation (Business Rule 4).
        bloodType: isBloodGroupKnown(form.bloodType) ? form.bloodType : null,
        address: form.address.trim(),
        password: form.password,
      });
      onSignedIn?.(auth);
    } catch (err) {
      // Covers a duplicate phone/email (409) and field validation failures (400);
      // the backend's message is written for display, so it is shown verbatim.
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
    } finally {
      setSubmitting(false);
    }
  }

  /** Adds the invalid-input outline when the backend flagged this field. */
  const inputClass = (field) => `au-input${fieldErrors[field] ? " au-input--bad" : ""}`;
  const selectClass = (field) => `au-select${fieldErrors[field] ? " au-input--bad" : ""}`;

  return (
    <div className="au-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        .au-root {
          --navy:      #1C3A5E;
          --red:       #C8102E;
          --red-dark:  #A50E26;
          --ink:       #111111;
          --ink-muted: #7A7A7A;
          --line:      #E1E4E8;

          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
          color: var(--ink);
          background: #FFFFFF;
          height: 100vh;
          min-height: 680px;
          overflow: hidden;
          display: grid;
          grid-template-columns: .95fr 1fr;
          -webkit-font-smoothing: antialiased;
        }
        .au-root *, .au-root *::before, .au-root *::after { box-sizing: border-box; }

        .au-brandside {
          position: relative; background: var(--navy); overflow: hidden;
          display: flex; flex-direction: column;
          padding: clamp(24px, 4.4vh, 48px) clamp(24px, 3.2vw, 54px);
        }
        .au-watermark { position: absolute; inset: 0; width: 100%; height: 100%; opacity: .13; }
        .au-brand { position: relative; display: flex; align-items: center; gap: 11px; }
        .au-brand-name { font-size: clamp(17px, 2.5vh, 22px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .au-brand-tag { font-size: clamp(5.4px, .86vh, 7px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 4px; font-weight: 600; }

        .au-pitch { position: relative; margin: auto 0; }
        .au-pitch h1 { margin: 0; font-size: clamp(26px, 5vh, 46px); line-height: 1.08; font-weight: 800; letter-spacing: -.035em; color: #FFF; }
        .au-pitch h1 span { display: block; color: #FF6B7F; }
        .au-pitch p { margin: clamp(12px, 2vh, 20px) 0 0; font-size: clamp(11.5px, 1.8vh, 15px); line-height: 1.55; color: rgba(255,255,255,.78); max-width: 38ch; }

        .au-points { position: relative; display: flex; flex-direction: column; gap: clamp(7px, 1.3vh, 13px); }
        .au-point { display: flex; align-items: center; gap: 10px; font-size: clamp(10.5px, 1.55vh, 13.5px); color: rgba(255,255,255,.86); font-weight: 500; }
        .au-point svg { width: clamp(15px, 2.1vh, 18px); height: clamp(15px, 2.1vh, 18px); flex: 0 0 auto; color: #6FD79A; }

        .au-formside { display: flex; align-items: center; justify-content: center; padding: clamp(18px, 3vh, 36px) clamp(20px, 2.8vw, 52px); overflow: hidden; }
        .au-form { width: 100%; max-width: 470px; margin: 0; }
        .au-h { font-size: clamp(20px, 3.3vh, 30px); font-weight: 800; letter-spacing: -.03em; margin: 0; }
        .au-p { font-size: clamp(11px, 1.6vh, 13.5px); color: var(--ink-muted); margin: 5px 0 clamp(12px, 2.2vh, 22px); }

        .au-grid { display: grid; grid-template-columns: 1fr 1fr; gap: clamp(8px, 1.3vh, 14px) clamp(10px, 1vw, 16px); }
        .au-field { display: flex; flex-direction: column; gap: 4px; }
        .au-field--full { grid-column: 1 / -1; }
        .au-label { font-size: clamp(9.5px, 1.35vh, 12px); font-weight: 600; color: #3D3D3D; }
        .au-input, .au-select {
          width: 100%; font-family: inherit;
          font-size: clamp(11px, 1.55vh, 13.5px); font-weight: 500; color: var(--ink);
          padding: clamp(8px, 1.35vh, 12px) 13px;
          border: 1.5px solid var(--line); border-radius: 8px; background: #FBFBFC;
        }
        .au-input::placeholder { color: #A8ADB3; font-weight: 400; }
        .au-input:focus, .au-select:focus { outline: none; border-color: var(--navy); background: #FFF; }

        /* Wiring states — added for real submit feedback, using the existing tokens. */
        .au-input--bad { border-color: var(--red); }
        .au-err { font-size: clamp(9px, 1.25vh, 11px); font-weight: 500; color: var(--red); }
        .au-alert {
          background: rgba(200,16,46,.07);
          border: 1.5px solid rgba(200,16,46,.28);
          color: #8E0B21; border-radius: 8px;
          padding: clamp(7px, 1.2vh, 10px) 12px;
          font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 500; line-height: 1.45;
          margin-bottom: clamp(9px, 1.5vh, 14px);
        }
        .au-submit:disabled { opacity: .68; cursor: progress; }

        .au-terms { display: flex; align-items: flex-start; gap: 9px; margin: clamp(11px, 1.9vh, 18px) 0; font-size: clamp(10px, 1.45vh, 12.5px); color: #3D3D3D; line-height: 1.45; cursor: pointer; }
        .au-terms input { width: 15px; height: 15px; margin-top: 1px; accent-color: var(--navy); flex: 0 0 auto; cursor: pointer; }
        .au-terms a { color: var(--red); font-weight: 600; text-decoration: none; }

        .au-submit {
          width: 100%; border: none; border-radius: 8px; cursor: pointer;
          background: var(--red); color: #FFF;
          font-family: inherit; font-weight: 700;
          font-size: clamp(12.5px, 1.9vh, 15.5px);
          padding: clamp(10px, 1.7vh, 14px);
          transition: background .15s ease;
        }
        .au-submit:hover { background: var(--red-dark); }
        .au-submit:focus-visible, .au-google:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }

        .au-or { display: flex; align-items: center; gap: 12px; margin: clamp(10px, 1.8vh, 17px) 0; }
        .au-or::before, .au-or::after { content: ''; flex: 1; height: 1px; background: var(--line); }
        .au-or span { font-size: clamp(9px, 1.3vh, 11px); color: var(--ink-muted); font-weight: 500; }

        .au-google {
          width: 100%; display: flex; align-items: center; justify-content: center; gap: 10px;
          background: #FFF; border: 1.5px solid var(--line); border-radius: 8px; cursor: pointer;
          font-family: inherit; font-weight: 600; color: #2C2C2C;
          font-size: clamp(11px, 1.6vh, 13.5px);
          padding: clamp(9px, 1.5vh, 12px);
          transition: background .15s ease;
        }
        .au-google:hover { background: #F7F8F9; }
        .au-google svg { width: clamp(15px, 2.1vh, 18px); height: clamp(15px, 2.1vh, 18px); }

        .au-foot { text-align: center; margin-top: clamp(11px, 1.9vh, 19px); font-size: clamp(10.5px, 1.5vh, 13px); color: var(--ink-muted); }
        .au-foot a { color: var(--red); font-weight: 700; text-decoration: none; }

        @media (max-width: 900px) {
          .au-root { grid-template-columns: 1fr; }
          .au-brandside { display: none; }
        }
        @media (prefers-reduced-motion: reduce) { .au-root * { transition: none !important; } }
      `}</style>

      <div className="au-brandside">
        <Watermark />
        <div className="au-brand">
          <svg width="32" height="36" viewBox="0 0 34 38" aria-hidden="true">
            <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
            <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF"
                  strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <div>
            <div className="au-brand-name">
              <span style={{ color: "#FF6B7F" }}>Blood</span>
              <span style={{ color: "#FFFFFF" }}>Link</span>
            </div>
            <div className="au-brand-tag">GIVE BLOOD · SAVE LIVES</div>
          </div>
        </div>

        <div className="au-pitch">
          <h1>Become a<span>voluntary donor.</span></h1>
          <p>
            One donation can support up to three patients. Join the donor
            register for Makeni City and be notified when your blood group
            is urgently needed.
          </p>
        </div>

        <div className="au-points">
          <div className="au-point"><TickIcon /> Track your donation history and eligibility</div>
          <div className="au-point"><TickIcon /> Get alerts when your blood group runs low</div>
          <div className="au-point"><TickIcon /> Earn a recognised donor certificate</div>
        </div>
      </div>

      <div className="au-formside">
        <form className="au-form" onSubmit={handleSubmit} noValidate>
          <h2 className="au-h">Create your account</h2>
          <p className="au-p">Register as a voluntary blood donor in Makeni City.</p>

          {error && <div className="au-alert" role="alert">{error}</div>}

          <div className="au-grid">
            <div className="au-field au-field--full">
              <label className="au-label" htmlFor="rg-name">Full Name</label>
              <input
                id="rg-name" className={inputClass("fullName")} type="text"
                placeholder="Enter your full name" autoComplete="name"
                value={form.fullName} onChange={(e) => update("fullName", e.target.value)}
              />
              {fieldErrors.fullName && <span className="au-err">{fieldErrors.fullName}</span>}
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-phone">Phone Number</label>
              <input
                id="rg-phone" className={inputClass("phone")} type="tel"
                placeholder="+232 76 000 000" autoComplete="tel"
                value={form.phone} onChange={(e) => update("phone", e.target.value)}
              />
              {fieldErrors.phone && <span className="au-err">{fieldErrors.phone}</span>}
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-email">Email Address</label>
              <input
                id="rg-email" className={inputClass("email")} type="email"
                placeholder="you@example.com" autoComplete="email"
                value={form.email} onChange={(e) => update("email", e.target.value)}
              />
              {fieldErrors.email && <span className="au-err">{fieldErrors.email}</span>}
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-dob">Date of Birth</label>
              <input
                id="rg-dob" className={inputClass("dateOfBirth")} type="date"
                value={form.dateOfBirth} onChange={(e) => update("dateOfBirth", e.target.value)}
              />
              {fieldErrors.dateOfBirth && <span className="au-err">{fieldErrors.dateOfBirth}</span>}
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-gender">Gender</label>
              {/* values map onto the Gender enum; the visible option text is the mockup's */}
              <select
                id="rg-gender" className={selectClass("gender")}
                value={form.gender} onChange={(e) => update("gender", e.target.value)}
              >
                <option value="" disabled>Select</option>
                <option value="FEMALE">Female</option>
                <option value="MALE">Male</option>
                <option value="PREFER_NOT_TO_SAY">Prefer not to say</option>
              </select>
              {fieldErrors.gender && <span className="au-err">{fieldErrors.gender}</span>}
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-group">Blood Group</label>
              {/* The group labels are already the API's wire format; "I don't know
                  yet" carries an empty value and is sent as null. */}
              <select
                id="rg-group" className={selectClass("bloodType")}
                value={form.bloodType} onChange={(e) => update("bloodType", e.target.value)}
              >
                <option value="" disabled>Select</option>
                {BLOOD_GROUPS.map((g) => <option key={g} value={g}>{g}</option>)}
                <option value={BLOOD_GROUP_UNKNOWN}>I don&rsquo;t know yet</option>
              </select>
              {fieldErrors.bloodType && <span className="au-err">{fieldErrors.bloodType}</span>}
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-area">Area / Community</label>
              <input
                id="rg-area" className={inputClass("address")} type="text"
                placeholder="e.g. Wusum, Makeni" autoComplete="address-level2"
                value={form.address} onChange={(e) => update("address", e.target.value)}
              />
              {fieldErrors.address && <span className="au-err">{fieldErrors.address}</span>}
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-pass">Password</label>
              <input
                id="rg-pass" className={inputClass("password")} type="password"
                placeholder="Create a password" autoComplete="new-password"
                value={form.password} onChange={(e) => update("password", e.target.value)}
              />
              {fieldErrors.password && <span className="au-err">{fieldErrors.password}</span>}
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-pass2">Confirm Password</label>
              <input
                id="rg-pass2" className={inputClass("confirmPassword")} type="password"
                placeholder="Re-enter password" autoComplete="new-password"
                value={form.confirmPassword} onChange={(e) => update("confirmPassword", e.target.value)}
              />
              {fieldErrors.confirmPassword && <span className="au-err">{fieldErrors.confirmPassword}</span>}
            </div>
          </div>

          <label className="au-terms">
            <input
              type="checkbox"
              checked={agreed}
              onChange={(e) => setAgreed(e.target.checked)}
            />
            <span>
              I agree to the <a href="#terms">terms and conditions</a> and consent to
              BloodLink storing my donor details.
            </span>
          </label>

          <button type="submit" className="au-submit" disabled={submitting}>
            {submitting ? "Creating account…" : "Create account"}
          </button>

          <div className="au-or"><span>or sign up with</span></div>

          {/* Social sign-up is not part of this phase — the button is intentionally inert. */}
          <button type="button" className="au-google"><GoogleIcon /> Google</button>

          <div className="au-foot">
            Already have an account? <a href="#login">Sign in</a>
          </div>
        </form>
      </div>
    </div>
  );
}

function Watermark() {
  return (
    <svg className="au-watermark" viewBox="0 0 600 700" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
      <path d="M300 250c0-60 88-82 121-27 33-55 121-33 121 27 0 71-121 137-121 137s-121-66-121-137z"
            fill="none" stroke="#FFF" strokeWidth="7" />
      <path d="M-40 400h150l48-114 66 228 57-149 40 35h190"
            fill="none" stroke="#FFF" strokeWidth="7" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="120" cy="150" r="70" fill="none" stroke="#FFF" strokeWidth="6" />
      <circle cx="500" cy="580" r="110" fill="none" stroke="#FFF" strokeWidth="6" />
    </svg>
  );
}

function TickIcon() { return (<svg viewBox="0 0 24 24" {...ICON} strokeWidth="2.4"><circle cx="12" cy="12" r="9.5" strokeWidth="1.6"/><path d="M7.8 12.4 11 15.6l5.4-6.4"/></svg>); }
function GoogleIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path fill="#4285F4" d="M23 12.3c0-.8-.1-1.6-.2-2.3H12v4.5h6.2a5.3 5.3 0 0 1-2.3 3.5v2.9h3.7c2.2-2 3.4-5 3.4-8.6z"/>
      <path fill="#34A853" d="M12 23.5c3.1 0 5.7-1 7.6-2.8l-3.7-2.9a7 7 0 0 1-10.4-3.6H1.7v3A11.5 11.5 0 0 0 12 23.5z"/>
      <path fill="#FBBC05" d="M5.5 14.2a6.9 6.9 0 0 1 0-4.4v-3H1.7a11.5 11.5 0 0 0 0 10.4l3.8-3z"/>
      <path fill="#EA4335" d="M12 5.4c1.7 0 3.2.6 4.4 1.7l3.3-3.3A11.5 11.5 0 0 0 1.7 6.8l3.8 3A6.9 6.9 0 0 1 12 5.4z"/>
    </svg>
  );
}
