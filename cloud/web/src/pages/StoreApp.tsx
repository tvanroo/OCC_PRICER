import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import { type Me } from '../api'
import { useSignOut } from '../App'
import NewTrade from './NewTrade'
import { History, TradeDetail } from './History'
import Rates from './Rates'
import Staff from './Staff'

export default function StoreApp({ me, onSignOut }: { me: Me; onSignOut: () => Promise<void> }) {
  const signOut = useSignOut(onSignOut)
  const trialDays = Math.max(0, Math.ceil((new Date(me.trialEndsAt).getTime() - Date.now()) / 86_400_000))
  return (
    <>
      <header className="topbar">
        <span className="brand">{me.store}</span>
        <nav>
          <NavLink to="/app/trade">New trade</NavLink>
          <NavLink to="/app/history">History</NavLink>
          <NavLink to="/app/rates">Buy rates</NavLink>
          <NavLink to="/app/staff">Staff</NavLink>
          <NavLink to="/">Price check</NavLink>
          <button className="link" onClick={signOut}>Sign out {me.name}</button>
        </nav>
      </header>
      {me.planStatus === 'trial' && me.entitled && <div className="banner">Free trial: {trialDays} days left.</div>}
      <main className="page">
        {!me.entitled ? (
          <div className="panel"><h1>Your trial has ended</h1><p>Contact us to keep using trade-ins, history and exports. The free price check still works.</p></div>
        ) : (
          <Routes>
            <Route path="trade" element={<NewTrade />} />
            <Route path="history" element={<History />} />
            <Route path="history/:id" element={<TradeDetail />} />
            <Route path="rates" element={<Rates me={me} />} />
            <Route path="staff" element={<Staff me={me} />} />
            <Route path="*" element={<Navigate to="trade" replace />} />
          </Routes>
        )}
      </main>
    </>
  )
}
