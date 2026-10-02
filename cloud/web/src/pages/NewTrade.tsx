import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, CONDITIONS, FINISHES, money, type Card } from '../api'

interface Line { key: number; card: Card; finish: string; condition: string; quantity: number }
interface PricedLine { valuationUnit: number; creditUnit: number; checkUnit: number; creditRate: number; checkRate: number }
interface Quote {
  lines: PricedLine[]
  marketTotal: number
  creditOffer: number
  checkOffer: number
  settlement: { payment: string; credit: number; check: number }
}
interface Saved { id: string; number: number }

let nextKey = 1

export default function NewTrade() {
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<Card[]>([])
  const [noMatch, setNoMatch] = useState(false)
  const [lines, setLines] = useState<Line[]>([])
  const [payment, setPayment] = useState<'credit' | 'check' | 'partial'>('credit')
  const [splitCredit, setSplitCredit] = useState('')
  const [phone, setPhone] = useState('')
  const [customerName, setCustomerName] = useState('')
  const [checkNumber, setCheckNumber] = useState('')
  const [quote, setQuote] = useState<Quote | null>(null)
  const [error, setError] = useState('')
  const [saved, setSaved] = useState<Saved | null>(null)

  useEffect(() => {
    if (query.trim().length < 2) { setResults([]); setNoMatch(false); return }
    const timer = setTimeout(() => {
      api<Card[]>(`/api/app/cards?q=${encodeURIComponent(query.trim())}`).then(r => { setResults(r); setNoMatch(r.length === 0) }).catch(e => setError(e.message))
    }, 250)
    return () => clearTimeout(timer)
  }, [query])

  const request = useMemo(() => ({
    lines: lines.map(l => ({ cardId: l.card.id, finish: l.finish, condition: l.condition, quantity: l.quantity })),
    payment,
    credit: payment === 'partial' && splitCredit !== '' ? Number(splitCredit) : undefined,
  }), [lines, payment, splitCredit])

  useEffect(() => {
    if (lines.length === 0) { setQuote(null); return }
    if (payment === 'partial' && splitCredit === '') {
      api<Quote>('/api/app/trades/quote', { method: 'POST', body: { ...request, payment: 'credit' } })
        .then(q => { setQuote(q); setError('') }).catch(e => setError(e.message))
      return
    }
    const timer = setTimeout(() => {
      api<Quote>('/api/app/trades/quote', { method: 'POST', body: request })
        .then(q => { setQuote(q); setError('') }).catch(e => setError(e.message))
    }, 200)
    return () => clearTimeout(timer)
  }, [request, lines.length, payment, splitCredit])

  function add(card: Card) {
    const finish = FINISHES.find(f => f.price(card) != null)?.key ?? 'normal'
    setLines([...lines, { key: nextKey++, card, finish, condition: 'NM', quantity: 1 }])
    setQuery('')
    setResults([])
    setNoMatch(false)
    setSaved(null)
  }
  const update = (key: number, change: Partial<Line>) => setLines(lines.map(l => l.key === key ? { ...l, ...change } : l))

  async function save() {
    try {
      const result = await api<Saved>('/api/app/trades', {
        method: 'POST',
        body: { ...request, customerPhone: phone || undefined, customerName, checkNumber },
      })
      setSaved(result)
      setLines([]); setPhone(''); setCustomerName(''); setCheckNumber(''); setSplitCredit(''); setPayment('credit')
    } catch (e) { setError((e as Error).message) }
  }

  return (
    <section>
      <h1>New trade</h1>
      {saved && (
        <div className="notice">
          Trade #{saved.number} saved. <a href={`/api/app/trades/${saved.id}/pos.csv`}>Download POS CSV</a> ·{' '}
          <Link to={`/app/history/${saved.id}`}>View</Link>
        </div>
      )}
      <div className="picker">
        <input className="search" placeholder="Add a card by name, set, number or card text, e.g. DMU 391" value={query} onChange={e => setQuery(e.target.value)} />
        {noMatch && query.trim().length >= 2 && <p className="muted">No cards found.</p>}
        {results.length > 0 && (
          <ul className="results">
            {results.map(card => (
              <li key={card.id}><button onClick={() => add(card)}>
                <strong>{card.name}</strong> <span className="muted">{card.set} #{card.number}</span>
                <span className="right">{money(card.usd ?? card.usdFoil ?? card.usdEtched)}</span>
              </button></li>
            ))}
          </ul>
        )}
      </div>

      {lines.length > 0 && (
        <table className="grid">
          <thead><tr><th>Card</th><th>Finish</th><th>Condition</th><th>Qty</th><th>Value</th><th>Credit</th><th>Check</th><th /></tr></thead>
          <tbody>
            {lines.map((line, i) => {
              const priced = quote?.lines[i]
              return (
                <tr key={line.key}>
                  <td>{line.card.name}<div className="muted small">{line.card.set} #{line.card.number}</div></td>
                  <td><select value={line.finish} onChange={e => update(line.key, { finish: e.target.value })}>
                    {FINISHES.filter(f => f.price(line.card) != null).map(f => <option key={f.key} value={f.key}>{f.label}</option>)}
                  </select></td>
                  <td><select value={line.condition} onChange={e => update(line.key, { condition: e.target.value })}>
                    {CONDITIONS.map(c => <option key={c}>{c}</option>)}
                  </select></td>
                  <td><input type="number" min={1} max={999} className="qty" value={line.quantity}
                             onChange={e => update(line.key, { quantity: Math.max(1, Number(e.target.value) || 1) })} /></td>
                  <td>{money(priced?.valuationUnit)}</td>
                  <td>{money(priced?.creditUnit)}</td>
                  <td>{money(priced?.checkUnit)}</td>
                  <td><button className="link" onClick={() => setLines(lines.filter(l => l.key !== line.key))} aria-label="Remove">✕</button></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      )}

      {quote && (
        <div className="checkout">
          <div className="totals">
            <div><span>Market value</span><strong>{money(quote.marketTotal)}</strong></div>
            <div><span>Store credit offer</span><strong>{money(quote.creditOffer)}</strong></div>
            <div><span>Check offer</span><strong>{money(quote.checkOffer)}</strong></div>
          </div>
          <fieldset>
            <legend>Payout</legend>
            {(['credit', 'check', 'partial'] as const).map(p => (
              <label key={p} className="inline"><input type="radio" checked={payment === p} onChange={() => setPayment(p)} />
                {p === 'credit' ? 'Store credit' : p === 'check' ? 'Check' : 'Split'}</label>
            ))}
            {payment === 'partial' && (
              <label>Store credit amount<input type="number" min={0} step="0.01" value={splitCredit} onChange={e => setSplitCredit(e.target.value)} /></label>
            )}
            <p>Pay out: <strong>{money(quote.settlement.credit)}</strong> credit
              {Number(quote.settlement.check) > 0 && <> + <strong>{money(quote.settlement.check)}</strong> check</>}</p>
          </fieldset>
          <fieldset>
            <legend>Customer</legend>
            <label>Phone number<input type="tel" autoComplete="off" value={phone} onChange={e => setPhone(e.target.value)} placeholder="(555) 010-2030" /></label>
            <label>Name<input value={customerName} onChange={e => setCustomerName(e.target.value)} /></label>
            {payment !== 'credit' && <label>Check number<input value={checkNumber} onChange={e => setCheckNumber(e.target.value)} /></label>}
          </fieldset>
          <button onClick={save} disabled={payment === 'partial' && splitCredit === ''}>Save trade</button>
        </div>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
