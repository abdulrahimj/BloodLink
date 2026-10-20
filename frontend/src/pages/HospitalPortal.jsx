import React, { useEffect, useState } from "react";
import HospitalDashboard from "./HospitalDashboard.jsx";
import { getHospitals } from "../api/hospitals.js";

/* BloodLink — Hospital portal shell: resolves the hospital (staff go straight in, ADMIN or unassigned staff pick),
   mirroring BloodBankPortal. The picker grants nothing: requests still need the caller's own hospital. */

export default function HospitalPortal({ user, onSignOut, onUserUpdated }) {
  const [hospital, setHospital] = useState(
    user.hospitalId ? { id: user.hospitalId, name: null } : null
  );

  const [choices, setChoices] = useState(null);
  const [pickerError, setPickerError] = useState(null);

  /**
   * Fetches the picker's options when no hospital is resolved, or the hospital's name when one is.
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
 * Plain interstitial for ADMIN or unassigned staff to choose a hospital; mirrors BankPicker.
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
