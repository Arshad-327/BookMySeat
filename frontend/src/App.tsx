import { Route, Routes } from 'react-router-dom'

import { API_BASE_URL } from './config'

/** The scaffold's only screen. Replaced by the real routes in the next commit. */
function Placeholder() {
  return (
    <main className="mx-auto max-w-2xl p-8">
      <h1 className="text-2xl font-semibold text-slate-900">BookMySeat</h1>
      <p className="mt-2 text-slate-600">
        The scaffold builds and renders. Nothing here calls the API yet; when it does, it will
        call <code className="rounded bg-slate-100 px-1">{API_BASE_URL}</code>.
      </p>
    </main>
  )
}

export function App() {
  return (
    <Routes>
      <Route path="*" element={<Placeholder />} />
    </Routes>
  )
}
