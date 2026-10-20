import React from 'react';

/** Small-screen control for opening and closing a portal's navigation drawer. */
export default function MobileMenuToggle({ open, onClick, controls }) {
  return (
    <button
      type="button"
      className="mobile-menu-toggle"
      aria-label={open ? 'Close navigation menu' : 'Open navigation menu'}
      aria-expanded={open}
      aria-controls={controls}
      onClick={onClick}
    >
      <svg viewBox="0 0 24 24" aria-hidden="true">
        {open ? (
          <path d="m6 6 12 12M18 6 6 18" />
        ) : (
          <path d="M4 6h16M4 12h16M4 18h16" />
        )}
      </svg>
    </button>
  );
}
