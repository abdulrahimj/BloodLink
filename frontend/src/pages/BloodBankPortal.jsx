import React, { useEffect, useState } from "react";
import BloodBankDashboard from "./BloodBankDashboard.jsx";
import BloodBankInventory from "./BloodBankInventory.jsx";
import BloodBankDonors from "./BloodBankDonors.jsx";
import { getBloodBanks } from "../api/bloodBanks.js";

/* BloodLink — Blood Bank portal shell: resolves which bank this session manages (staff go straight in,
   ADMIN or unassigned staff pick first), then switches its views with plain state. */

export default function BloodBankPortal({ user, onSignOut, onUserUpdated }) {
  const [bank, setBank] = useState(
    user.bloodBankId ? { id: user.bloodBankId, name: null } : null
  );
  const [view, setView] = useState("dashboard");

  const [choices, setChoices] = useState(null);
  const [pickerError, setPickerError] = useState(null);

  useEffect(() => {
    if (bank) return; // already resolved (assigned staff) — no picker needed
    let cancelled = false;

    getBloodBanks()
      .then((banks) => { if (!cancelled) setChoices(banks); })
      .catch((err) => { if (!cancelled) setPickerError(err.message); });

    return () => { cancelled = true; };
  }, [bank]);

  if (!bank) {
    return (
      <BankPicker
        choices={choices}
        error={pickerError}
        onChoose={(chosen) => setBank(chosen)}
        onSignOut={onSignOut}
      />
    );
  }

  // Four views from three page components; Settings is shown inside the Dashboard page,
  // keeping its sidebar and bell mounted.
  const shared = { bloodBankId: bank.id, user, onSignOut, activeView: view, onNavigate: setView, onUserUpdated };
  if (view === "inventory") return <BloodBankInventory {...shared} />;
  if (view === "donors") return <BloodBankDonors {...shared} />;
  return <BloodBankDashboard {...shared} />;
}

/**
 * Plain interstitial for ADMIN or unassigned staff to choose a bank, not a polished feature.
 */
function BankPicker({ choices, error, onChoose, onSignOut }) {
  return (
    <div style={{
      minHeight: "100vh", display: "flex", flexDirection: "column",
      alignItems: "center", justifyContent: "center", gap: 16,
      fontFamily: "'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Arial, sans-serif",
      background: "#F1F2F4", color: "#111111",
    }}>
      <div style={{ fontSize: 20, fontWeight: 800 }}>Choose a blood bank to manage</div>
      <div style={{ fontSize: 13, color: "#7A7A7A", marginTop: -8 }}>
        Your account isn&rsquo;t tied to one bank — pick one for this session.
      </div>

      {error && <div style={{ color: "#C8102E", fontWeight: 600, fontSize: 13 }}>{error}</div>}
      {!error && !choices && <div style={{ color: "#7A7A7A", fontSize: 13 }}>Loading blood banks…</div>}

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
