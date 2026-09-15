import { useEffect, useState } from 'react';
import BloodLinkLanding from './pages/BloodLinkLanding.jsx';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import DonorPortal from './pages/DonorPortal.jsx';
import BloodBankPortal from './pages/BloodBankPortal.jsx';
import HospitalPortal from './pages/HospitalPortal.jsx';
import AdminConsole from './pages/AdminConsole.jsx';
import { clearSession, getUser, isSignedIn } from './auth/session.js';

/* ================================================================
   BloodLink — app shell

   Routes between the public landing page, the two auth screens, and, once
   signed in, a role-appropriate view. Routing is on the URL hash because
   that is what the mockups' own links already use (#login, #register) —
   no router dependency needed for a handful of screens. All four portals
   are wired now; SignedInPlaceholder remains only as the fallback for a
   role the backend might add before a screen exists for it.

   The root URL (no hash) is the landing page for a signed-out visitor —
   BloodLinkLanding's own Login/Register buttons are plain <a href="#login">
   / <a href="#register"> links, so clicking them is indistinguishable from
   typing the hash directly and needs no wiring here beyond rendering the
   page. A signed-in visitor never sees it: the role-dashboard check below
   runs first and returns before route is ever consulted, exactly as it did
   before this page existed — a returning user's own hash (if any) is not
   even read in that case.

   ADMIN now lands on the Admin Console rather than the Blood Bank portal.
   That was a stopgap from before the console existed — of the two, the
   Blood Bank portal was where an administrator had actual authority — and
   it is retired now that the console itself does real account management
   and reporting. The trade-off worth knowing: an administrator can no
   longer reach the Blood Bank/Hospital portals' bank-picker flow (approving
   or fulfilling a request, adding stock) by simply signing in, since routing
   here is by role rather than by an in-app switcher between portals. Those
   actions are all still reachable over the API exactly as before; only the
   one-click UI path from a fresh sign-in has moved.
   ================================================================ */

/**
 * Reads the current screen from the hash.
 *
 * An empty hash — the bare root URL — is the landing page, not login: that is
 * the one behavioural change from before this page existed. '#login' and
 * '#register' are unchanged, and so is the fallback for anything else, which
 * stays 'login' exactly as it always has (an unrecognised hash is not treated
 * as the root).
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

  // Keeps the view in step with the browser's back/forward buttons and with the
  // in-page "Create one" / "Sign in" links, which only change the hash.
  useEffect(() => {
    const onHashChange = () => setRoute(readRoute());
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  /** Shared success path for both screens — the token is already stored by then. */
  function handleSignedIn(auth) {
    setUser(auth.user);
  }

  function handleSignOut() {
    clearSession();
    setUser(null);
    window.location.hash = '#login';
  }

  if (user) {
    if (user.role === 'DONOR') {
      return <DonorPortal user={user} onSignOut={handleSignOut} />;
    }
    if (user.role === 'BLOOD_BANK_STAFF') {
      return <BloodBankPortal user={user} onSignOut={handleSignOut} />;
    }
    if (user.role === 'HOSPITAL_STAFF') {
      return <HospitalPortal user={user} onSignOut={handleSignOut} />;
    }
    if (user.role === 'ADMIN') {
      return <AdminConsole user={user} onSignOut={handleSignOut} />;
    }
    return <SignedInPlaceholder user={user} onSignOut={handleSignOut} />;
  }

  if (route === 'landing') {
    return <BloodLinkLanding />;
  }

  return route === 'register'
    ? <Register onSignedIn={handleSignedIn} />
    : <Login onSignedIn={handleSignedIn} />;
}

/**
 * Stand-in for the role dashboards, which are a later phase.
 * It exists so this phase can be verified end to end: reaching this screen proves
 * a token was issued, stored, and carries the role the backend assigned.
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
        <p>{user.email} · {user.phone}</p>
        <button type="button" onClick={onSignOut}>Sign out</button>
      </section>
    </main>
  );
}
