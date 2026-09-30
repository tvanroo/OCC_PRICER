import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, money } from '../api'

interface TradeSummary {
  id: string; number: number; created_at: string; payment: string; credit_total: number; check_total: number
  market_total: number; customer_phone: string | null; customer_name: string | null; created_by: string; cards: number
}

export function History() {
  const [phone, setPhone] = useState('')
  const [trades, setTrades] = useState<TradeSummary[]>([])
  const [error, setError] = useState('')
  useEffect(() => {
    const timer = setTimeout(() => {
      const digits = phone.replace(/\D/g, '')
      if (phone && digits.length < 8) return
      api<TradeSummary[]>(`/api/app/trades${phone ? `?phone=${encodeURIComponent(phone)}` : ''}`)
        .then(t => { setTrades(t); setError('') }).catch(e => setError(e.message))
    }, 300)
    return () => clearTimeout(timer)
  }, [phone])
  return (
    <section>
      <h1>Trade history</h1>
      <input className="search" placeholder="Filter by customer phone" value={phone} onChange={e => setPhone(e.target.value)} />
      {error && <p className="error">{error}</p>}
      <table className="grid">
        <thead><tr><th>#</th><th>Date</th><th>Customer</th><th>Cards</th><th>Credit</th><th>Check</th><th>By</th><th /></tr></thead>
        <tbody>
          {trades.map(t => (
            <tr key={t.id}>
              <td><Link to={`/app/history/${t.id}`}>{t.number}</Link></td>
              <td>{new Date(t.created_at).toLocaleString()}</td>
              <td>{t.customer_name || '—'}<div className="muted small">{t.customer_phone}</div></td>
              <td>{t.cards}</td>
              <td>{money(t.credit_total)}</td>
              <td>{money(t.check_total)}</td>
              <td>{t.created_by}</td>
              <td><a href={`/api/app/trades/${t.id}/pos.csv`}>CSV</a></td>
            </tr>
          ))}
        </tbody>
      </table>
      {trades.length === 0 && <p className="muted">No trades yet.</p>}
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
  if (!trade) return <p>Loading…</p>
  return (
    <section>
      <p><Link to="/app/history">← History</Link></p>
      <h1>Trade #{trade.number}</h1>
      <p className="muted">{new Date(trade.created_at).toLocaleString()} · by {trade.created_by}
        {trade.customer_phone && <> · {trade.customer_name || 'Customer'} {trade.customer_phone}</>}
        {trade.check_number && <> · check #{trade.check_number}</>}</p>
      <table className="grid">
        <thead><tr><th>Card</th><th>Finish</th><th>Cond.</th><th>Qty</th><th>Value each</th><th>Credit</th><th>Check</th></tr></thead>
        <tbody>
          {trade.lines.map(l => (
            <tr key={l.line_no}>
              <td>{l.name}<div className="muted small">{l.set_code} #{l.collector_number}</div></td>
              <td>{l.finish}</td><td>{l.condition}</td><td>{l.quantity}</td>
              <td>{money(l.valuation_unit)}</td><td>{money(l.credit_alloc)}</td><td>{money(l.check_alloc)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="totals">
        <div><span>Market value</span><strong>{money(trade.market_total)}</strong></div>
        <div><span>Store credit paid</span><strong>{money(trade.credit_total)}</strong></div>
        <div><span>Check paid</span><strong>{money(trade.check_total)}</strong></div>
      </div>
      <p><a className="button" href={`/api/app/trades/${trade.id}/pos.csv`}>Download POS CSV</a>{' '}
        <button className="secondary" onClick={() => window.print()}>Print receipt</button></p>
    </section>
  )
}
