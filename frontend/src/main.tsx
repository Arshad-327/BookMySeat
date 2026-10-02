import { QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'

import { App } from './App'
import { AuthProvider } from './auth/AuthContext'
import './index.css'
import { queryClient } from './queryClient'

const root = document.getElementById('root')
if (!root) {
  throw new Error('index.html has no #root element')
}

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        {/*
          Both flags opt in to how React Router 7 will behave, and silence the warning v6
          prints about each on every load. Neither changes anything this app does today:

          v7_startTransition   router state updates are wrapped in React.startTransition.
                               The documented effect is on React.lazy and Suspense, and
                               this app uses neither.
          v7_relativeSplatPath changes how a RELATIVE link resolves inside a splat route
                               (path="*"). The one splat route here redirects to the
                               absolute path "/", and no link in the app is relative.

          If a lazy route or a relative link inside the splat is ever added, these are the
          two lines to re-read.
        */}
        <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
)
