import React, { useState } from "react";
import { login } from "../api/auth.js";

/* ================================================================
   BloodLink — Login

   Rebuilt in the shared design system: Inter throughout, navy brand
   panel carried over from the portal sidebars, red primary action
   matching the Register button on the public landing page.

   Wired to POST /api/auth/login. The layout, styling and fields are
   the mockup's, unchanged; only the submit behaviour is real:
   the inputs became controlled, the wrapper became a <form> so Enter
   submits, and two on-brand states were added — an error banner and a
   busy submit button — because a form with no feedback cannot be used.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

export default function Login({ onSignedIn }) {
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [remember, setRemember] = useState(true);

  // `error` is the banner message; `fieldErrors` marks individual inputs when the
  // backend rejects the payload with per-field validation messages.
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const [submitting, setSubmitting] = useState(false);

  /**
   * Authenticates against the API and hands the session up to the app shell.
   * The token itself is stored by login() — see api/auth.js — so nothing
   * credential-shaped stays in this component's state after submit.
   */
  async function handleSubmit(event) {
    event.preventDefault();
    if (submitting) return;

    setError(null);
    setFieldErrors({});
    setSubmitting(true);

    try {
      const auth = await login(phone.trim(), password, remember);
      onSignedIn?.(auth);
    } catch (err) {
      // A wrong password (401) and a validation failure (400) both land here;
      // the backend's message is written for display, so it is shown verbatim.
      setError(err.message);
      setFieldErrors(err.fieldErrors ?? {});
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="au-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        .au-root {
          --navy:      #1C3A5E;
          --navy-deep: #16304E;
          --red:       #C8102E;
          --red-dark:  #A50E26;
          --ink:       #111111;
          --ink-muted: #7A7A7A;
          --line:      #E1E4E8;
          --shadow:    0 2px 10px rgba(0,0,0,0.07);

          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
          color: var(--ink);
          background: #FFFFFF;
          height: 100vh;
          min-height: 680px;
          overflow: hidden;
          display: grid;
          grid-template-columns: 1.05fr 1fr;
          -webkit-font-smoothing: antialiased;
        }
        .au-root *, .au-root *::before, .au-root *::after { box-sizing: border-box; }

        /* ===== BRAND PANEL ===== */
        .au-brandside {
          position: relative;
          background: var(--navy);
          overflow: hidden;
          display: flex; flex-direction: column;
          padding: clamp(24px, 4.4vh, 48px) clamp(24px, 3.4vw, 58px);
        }
        .au-watermark { position: absolute; inset: 0; width: 100%; height: 100%; opacity: .13; }
        .au-brand { position: relative; display: flex; align-items: center; gap: 11px; }
        .au-brand-name { font-size: clamp(17px, 2.5vh, 22px); font-weight: 800; letter-spacing: -.02em; line-height: 1; }
        .au-brand-tag { font-size: clamp(5.4px, .86vh, 7px); letter-spacing: .14em; color: rgba(255,255,255,.7); margin-top: 4px; font-weight: 600; }

        .au-pitch { position: relative; margin-top: auto; margin-bottom: auto; }
        .au-pitch h1 {
          margin: 0;
          font-size: clamp(30px, 5.6vh, 54px);
          line-height: 1.07; font-weight: 800; letter-spacing: -.035em;
          color: #FFFFFF;
        }
        .au-pitch h1 span { display: block; color: #FF6B7F; }
        .au-pitch p {
          margin: clamp(12px, 2.2vh, 22px) 0 0;
          font-size: clamp(12px, 1.85vh, 16px);
          line-height: 1.55; color: rgba(255,255,255,.78);
          max-width: 40ch;
        }

        .au-facts { position: relative; display: flex; gap: clamp(18px, 2.4vw, 40px); }
        .au-fact-num { font-size: clamp(18px, 2.9vh, 26px); font-weight: 800; color: #FFFFFF; letter-spacing: -.02em; }
        .au-fact-lbl { font-size: clamp(9px, 1.3vh, 11.5px); color: rgba(255,255,255,.66); margin-top: 2px; font-weight: 500; }

        /* ===== FORM PANEL ===== */
        .au-formside {
          display: flex; align-items: center; justify-content: center;
          padding: clamp(20px, 3.4vh, 40px) clamp(22px, 3vw, 56px);
        }
        .au-form { width: 100%; max-width: 400px; margin: 0; }
        .au-h { font-size: clamp(22px, 3.6vh, 32px); font-weight: 800; letter-spacing: -.03em; margin: 0; }
        .au-p { font-size: clamp(11.5px, 1.7vh, 14px); color: var(--ink-muted); margin: 6px 0 clamp(16px, 2.8vh, 28px); }

        .au-field { display: flex; flex-direction: column; gap: 5px; margin-bottom: clamp(10px, 1.7vh, 16px); }
        .au-label { font-size: clamp(10px, 1.42vh, 12.5px); font-weight: 600; color: #3D3D3D; }
        .au-inwrap { position: relative; display: flex; align-items: center; }
        .au-inwrap svg { position: absolute; left: 13px; width: clamp(14px, 2vh, 17px); height: clamp(14px, 2vh, 17px); color: #9AA0A6; }
        .au-input {
          width: 100%; font-family: inherit;
          font-size: clamp(11.5px, 1.65vh, 14px); font-weight: 500; color: var(--ink);
          padding: clamp(9px, 1.5vh, 13px) 14px clamp(9px, 1.5vh, 13px) 40px;
          border: 1.5px solid var(--line); border-radius: 8px; background: #FBFBFC;
        }
        .au-input::placeholder { color: #A8ADB3; font-weight: 400; }
        .au-input:focus { outline: none; border-color: var(--navy); background: #FFF; }

        /* Wiring states — added for real submit feedback, using the existing tokens. */
        .au-input--bad { border-color: var(--red); }
        .au-err { font-size: clamp(9.5px, 1.3vh, 11.5px); font-weight: 500; color: var(--red); }
        .au-alert {
          background: rgba(200,16,46,.07);
          border: 1.5px solid rgba(200,16,46,.28);
          color: #8E0B21; border-radius: 8px;
          padding: clamp(8px, 1.3vh, 11px) 12px;
          font-size: clamp(10.5px, 1.5vh, 13px); font-weight: 500; line-height: 1.45;
          margin-bottom: clamp(10px, 1.7vh, 16px);
        }
        .au-submit:disabled { opacity: .68; cursor: progress; }

        .au-meta { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: clamp(2px, .8vh, 8px) 0 clamp(14px, 2.4vh, 22px); }
        .au-check { display: flex; align-items: center; gap: 8px; font-size: clamp(10.5px, 1.5vh, 13px); font-weight: 500; color: #3D3D3D; cursor: pointer; }
        .au-check input { width: 15px; height: 15px; accent-color: var(--navy); cursor: pointer; }
        .au-link { font-size: clamp(10.5px, 1.5vh, 13px); font-weight: 600; color: var(--red); text-decoration: none; }
        .au-link:hover { text-decoration: underline; }

        .au-submit {
          width: 100%; border: none; border-radius: 8px; cursor: pointer;
          background: var(--red); color: #FFF;
          font-family: inherit; font-weight: 700;
          font-size: clamp(13px, 1.95vh, 16px);
          padding: clamp(11px, 1.8vh, 15px);
          transition: background .15s ease;
        }
        .au-submit:hover { background: var(--red-dark); }
        .au-submit:focus-visible, .au-google:focus-visible { outline: 2px solid var(--navy); outline-offset: 2px; }

        .au-or { display: flex; align-items: center; gap: 12px; margin: clamp(12px, 2.2vh, 20px) 0; }
        .au-or::before, .au-or::after { content: ''; flex: 1; height: 1px; background: var(--line); }
        .au-or span { font-size: clamp(9.5px, 1.35vh, 11.5px); color: var(--ink-muted); font-weight: 500; }

        .au-google {
          width: 100%; display: flex; align-items: center; justify-content: center; gap: 10px;
          background: #FFF; border: 1.5px solid var(--line); border-radius: 8px; cursor: pointer;
          font-family: inherit; font-weight: 600; color: #2C2C2C;
          font-size: clamp(11.5px, 1.65vh, 14px);
          padding: clamp(10px, 1.6vh, 13px);
          transition: background .15s ease;
        }
        .au-google:hover { background: #F7F8F9; }
        .au-google svg { width: clamp(15px, 2.1vh, 18px); height: clamp(15px, 2.1vh, 18px); }

        .au-foot { text-align: center; margin-top: clamp(14px, 2.4vh, 24px); font-size: clamp(11px, 1.55vh, 13.5px); color: var(--ink-muted); }
        .au-foot a { color: var(--red); font-weight: 700; text-decoration: none; }
        .au-foot a:hover { text-decoration: underline; }

        @media (max-width: 900px) {
          .au-root { grid-template-columns: 1fr; }
          .au-brandside { display: none; }
        }
        @media (prefers-reduced-motion: reduce) { .au-root * { transition: none !important; } }
      `}</style>

      {/* ================= BRAND PANEL ================= */}
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
          <h1>Your Blood.<span>Someone&rsquo;s Hope.</span></h1>
          <p>
            Real-time blood bank logistics and voluntary donor management
            for hospitals and blood banks across Makeni City.
          </p>
        </div>

        <div className="au-facts">
          <div>
            <div className="au-fact-num">850+</div>
            <div className="au-fact-lbl">Registered donors</div>
          </div>
          <div>
            <div className="au-fact-num">320+</div>
            <div className="au-fact-lbl">Requests fulfilled</div>
          </div>
          <div>
            <div className="au-fact-num">6</div>
            <div className="au-fact-lbl">Partner facilities</div>
          </div>
        </div>
      </div>

      {/* ================= FORM PANEL ================= */}
      <div className="au-formside">
        <form className="au-form" onSubmit={handleSubmit} noValidate>
          <h2 className="au-h">Welcome back</h2>
          <p className="au-p">Sign in to continue to your BloodLink account.</p>

          {error && <div className="au-alert" role="alert">{error}</div>}

          <div className="au-field">
            <label className="au-label" htmlFor="au-phone">Phone Number</label>
            <div className="au-inwrap">
              <PhoneIcon />
              <input
                id="au-phone"
                className={`au-input${fieldErrors.phone ? " au-input--bad" : ""}`}
                type="tel"
                placeholder="+232 76 000 000"
                autoComplete="tel"
                value={phone}
                onChange={(e) => setPhone(e.target.value)}
              />
            </div>
            {fieldErrors.phone && <span className="au-err">{fieldErrors.phone}</span>}
          </div>

          <div className="au-field">
            <label className="au-label" htmlFor="au-pass">Password</label>
            <div className="au-inwrap">
              <LockIcon />
              <input
                id="au-pass"
                className={`au-input${fieldErrors.password ? " au-input--bad" : ""}`}
                type="password"
                placeholder="Enter your password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            {fieldErrors.password && <span className="au-err">{fieldErrors.password}</span>}
          </div>

          <div className="au-meta">
            <label className="au-check">
              <input
                type="checkbox"
                checked={remember}
                onChange={(e) => setRemember(e.target.checked)}
              /> Remember me
            </label>
            <a className="au-link" href="#forgot">Forgot password?</a>
          </div>

          <button type="submit" className="au-submit" disabled={submitting}>
            {submitting ? "Signing in…" : "Sign in"}
          </button>

          <div className="au-or"><span>or continue with</span></div>

          {/* Social sign-in is not part of this phase — the button is intentionally inert. */}
          <button type="button" className="au-google"><GoogleIcon /> Google</button>

          <div className="au-foot">
            Don&rsquo;t have an account? <a href="#register">Create one</a>
          </div>
        </form>
      </div>
    </div>
  );
}

/* ================= ART ================= */
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

/* ================= ICONS ================= */
function PhoneIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><path d="M22 16.9v3a2 2 0 0 1-2.2 2 19.8 19.8 0 0 1-8.6-3.1 19.5 19.5 0 0 1-6-6A19.8 19.8 0 0 1 2.1 4.2 2 2 0 0 1 4.1 2h3a2 2 0 0 1 2 1.7c.1 1 .4 1.9.7 2.8a2 2 0 0 1-.5 2.1L8.1 9.9a16 16 0 0 0 6 6l1.3-1.2a2 2 0 0 1 2.1-.5c.9.3 1.8.6 2.8.7a2 2 0 0 1 1.7 2z"/></svg>); }
function LockIcon() { return (<svg viewBox="0 0 24 24" {...ICON}><rect x="3.5" y="10.5" width="17" height="11" rx="2.5"/><path d="M7.5 10.5V7a4.5 4.5 0 0 1 9 0v3.5"/></svg>); }
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
