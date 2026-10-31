import { useEffect, useState } from 'react';
import BloodLinkLanding from './pages/BloodLinkLanding.jsx';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import DonorPortal from './pages/DonorPortal.jsx';
import BloodBankPortal from './pages/BloodBankPortal.jsx';
import HospitalPortal from './pages/HospitalPortal.jsx';
import AdminConsole from './pages/AdminConsole.jsx';
import SetOwnPassword from './pages/SetOwnPassword.jsx';
import { SESSION_ENDED_EVENT, clearSession, getUser, isSignedIn, updateSessionUser } from './auth/session.js';
import { mustChangePassword } from './lib/passwordChange.js';

/* BloodLink — app shell: hash-routed landing page, login/register, and each role's portal once signed in
   (ADMIN goes to the Admin Console). No router dependency for a handful of screens. */

/**
 * Reads the current screen from the hash: empty is the landing page, '#register' register,
 * anything else login.
 */
function readRoute() {
  if (window.location.hash === '#register') return 'register';
  if (window.location.hash === '') return 'landing';
  return 'login';
}

export default function App() {
  const [route, setRoute] = useState(readRoute);
  // Seeded from storage so a refresh keeps the user signed in rather than
  // bouncing them back to the login screen.
  const [user, setUser] = useState(() => (isSignedIn() ? getUser() : null));

  // Why the server ended the last session, shown on Sign in until the next sign-in (Business Rule 32).
  const [signInNotice, setSignInNotice] = useState(null);

  // Keeps the view in step with the browser's back/forward buttons and with the
  // in-page "Create one" / "Sign in" links, which only change the hash.
  useEffect(() => {
    const onHashChange = () => setRoute(readRoute());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  // The session is already cleared when this fires (auth/session.js endSession); close the portal and say why.
  useEffect(() => {
    const onSessionEnded = (event) => {
      setUser(null);
      setSignInNotice(event.detail?.message ?? null);
      window.location.hash = '#login';
    };
    window.addEventListener(SESSION_ENDED_EVENT, onSessionEnded);
    return () => window.removeEventListener(SESSION_ENDED_EVENT, onSessionEnded);
  }, []);

  /** Shared success path for both screens — the token is already stored by then. */
  function handleSignedIn(auth) {
    setSignInNotice(null);
    setUser(auth.user);
  }

  function handleSignOut() {
    clearSession();
    setSignInNotice(null);
    setUser(null);
    window.location.hash = '#login';
  }

  /**
   * After Profile Settings saves a new name or email (Business Rule 16), updates this state and the stored session;
   * the token is untouched.
   */
  function handleUserUpdated(changes) {
    const stored = updateSessionUser(changes);
    setUser((current) => (current ? { ...current, ...(stored ?? changes) } : current));
  }

  // Business Rule 27: a temporary password must be replaced before any portal opens (the server refuses the rest).
  if (user && mustChangePassword(user)) {
    return (
      <SetOwnPassword
        user={user}
        onPasswordChanged={() => handleUserUpdated({ mustChangePassword: false })}
        onSignOut={handleSignOut}
      />
    );
  }

  if (user) {
    const portalProps = { user, onSignOut: handleSignOut, onUserUpdated: handleUserUpdated };
    if (user.role === 'DONOR') {
      return <DonorPortal {...portalProps} />;
    }
    if (user.role === 'BLOOD_BANK_STAFF') {
      return <BloodBankPortal {...portalProps} />;
    }
    if (user.role === 'HOSPITAL_STAFF') {
      return <HospitalPortal {...portalProps} />;
    }
    if (user.role === 'ADMIN') {
      return <AdminConsole {...portalProps} />;
    }
    return <SignedInPlaceholder user={user} onSignOut={handleSignOut} />;
  }

  if (route === 'landing') {
    return <BloodLinkLanding />;
  }

  return route === 'register'
    ? <Register onSignedIn={handleSignedIn} />
    : <Login onSignedIn={handleSignedIn} notice={signInNotice} />;
}

/**
 * Fallback screen for a role with no portal yet; reaching it proves a token was issued and stored.
 */
function SignedInPlaceholder({ user, onSignOut }) {
  return (
    <main className="app">
      <h1>BloodLink</h1>
      <p className="subtitle">Real-time blood bank logistics — Makeni City</p>

      <section className="card">
        <h2>Signed in</h2>
        <p className="status status--ok">
          {user.fullName} · {user.role}
        </p>
        {/* A donor may have no email (Business Rule 18) — list only what exists. */}
        <p>{[user.email, user.phone].filter(Boolean).join(" · ")}</p>
        <button type="button" onClick={onSignOut}>Sign out</button>
      </section>
    </main>
  );
}
