import React from "react";

/* ================================================================
   BloodLink — Register (Voluntary Donor Sign-up)

   Fields follow the User and Donor entities from the Chapter Three
   ERD: full name, phone, email, date of birth, gender, blood group,
   area and password.
   ================================================================ */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };
const BLOOD_GROUPS = ["A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"];

export default function Register() {
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
        .au-form { width: 100%; max-width: 470px; }
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
        <div className="au-form">
          <h2 className="au-h">Create your account</h2>
          <p className="au-p">Register as a voluntary blood donor in Makeni City.</p>

          <div className="au-grid">
            <div className="au-field au-field--full">
              <label className="au-label" htmlFor="rg-name">Full Name</label>
              <input id="rg-name" className="au-input" type="text" placeholder="Enter your full name" />
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-phone">Phone Number</label>
              <input id="rg-phone" className="au-input" type="tel" placeholder="+232 76 000 000" />
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-email">Email Address</label>
              <input id="rg-email" className="au-input" type="email" placeholder="you@example.com" />
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-dob">Date of Birth</label>
              <input id="rg-dob" className="au-input" type="date" defaultValue="2000-01-01" />
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-gender">Gender</label>
              <select id="rg-gender" className="au-select" defaultValue="">
                <option value="" disabled>Select</option>
                <option>Female</option>
                <option>Male</option>
                <option>Prefer not to say</option>
              </select>
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-group">Blood Group</label>
              <select id="rg-group" className="au-select" defaultValue="">
                <option value="" disabled>Select</option>
                {BLOOD_GROUPS.map((g) => <option key={g}>{g}</option>)}
                <option>I don&rsquo;t know yet</option>
              </select>
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-area">Area / Community</label>
              <input id="rg-area" className="au-input" type="text" placeholder="e.g. Wusum, Makeni" />
            </div>

            <div className="au-field">
              <label className="au-label" htmlFor="rg-pass">Password</label>
              <input id="rg-pass" className="au-input" type="password" placeholder="Create a password" />
            </div>
            <div className="au-field">
              <label className="au-label" htmlFor="rg-pass2">Confirm Password</label>
              <input id="rg-pass2" className="au-input" type="password" placeholder="Re-enter password" />
            </div>
          </div>

          <label className="au-terms">
            <input type="checkbox" />
            <span>
              I agree to the <a href="#terms">terms and conditions</a> and consent to
              BloodLink storing my donor details.
            </span>
          </label>

          <button type="button" className="au-submit">Create account</button>

          <div className="au-or"><span>or sign up with</span></div>

          <button type="button" className="au-google"><GoogleIcon /> Google</button>

          <div className="au-foot">
            Already have an account? <a href="#login">Sign in</a>
          </div>
        </div>
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
