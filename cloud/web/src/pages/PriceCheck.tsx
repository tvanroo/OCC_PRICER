import { useEffect, useState } from 'react'
import { api, FINISHES, money, type Card } from '../api'

interface SearchResult { cards: Card[]; pricesUpdatedAt: string | null }

/** The free price check: search a card, see its market price. Nothing else, by design. */
export default function PriceCheck() {
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<SearchResult | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (query.trim().length < 2) { setResult(null); setError(''); return }
    const timer = setTimeout(() => {
      api<SearchResult>(`/api/public/cards?q=${encodeURIComponent(query.trim())}`)
        .then(r => { setResult(r); setError('') })
        .catch(e => setError(e.message))
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  return (
    <section>
      <h1>Magic card price check</h1>
      <p className="muted">Search any card to see today's market price.</p>
      <input className="search" autoFocus placeholder="Card name, set, number or card text, e.g. Lightning Bolt or DMU 391" value={query}
             onChange={e => setQuery(e.target.value)} aria-label="Card name" />
      {error && <p className="error">{error}</p>}
      {result && (
        <>
          {result.cards.length === 0 && <p className="muted">No cards found.</p>}
          <div className="cards">
            {result.cards.map(card => (
              <article key={card.id} className="card">
                {card.image ? <img src={card.image} alt="" loading="lazy" /> : <div className="noimg" />}
                <div>
                  <h3>{card.name}</h3>
                  <p className="muted">{card.setName} ({card.set}) #{card.number} · {card.rarity}</p>
                  <dl className="prices">
                    {FINISHES.filter(f => f.price(card) != null).map(f => (
                      <div key={f.key}><dt>{f.label}</dt><dd>{money(f.price(card))}</dd></div>
                    ))}
                  </dl>
                </div>
              </article>
            ))}
          </div>
          {result.pricesUpdatedAt && <p className="muted small">Prices updated {new Date(result.pricesUpdatedAt).toLocaleString()}.</p>}
        </>
      )}
    </section>
  )
}
