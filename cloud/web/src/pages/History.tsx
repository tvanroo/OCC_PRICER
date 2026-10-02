import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, money, phoneText } from '../api'

interface TradeSummary {
  id: string; number: number; created_at: string; payment: string; credit_total: number; check_total: number
  market_total: number; customer_phone: string | null; customer_name: string | null; created_by: string; cards: number
}

const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString()
const when = (iso: string) => {
  const d = new Date(iso)
  return sameDay(d, new Date()) ? d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : d.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })
}

export function History() {
  const [phone, setPhone] = useState('')
  const [trades, setTrades] = useState<TradeSummary[]>([])
  const [error, setError] = useState('')
  const navigate = useNavigate()
  useEffect(() => {
    const timer = setTimeout(() => {
      const digits = phone.replace(/\D/g, '')
      if (phone && digits.length < 8) return
      api<TradeSummary[]>(`/api/app/trades${phone ? `?phone=${encodeURIComponent(phone)}` : ''}`)
        .then(t => { setTrades(t); setError('') }).catch(e => setError(e.message))
    }, 300)
    return () => clearTimeout(timer)
  }, [phone])

  // The list holds the latest 50 trades, so today's totals are exact unless a store does more than 50 in a day.
  const today = trades.filter(t => sameDay(new Date(t.created_at), new Date()))
  const sum = (key: 'credit_total' | 'check_total') => today.reduce((n, t) => n + Number(t[key] ?? 0), 0)

  return (
    <section>
      <h1>Trade history</h1>
      <div className="toolbar">
        <input type="tel" inputMode="tel" placeholder="Filter by customer phone" aria-label="Filter by customer phone" value={phone} onChange={e => setPhone(e.target.value)} />
      </div>
      {!phone && (
        <div className="stats">
          <div className="stat"><span>Trades today</span><strong>{today.length === 50 ? '50+' : today.length}</strong></div>
          <div className="stat copper"><span>Credit paid out today</span><strong>{money(sum('credit_total'))}</strong></div>
          <div className="stat"><span>Checks written today</span><strong>{money(sum('check_total'))}</strong></div>
        </div>
      )}
      {error && <p className="error">{error}</p>}
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>#</th><th>When</th><th>Customer</th><th className="r">Cards</th><th className="r">Credit</th><th className="r">Check</th><th>By</th><th><span className="sr-only">POS export</span></th></tr></thead>
          <tbody>
            {trades.map(t => (
              <tr key={t.id} className="clickable" onClick={() => navigate(`/app/history/${t.id}`)}>
                <td><Link to={`/app/history/${t.id}`} onClick={e => e.stopPropagation()}><strong>{t.number}</strong></Link></td>
                <td className="num">{when(t.created_at)}</td>
                <td>{t.customer_name || (t.customer_phone ? 'Customer' : 'Walk-in')}<div className="muted small num">{phoneText(t.customer_phone)}</div></td>
                <td className="r">{t.cards}</td>
                <td className="r credit">{Number(t.credit_total) > 0 ? money(t.credit_total) : '—'}</td>
                <td className="r">{Number(t.check_total) > 0 ? money(t.check_total) : '—'}</td>
                <td>{t.created_by}</td>
                <td><a href={`/api/app/trades/${t.id}/pos.csv`} onClick={e => e.stopPropagation()}>CSV</a></td>
              </tr>
            ))}
          </tbody>
        </table>
        {trades.length === 0 && <p className="empty">No trades yet.</p>}
      </div>
    </section>
  )
}

interface TradeDetailData extends TradeSummary {
  check_number: string
  lines: { line_no: number; name: string; set_code: string; collector_number: string; finish: string; condition: string;
    quantity: number; valuation_unit: number; credit_alloc: number; check_alloc: number }[]
}

export function TradeDetail() {
  const { id } = useParams()
  const [trade, setTrade] = useState<TradeDetailData | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { api<TradeDetailData>(`/api/app/trades/${id}`).then(setTrade).catch(e => setError(e.message)) }, [id])
  if (error) return <p className="error">{error}</p>
  if (!trade) return <p className="muted">Loading…</p>
  return (
    <section style={{ maxWidth: 1040, margin: '0 auto' }}>
      <Link to="/app/history" className="back">← History</Link>
      <h1>Trade #{trade.number}</h1>
      <p className="muted" style={{ marginTop: 0 }}>{new Date(trade.created_at).toLocaleString()} · by {trade.created_by}
        {trade.customer_phone && <> · {trade.customer_name || 'Customer'} <span className="num">{phoneText(trade.customer_phone)}</span></>}
        {trade.check_number && <> · check #{trade.check_number}</>}</p>
      <div className="table-wrap">
        <table className="grid">
          <thead><tr><th>Card</th><th>Finish</th><th>Cond.</th><th className="r">Qty</th><th className="r">Value each</th><th className="r">Credit</th><th className="r">Check</th></tr></thead>
          <tbody>
            {trade.lines.map(l => (
              <tr key={l.line_no}>
                <td><strong>{l.name}</strong><div className="muted small">{l.set_code.toUpperCase()} #{l.collector_number}</div></td>
                <td style={{ textTransform: 'capitalize' }}>{l.finish}</td><td>{l.condition}</td><td className="r">{l.quantity}</td>
                <td className="r">{money(l.valuation_unit)}</td><td className="r credit">{money(l.credit_alloc)}</td><td className="r">{money(l.check_alloc)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="totals">
        <div className="sum"><span>Market value</span><strong>{money(trade.market_total)}</strong></div>
        <div className="sum"><span>Store credit paid</span><strong style={{ color: 'var(--copper-text)' }}>{money(trade.credit_total)}</strong></div>
        <div className="sum"><span>Check paid</span><strong>{money(trade.check_total)}</strong></div>
      </div>
      <div className="actions" style={{ justifyContent: 'flex-end' }}>
        <a className="button" href={`/api/app/trades/${trade.id}/pos.csv`}>Download POS CSV</a>
        <button className="secondary" onClick={() => window.print()}>Print receipt</button>
      </div>
    </section>
  )
}
