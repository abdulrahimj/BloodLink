import React, { useEffect, useState } from "react";
import HospitalDashboard from "./HospitalDashboard.jsx";
import { getHospitals } from "../api/hospitals.js";

/* ================================================================
   BloodLink — Hospital portal shell

   Resolves which hospital this session speaks for, then hands off to the
   wired request desk. The exact counterpart of BloodBankPortal, and
   deliberately the same shape — the two roles have the same problem
   (which facility am I?) and solving it two different ways would make
   both harder to follow.

   Hospital resolution:
     • HOSPITAL_STAFF with an assigned hospital (user.hospitalId) — straight
       in. The normal case, and what the mockup assumes: one fixed hospital
       identity in the header.
     • ADMIN, or staff an administrator has not yet assigned to a hospital
       (there is still no account-management UI to do that) — a small picker
       first, so those accounts can reach the screen at all.

   The picker is a convenience for looking around, not a grant: submitting a
   request is refused server-side for anyone whose own User row has no
   hospital, whatever they pick here (see RequestService.createRequest).
   ================================================================ */

export default function HospitalPortal({ user, onSignOut, onUserUpdated }) {
  const [hospital, setHospital] = useState(
    user.hospitalId ? { id: user.hospitalId, name: null } : null
  );

  const [choices, setChoices] = useState(null);
  const [pickerError, setPickerError] = useState(null);

  /**
   * Two jobs in one effect: fetch the picker's options when the hospital is
   * unresolved, and fill in the name when it is. An assigned staff member knows
   * their hospital's id from their token but not its name, and the header has to
   * show a name — this is the only place that can supply one.
   */
  useEffect(() => {
    let cancelled = false;

    if (hospital?.name) return; // fully resolved — nothing left to fetch

    getHospitals()
      .then((hospitals) => {
        if (cancelled) return;
        if (!hospital) {
          setChoices(hospitals);
          return;
        }
        const match = hospitals.find((h) => h.id === hospital.id);
        // Fall back to a neutral label rather than blanking the header if the
        // assigned hospital has somehow been removed from the reference list.
        setHospital({ id: hospital.id, name: match?.name ?? "Hospital Portal" });
      })
      .catch((err) => { if (!cancelled) setPickerError(err.message); });

    return () => { cancelled = true; };
  }, [hospital]);

  if (!hospital) {
    return (
      <HospitalPicker
        choices={choices}
        error={pickerError}
        onChoose={(chosen) => setHospital(chosen)}
        onSignOut={onSignOut}
      />
    );
  }

  return <HospitalDashboard hospital={hospital} user={user} onSignOut={onSignOut} onUserUpdated={onUserUpdated} />;
}

/**
 * Interstitial shown only for ADMIN or an unassigned staff account. Deliberately
 * plain rather than styled to match the portal screen — it exists so those
 * accounts can reach the (typically single-hospital) portal at all, not as a
 * polished feature in its own right. Mirrors BankPicker in BloodBankPortal.jsx.
 */
function HospitalPicker({ choices, error, onChoose, onSignOut }) {
  return (
    <div style={{
      minHeight: "100vh", display: "flex", flexDirection: "column",
      alignItems: "center", justifyContent: "center", gap: 16,
      fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif",
      background: "#F1F2F4", color: "#111111",
    }}>
      <div style={{ fontSize: 20, fontWeight: 800 }}>Choose a hospital</div>
      <div style={{ fontSize: 13, color: "#7A7A7A", marginTop: -8 }}>
        Your account isn&rsquo;t tied to one hospital — pick one for this session.
      </div>

      {error && <div style={{ color: "#C8102E", fontWeight: 600, fontSize: 13 }}>{error}</div>}
      {!error && !choices && <div style={{ color: "#7A7A7A", fontSize: 13 }}>Loading hospitals…</div>}

      {choices && (
        <div style={{ display: "flex", flexDirection: "column", gap: 10, minWidth: 280 }}>
          {choices.map((choice) => (
            <button
              key={choice.id}
              type="button"
              onClick={() => onChoose(choice)}
              style={{
                background: "#FFFFFF", border: "1.5px solid #E6E8EB", borderRadius: 8,
                padding: "12px 16px", fontFamily: "inherit", fontWeight: 700, fontSize: 14,
                cursor: "pointer", textAlign: "left", color: "#1C3A5E",
              }}
            >
              {choice.name}
              <div style={{ fontWeight: 500, fontSize: 12, color: "#7A7A7A", marginTop: 2 }}>
                {choice.location}
              </div>
            </button>
          ))}
        </div>
      )}

      <button
        type="button"
        onClick={onSignOut}
        style={{
          background: "transparent", border: "none", color: "#7A7A7A",
          fontFamily: "inherit", fontSize: 12.5, fontWeight: 600, cursor: "pointer", marginTop: 8,
        }}
      >
        Sign out
      </button>
    </div>
  );
}
