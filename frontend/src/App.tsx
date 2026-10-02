import { Navigate, Route, Routes } from 'react-router-dom'

import { Header } from './components/Header'
import { BookingPage } from './pages/BookingPage'
import { BrowsePage } from './pages/BrowsePage'
import { EventPage } from './pages/EventPage'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { ShowPage } from './pages/ShowPage'

/**
 * Browse, the event page and the seat map are public, like the API behind them: a visitor
 * sees the catalogue and what is still available before being asked to sign in.
 */
export function App() {
  return (
    <div className="min-h-screen bg-slate-50">
      <Header />
      <Routes>
        <Route path="/" element={<BrowsePage />} />
        <Route path="/events/:id" element={<EventPage />} />
        <Route path="/shows/:id" element={<ShowPage />} />
        <Route path="/bookings/:id" element={<BookingPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </div>
  )
}
