import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import './styles.css';

// Apply the saved theme before first paint (no flash); the popup re-applies it from settings.
try {
  const t = localStorage.getItem('clipverse.theme');
  if (t) document.documentElement.dataset.theme = t;
} catch { /* storage unavailable */ }

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
