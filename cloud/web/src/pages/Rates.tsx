import { useEffect, useState } from 'react'
import { api, type Me } from '../api'

interface Rule { thresholdMin: string; creditRate: string; checkRate: string }
interface ServerRule { thresholdMin: number; creditRate: number; checkRate: number }

const pct = (rate: number) => String(Math.round(rate * 10000) / 100)

export default function Rates({ me }: { me: Me }) {
  const [rules, setRules] = useState<Rule[]>([])
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const load = (data: { rules: ServerRule[] }) => setRules(data.rules.map(r => ({
    thresholdMin: String(r.thresholdMin), creditRate: pct(r.creditRate), checkRate: pct(r.checkRate) })))
  useEffect(() => { api<{ rules: ServerRule[] }>('/api/app/rates').then(load).catch(e => setError(e.message)) }, [])
  const owner = me.role === 'owner'
  const update = (i: number, change: Partial<Rule>) => setRules(rules.map((r, j) => j === i ? { ...r, ...change } : r))

  async function save() {
    try {
      load(await api<{ rules: ServerRule[] }>('/api/app/rates', { method: 'PUT', body: { rules: rules.map(r => ({
        thresholdMin: Number(r.thresholdMin), creditRate: Number(r.creditRate) / 100, checkRate: Number(r.checkRate) / 100 })) } }))
      setMessage('Rates saved.'); setError('')
    } catch (e) { setError((e as Error).message); setMessage('') }
  }

  return (
    <section>
      <h1>Buy rates</h1>
      <p className="muted">A card's offer uses the row with the highest threshold below its value. Keep a $0 row for everything else.</p>
      <table className="grid">
        <thead><tr><th>Card value over</th><th>Store credit %</th><th>Check %</th><th /></tr></thead>
        <tbody>
          {rules.map((r, i) => (
            <tr key={i}>
              <td>$<input type="number" min={0} step="0.01" disabled={!owner} value={r.thresholdMin} onChange={e => update(i, { thresholdMin: e.target.value })} /></td>
              <td><input type="number" min={1} max={100} disabled={!owner} value={r.creditRate} onChange={e => update(i, { creditRate: e.target.value })} />%</td>
              <td><input type="number" min={1} max={100} disabled={!owner} value={r.checkRate} onChange={e => update(i, { checkRate: e.target.value })} />%</td>
              <td>{owner && rules.length > 1 && <button className="link" onClick={() => setRules(rules.filter((_, j) => j !== i))}>Remove</button>}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {owner ? (
        <p><button className="secondary" onClick={() => setRules([...rules, { thresholdMin: '', creditRate: '50', checkRate: '40' }])}>Add tier</button>{' '}
          <button onClick={save}>Save rates</button></p>
      ) : <p className="muted">Only the store owner can change rates.</p>}
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
