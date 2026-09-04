import { useEffect, useState } from 'react';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';

export default function App() {
  const [health, setHealth] = useState({ state: 'loading' });

  useEffect(() => {
    let cancelled = false;

    fetch(`${API_BASE_URL}/api/health`)
      .then((res) => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return res.json();
      })
      .then((data) => {
        if (!cancelled) setHealth({ state: 'ok', data });
      })
      .catch((err) => {
        if (!cancelled) setHealth({ state: 'error', message: err.message });
      });

    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <main className="app">
      <h1>BloodLink</h1>
      <p className="subtitle">Real-time blood bank logistics — Makeni City</p>

      <section className="card">
        <h2>Backend health</h2>
        {health.state === 'loading' && <p>Checking…</p>}
        {health.state === 'ok' && (
          <p className="status status--ok">
            {JSON.stringify(health.data)}
          </p>
        )}
        {health.state === 'error' && (
          <p className="status status--error">
            Could not reach backend: {health.message}
          </p>
        )}
      </section>
    </main>
  );
}
