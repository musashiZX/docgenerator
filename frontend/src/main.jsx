import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
// Syncfusion Material theme — must be imported before any Syncfusion component renders
import '@syncfusion/ej2-documenteditor/styles/material.css';
import App from './App.jsx';

createRoot(document.getElementById('root')).render(
  <StrictMode>
    <App />
  </StrictMode>
);
