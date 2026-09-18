import React from "react";

/* ================================================================
   BloodLink — Public Landing Page

   ▸ FITS THE SCREEN. The page is locked to 100vh and never scrolls.
     All sizing is in vh/clamp units so it scales with the window.

   ▸ Self-contained: all artwork is inline SVG, so the file renders
     anywhere with no image assets, bundler or network needed.

   Wired into App.jsx as the root route (no hash) for a signed-out visitor;
   a signed-in visitor never sees it, and #login / #register still go
   straight to those screens regardless of this page. The layout and
   styling are the mockup's, unchanged — the only wiring needed is real
   navigation: the three Login/Register buttons became <a> tags pointing
   at the existing #login and #register hash routes App.jsx already
   listens for, in place of the mockup's inert onClick-less buttons.

   The header's About and Contact are the two links that got no such
   destination, because no such page exists. They are inert <span>s rather
   than <a>s — see the comment above them in the header for why that is a
   fix and not a cosmetic choice.
   ================================================================ */

export default function BloodLinkLanding() {
  return (
    <div className="bl-root">
      <style>{`
        @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');

        /* ===== THEME TOKENS — edit here to restyle everything ===== */
        .bl-root {
          --navy:      #1C3A5E;
          --red:       #C8102E;
          --red-dark:  #A50E26;
          --green:     #1E7F45;
          --hero-bg:   #F1F2F4;
          --card-bg:   #FFFFFF;
          --ink:       #111111;
          --ink-muted: #7A7A7A;
          --radius:    10px;
          --shadow:    0 2px 10px rgba(0,0,0,0.08);

          font-family: 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif;
          color: var(--ink);

          /* ---- viewport lock: no scrollbars ---- */
          height: 100vh;
          min-height: 680px;
          overflow: hidden;
          display: flex;
          flex-direction: column;
        }
        .bl-root *, .bl-root *::before, .bl-root *::after { box-sizing: border-box; }

        /* ===== HEADER ===== */
        .bl-header {
          flex: 0 0 auto;
          background: var(--navy);
          display: flex;
          align-items: center;
          justify-content: space-between;
          gap: 24px;
          padding: clamp(8px, 1.6vh, 15px) clamp(18px, 2.4vw, 34px);
        }
        .bl-nav { display: flex; align-items: center; gap: clamp(18px, 2.4vw, 34px); }
        /* Covers both a real <a> and the inert <span>s About/Contact are for now,
           so the header keeps the mockup's look whichever one is rendered. */
        .bl-nav a, .bl-nav span {
          color: #FFF;
          font-size: clamp(14px, 2.1vh, 19px);
          font-weight: 700;
          text-decoration: none;
          letter-spacing: -0.01em;
        }
        .bl-nav a:hover { opacity: 0.82; }
        /* No hover fade and the ordinary arrow cursor: the hover cue belongs to
           things that do something when clicked, and these two do not yet. */
        .bl-nav span { cursor: default; }
        .bl-nav a:focus-visible, .bl-btn:focus-visible, .bl-cta:focus-visible {
          outline: 2px solid #FFF; outline-offset: 3px;
        }

        .bl-actions { display: flex; align-items: center; gap: 12px; }
        /* Real <a> elements now (routed to #login / #register), not the mockup's
           inert <button>s — text-decoration and color are reset since an anchor
           carries browser link styling a button never did. */
        .bl-btn {
          display: inline-flex; align-items: center; gap: 8px;
          font-family: inherit;
          font-size: clamp(12px, 1.7vh, 15px);
          font-weight: 600;
          padding: clamp(7px, 1.2vh, 10px) clamp(13px, 1.5vw, 20px);
          border-radius: 6px; cursor: pointer;
          text-decoration: none;
          transition: background .15s ease, border-color .15s ease;
        }
        .bl-btn--ghost { background: transparent; color: #FFF; border: 1.5px solid rgba(255,255,255,.85); }
        .bl-btn--ghost:hover { background: rgba(255,255,255,.1); }
        .bl-btn--solid { background: var(--red); color: #FFF; border: 1.5px solid var(--red); }
        .bl-btn--solid:hover { background: var(--red-dark); border-color: var(--red-dark); }

        /* ===== HERO ===== */
        .bl-hero {
          flex: 1 1 auto;
          min-height: 0;                 /* lets the grid shrink instead of overflowing */
          background: var(--hero-bg);
          padding: clamp(14px, 2.6vh, 34px) clamp(18px, 2.4vw, 34px) clamp(12px, 2vh, 26px);
          display: flex;
          flex-direction: column;
          gap: clamp(10px, 2vh, 22px);
        }
        .bl-hero-grid {
          flex: 1 1 auto;
          min-height: 0;
          display: grid;
          grid-template-columns: 1fr 1fr;
          align-items: center;
          gap: 20px;
        }
        .bl-headline {
          margin: 0 0 clamp(14px, 3.4vh, 34px);
          font-size: clamp(30px, 6.4vh, 64px);
          line-height: 1.06;
          font-weight: 800;
          letter-spacing: -0.035em;
        }
        .bl-headline span { display: block; }
        .bl-headline .accent { color: var(--red); }

        /* Also now an <a href="#register">, not a button — same reset as .bl-btn. */
        .bl-cta {
          display: inline-flex; align-items: center; gap: 10px;
          background: var(--red); color: #FFF; border: none; border-radius: 7px;
          font-family: inherit;
          font-size: clamp(17px, 2.8vh, 25px);
          font-weight: 700; letter-spacing: -0.02em;
          padding: clamp(10px, 1.8vh, 15px) clamp(20px, 2.2vw, 30px);
          cursor: pointer; box-shadow: var(--shadow);
          text-decoration: none;
          transition: background .15s ease, transform .15s ease;
        }
        .bl-cta:hover { background: var(--red-dark); }
        .bl-cta:active { transform: translateY(1px); }

        /* ===== HERO ARTWORK ===== */
        .bl-art {
          position: relative;
          height: 100%;
          min-height: 0;
          display: flex;
          align-items: center;
          justify-content: center;
        }
        /* blurred room photo / gradient stand-in */
        .bl-art-room {
          position: absolute;
          right: 2%;
          top: 50%;
          transform: translateY(-50%);
          width: 74%;
          aspect-ratio: 1 / 1;
          max-height: 96%;
          border-radius: 50%;
          overflow: hidden;
          filter: blur(2px);
          opacity: .85;
        }
        .bl-art-room img { width: 100%; height: 100%; object-fit: cover; display: block; }
        .bl-art-room--fallback {
          background:
            radial-gradient(circle at 30% 25%, #FFFFFF 0%, rgba(255,255,255,0) 55%),
            radial-gradient(circle at 72% 68%, #DCE7F2 0%, rgba(220,231,242,0) 60%),
            linear-gradient(160deg, #EEF3F8 0%, #DCE4ED 55%, #CFDAE6 100%);
          filter: blur(6px);
        }
        /* the red arc sweep */
        .bl-art-arc { position: absolute; inset: 0; width: 100%; height: 100%; }
        /* the bag itself */
        .bl-art-bag {
          position: relative;
          height: 100%;
          max-height: 100%;
          width: auto;
          object-fit: contain;
          display: block;
        }
        .bl-art svg.bl-art-bag { height: 100%; width: 100%; }

        /* ===== STAT CARDS ===== */
        .bl-stats {
          flex: 0 0 auto;
          display: grid;
          grid-template-columns: repeat(4, 1fr);
          gap: clamp(10px, 1.4vw, 22px);
        }
        .bl-card {
          background: var(--card-bg); border-radius: var(--radius); box-shadow: var(--shadow);
          padding: clamp(10px, 1.7vh, 18px) clamp(12px, 1.3vw, 20px);
          display: flex; align-items: center; gap: clamp(9px, .9vw, 15px);
        }
        .bl-card-icon {
          flex: 0 0 auto;
          width: clamp(34px, 5.2vh, 52px);
          height: clamp(34px, 5.2vh, 52px);
          border-radius: 50%; display: grid; place-items: center;
        }
        .bl-card-icon svg { width: 55%; height: 55%; }
        .bl-card-icon--red { background: var(--red); }
        .bl-card-icon--green { background: var(--green); }

        .bl-card-figure { font-size: clamp(18px, 3vh, 27px); font-weight: 800; line-height: 1.1; letter-spacing: -.02em; }
        .bl-card-label  { font-size: clamp(11px, 1.7vh, 15px); font-weight: 700; line-height: 1.25; margin-top: 1px; }
        .bl-card-note   { font-size: clamp(8.5px, 1.25vh, 11px); color: var(--ink-muted); line-height: 1.35; margin-top: 3px; }

        /* ===== FOOTER ===== */
        .bl-footer {
          flex: 0 0 auto;
          background: var(--navy);
          padding: clamp(11px, 2.2vh, 22px) clamp(18px, 2.4vw, 34px);
          display: flex; align-items: center; gap: 13px;
        }
        .bl-footer svg { width: clamp(22px, 3.4vh, 34px); height: clamp(22px, 3.4vh, 34px); }
        .bl-footer-text { color: #FFF; font-size: clamp(14px, 2.1vh, 19px); font-weight: 700; letter-spacing: -.01em; }
        .bl-footer-text .accent { color: var(--red); }

        /* ===== NARROW SCREENS =====
           Below 760px the hero stacks; we allow scrolling there because
           four stacked cards genuinely cannot fit one phone screen. */
        @media (max-width: 760px) {
          .bl-root { height: auto; max-height: none; overflow: visible; }
          .bl-header { flex-wrap: wrap; gap: 12px; }
          .bl-nav { order: 3; width: 100%; justify-content: center; }
          .bl-hero-grid { grid-template-columns: 1fr; }
          .bl-art { min-height: 240px; }
          .bl-stats { grid-template-columns: 1fr 1fr; }
        }

        @media (prefers-reduced-motion: reduce) { .bl-root * { transition: none !important; } }
      `}</style>

      {/* ================= HEADER ================= */}
      <header className="bl-header">
        <Logo />
        {/* About and Contact are deliberately plain <span>s, not links. As
            <a href="#about"> / <a href="#contact"> they sent the browser to a hash
            App.jsx's readRoute does not recognise, and its fallback for an
            unrecognised hash is the Login screen — so clicking "About" signed-out
            landed the visitor on a login form, with no way back but the browser's
            Back button. There are no About or Contact pages to point at yet, so
            they carry no href, no click handler and no focus stop: nothing
            happens, and nothing promises that anything will. Give each an href
            once there is a real page behind it — and add the hash to readRoute at
            the same time, since that is the half whose absence caused this. */}
        <nav className="bl-nav">
          <span>About</span>
          <span>Contact</span>
        </nav>
        <div className="bl-actions">
          <a href="#login" className="bl-btn bl-btn--ghost">
            <UserIcon /> Login
          </a>
          <a href="#register" className="bl-btn bl-btn--solid">
            <UserIcon /> Register
          </a>
        </div>
      </header>

      {/* ================= HERO ================= */}
      <section className="bl-hero">
        <div className="bl-hero-grid">
          <div>
            <h1 className="bl-headline">
              <span>Your Blood.</span>
              <span className="accent">Someone&rsquo;s Hope.</span>
            </h1>
            <a href="#register" className="bl-cta">
              <DropletIcon color="#FFFFFF" /> Register
            </a>
          </div>

          <div className="bl-art">
            {/* soft blurred clinic backdrop behind the bag */}
            <div className="bl-art-room bl-art-room--fallback" aria-hidden="true" />

            {/* red arc + ECG heart watermark */}
            <svg className="bl-art-arc" viewBox="0 0 470 470" aria-hidden="true" preserveAspectRatio="xMidYMid meet">
              <g opacity="0.55">
                <path d="M105 168c0-22 32-30 44-10 12-20 44-12 44 10 0 26-44 50-44 50s-44-24-44-50z"
                      fill="none" stroke="#FFF" strokeWidth="4" />
                <path d="M62 196h34l11-26 15 52 13-34 9 8h44"
                      fill="none" stroke="#FFF" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round" />
              </g>
              <path d="M300 34C150 44 96 168 128 268c22 68 96 112 172 116"
                    fill="none" stroke="#C8102E" strokeWidth="15" strokeLinecap="round" />
            </svg>

            {/* the bag */}
            <BloodBagArt />
          </div>
        </div>

        <div className="bl-stats">
          <StatCard tone="red" icon={<DonorsIcon />} figure="850+"
                    label="Registered Donors" note="voluntary donors in Makeni" />
          <StatCard tone="green" icon={<PulseIcon />} figure="98%"
                    label="Availability Rate" note="Real-time stock accuracy" />
          <StatCard tone="red" icon={<DropletIcon color="#FFFFFF" />} figure="320+"
                    label="Blood Requests" note="Successfully fulfilled" />
          <StatCard tone="green" icon={<CheckIcon />}
                    label="Secure & Reliable System"
                    note="Your data is safe with us — we ensure privacy, security and accuracy in every process" />
        </div>
      </section>

      {/* ================= FOOTER ================= */}
      <footer className="bl-footer">
        <DropletIcon color="#C8102E" />
        <div className="bl-footer-text">
          Donate Blood. <span className="accent">Give Hope.</span>
        </div>
      </footer>
    </div>
  );
}

/* ================= SMALL PIECES ================= */

function StatCard({ tone, icon, figure, label, note }) {
  return (
    <div className="bl-card">
      <div className={`bl-card-icon bl-card-icon--${tone}`}>{icon}</div>
      <div>
        {figure && <div className="bl-card-figure">{figure}</div>}
        <div className="bl-card-label">{label}</div>
        <div className="bl-card-note">{note}</div>
      </div>
    </div>
  );
}

function Logo() {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 9 }}>
      <svg width="30" height="34" viewBox="0 0 34 38" aria-hidden="true">
        <path d="M17 2s13 14 13 22a13 13 0 0 1-26 0C4 16 17 2 17 2z" fill="#C8102E" />
        <path d="M7 24h5l2.5-6 3.5 12 3-8 2 2h5" fill="none" stroke="#FFF"
              strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <div style={{ lineHeight: 1 }}>
        <div style={{ fontSize: 15, fontWeight: 800, letterSpacing: "-0.02em" }}>
          <span style={{ color: "#C8102E" }}>Blood</span>
          <span style={{ color: "#FFFFFF" }}>Link</span>
        </div>
        <div style={{ fontSize: 5.6, letterSpacing: "0.14em", color: "rgba(255,255,255,.72)", marginTop: 3, fontWeight: 600 }}>
          GIVE BLOOD · SAVE LIVES
        </div>
      </div>
    </div>
  );
}

/* Hero artwork — blood bag, drawn as vector so it stays sharp at any size. */
function BloodBagArt() {
  const widths = [2, 1, 3, 1, 1, 2, 4, 1, 2, 1, 3, 2, 1, 1, 3, 1, 2, 2];
  let cursor = 0;
  const bars = widths.map((w, i) => {
    const bar = <rect key={i} x={cursor} y={0} width={w} height={15} fill="#141414" />;
    cursor += w + 2;
    return bar;
  });

  return (
    <svg className="bl-art-bag" viewBox="0 0 470 470" preserveAspectRatio="xMidYMid meet"
         role="img" aria-label="Whole blood donation bag, blood group B positive">
      <defs>
        <linearGradient id="blBlood" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#C01029" />
          <stop offset="42%" stopColor="#94091F" />
          <stop offset="100%" stopColor="#630614" />
        </linearGradient>
        <linearGradient id="blGlass" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="#FFF" stopOpacity=".62" />
          <stop offset="42%" stopColor="#FFF" stopOpacity=".16" />
          <stop offset="100%" stopColor="#FFF" stopOpacity=".46" />
        </linearGradient>
        <radialGradient id="blSheen" cx=".3" cy=".22" r=".55">
          <stop offset="0%" stopColor="#FFF" stopOpacity=".38" />
          <stop offset="100%" stopColor="#FFF" stopOpacity="0" />
        </radialGradient>
      </defs>

      {/* tubing */}
      <path d="M286 16c0 46-36 48-36 80" fill="none" stroke="#A80D22" strokeWidth="7" strokeLinecap="round" />
      {/* ports */}
      <rect x="222" y="72" width="16" height="34" rx="6" fill="#D8DDE4" />
      <rect x="244" y="64" width="16" height="42" rx="6" fill="#C6CDD6" />
      <rect x="266" y="72" width="16" height="34" rx="6" fill="#D8DDE4" />

      {/* bag */}
      <rect x="176" y="100" width="176" height="288" rx="22" fill="url(#blGlass)" stroke="#B9C1CB" strokeWidth="2.5" />
      <rect x="185" y="130" width="158" height="249" rx="17" fill="url(#blBlood)" />
      <rect x="185" y="130" width="158" height="249" rx="17" fill="url(#blSheen)" />

      {/* label */}
      <rect x="200" y="174" width="128" height="128" rx="4" fill="#FCFCFC" />
      <text x="210" y="220" fontFamily="Inter, Arial, sans-serif" fontSize="34" fontWeight="800" fill="#A80D22">B+</text>
      <g transform="translate(286 188)">{bars}</g>
      <text x="210" y="238" fontFamily="Inter, Arial, sans-serif" fontSize="7.5" fill="#666">450 ml</text>
      <line x1="210" y1="248" x2="318" y2="248" stroke="#E2E2E2" strokeWidth="1" />
      <text x="210" y="262" fontFamily="Inter, Arial, sans-serif" fontSize="8" fontWeight="700" fill="#222">VOLUNTEER DONOR</text>
      <text x="210" y="275" fontFamily="Inter, Arial, sans-serif" fontSize="8" fontWeight="700" fill="#222">WHOLE BLOOD</text>
      <g transform="translate(210 283)">{bars}</g>

      {/* specular highlight */}
      <rect x="192" y="138" width="13" height="232" rx="7" fill="#FFF" opacity=".26" />
    </svg>
  );
}

/* ================= ICONS ================= */
function UserIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21c0-4.4 3.6-7 8-7s8 2.6 8 7z" />
    </svg>
  );
}
function DropletIcon({ color = "#FFFFFF" }) {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill={color} aria-hidden="true">
      <path d="M12 2S5 10 5 14.5a7 7 0 0 0 14 0C19 10 12 2 12 2z" />
    </svg>
  );
}
function DonorsIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="#FFFFFF" aria-hidden="true">
      <circle cx="9" cy="8" r="3.4" />
      <circle cx="17" cy="9" r="2.7" />
      <path d="M2 20c0-3.6 3.1-5.8 7-5.8s7 2.2 7 5.8z" />
      <path d="M16.5 14.4c3 .3 5.5 2.3 5.5 5.6h-5.2c0-2-.4-3.9-.3-5.6z" />
    </svg>
  );
}
function PulseIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="2.1"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M2 12h4l2.5-6 4 13 3-8 2 1h4.5" />
    </svg>
  );
}
function CheckIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="2.6"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 12.5 9.5 18 20 7" />
    </svg>
  );
}
