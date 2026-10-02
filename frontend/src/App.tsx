import { Navigate, Route, Routes } from 'react-router-dom'

import { Header } from './components/Header'
import { BrowsePage } from './pages/BrowsePage'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'

/**
 * Three screens. Browse is public, like the API behind it: a visitor sees the catalogue
 * before being asked to sign in.
 */
export function App() {
  return (
    <div className="min-h-screen bg-slate-50">
      <Header />
      <Routes>
        <Route path="/" element={<BrowsePage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  )
}
