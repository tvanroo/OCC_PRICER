import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { api, ApiError, type Me } from './api'
import PriceCheck from './pages/PriceCheck'
import { Login, Signup } from './pages/Auth'
import StoreApp from './pages/StoreApp'

export default function App() {
  const [me, setMe] = useState<Me | null | undefined>(undefined)
  const refresh = useCallback(
    () => api<Me>('/api/auth/me').then(setMe).catch(() => setMe(null)),
    [],
  )
  useEffect(() => { refresh() }, [refresh])

  return (
    <Routes>
      <Route path="/" element={<Public me={me}><PriceCheck /></Public>} />
      <Route path="/login" element={me ? <Navigate to="/app" replace /> : <Public me={me}><Login /></Public>} />
      <Route path="/signup" element={me ? <Navigate to="/app" replace /> : <Public me={me}><Signup onDone={refresh} /></Public>} />
      <Route path="/app/*" element={
        me === undefined ? <p className="page">Loading…</p>
          : me === null ? <Navigate to="/login" replace />
          : <StoreApp me={me} onSignOut={refresh} />
      } />
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  )
}

function Public({ me, children }: { me: Me | null | undefined; children: React.ReactNode }) {
  return (
    <>
      <header className="topbar">
        <Link to="/" className="brand">OCC Pricer</Link>
        <nav>
          {me ? <Link to="/app">Open {me.store}</Link> : <><a href="/api/auth/login">Store sign in</a><Link to="/signup" className="button small">Start free trial</Link></>}
        </nav>
      </header>
      <main className="page">{children}</main>
      <footer className="footer">
        Card prices from <a href="https://scryfall.com" target="_blank" rel="noreferrer">Scryfall</a>. Price lookup is free, with no account needed.
        Card names and data are property of Wizards of the Coast.
      </footer>
    </>
  )
}

/** Clears our session, then ends the Auth0 session too so the next sign-in asks again. */
export function useSignOut(onSignOut: () => Promise<void>) {
  const navigate = useNavigate()
  return async () => {
    try {
      const { logoutUrl } = await api<{ logoutUrl: string }>('/api/auth/logout', { method: 'POST', body: {} })
      window.location.assign(logoutUrl)
      return
    } catch (e) { if (!(e instanceof ApiError)) throw e }
    await onSignOut()
    navigate('/')
  }
}
