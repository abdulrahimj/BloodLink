import { useEffect, useState } from 'react';
import Login from './pages/Login.jsx';
import Register from './pages/Register.jsx';
import DonorPortal from './pages/DonorPortal.jsx';
import { clearSession, getUser, isSignedIn } from './auth/session.js';

/* ================================================================
   BloodLink — app shell

   Routes between the two auth screens and, once signed in, to a
   role-appropriate view. Routing is on the URL hash because that is
   what the mockups' own links already use (#login, #register) — no
   router dependency needed for a handful of screens. Only DONOR has a
   real portal so far; the other three roles still fall back to
   SignedInPlaceholder until their phases land.
   ================================================================ */

/** Reads the current screen from the hash, defaulting to login. */
function readRoute() {
  return window.location.hash === '#register' ? 'register' : 'login';
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
    return user.role === 'DONOR'
      ? <DonorPortal user={user} onSignOut={handleSignOut} />
      : <SignedInPlaceholder user={user} onSignOut={handleSignOut} />;
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
