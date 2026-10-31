import React from "react";
import { passwordChecklist } from "../lib/passwordRules.js";

/* BloodLink — the live password checklist shown under every "choose a password" box: the five parts of the rule,
   each ticked green as it is met. Rules from lib/passwordRules.js; styles scoped under .pwc-root. */

const ICON = { fill: "none", stroke: "currentColor", strokeWidth: 2.4, strokeLinecap: "round", strokeLinejoin: "round" };

export default function PasswordChecklist({ password, id, className = "" }) {
  return (
    <div className={`pwc-root ${className}`.trim()}>
      <style>{`
        .pwc-list {
          list-style: none; margin: 2px 0 0; padding: 0;
          display: grid; grid-template-columns: repeat(auto-fill, minmax(165px, 1fr)); gap: 3px 12px;
        }
        .pwc-item {
          display: flex; align-items: center; gap: 7px;
          font-family: inherit; font-size: 11.5px; font-weight: 500; line-height: 1.35; color: #6B7280;
        }
        .pwc-item--met { color: #1E7F45; }
        .pwc-mark {
          flex: none; width: 15px; height: 15px; border-radius: 50%; box-sizing: border-box;
          display: inline-flex; align-items: center; justify-content: center;
          border: 1.5px solid #C5CAD1; background: #FFFFFF;
        }
        .pwc-item--met .pwc-mark { border-color: #1E7F45; background: #1E7F45; color: #FFFFFF; }
        .pwc-mark svg { width: 9px; height: 9px; }
        .pwc-sr {
          position: absolute; width: 1px; height: 1px; overflow: hidden;
          clip: rect(0 0 0 0); white-space: nowrap;
        }
      `}</style>
      <ul className="pwc-list" id={id} aria-label="Your password needs">
        {passwordChecklist(password).map((item) => (
          <li key={item.key} className={`pwc-item${item.met ? " pwc-item--met" : ""}`}>
            <span className="pwc-mark" aria-hidden="true">
              {item.met && <svg viewBox="0 0 12 12" {...ICON}><path d="M2.5 6.3l2.3 2.2 4.7-5" /></svg>}
            </span>
            {item.label}
            <span className="pwc-sr">{item.met ? " (done)" : " (still needed)"}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
