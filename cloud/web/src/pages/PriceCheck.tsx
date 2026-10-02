import { useEffect, useState } from 'react'
import { api, money, type Card } from '../api'
import SearchIcon from '../SearchIcon'
import CardLightbox from '../CardLightbox'

interface SearchResult { cards: Card[]; pricesUpdatedAt: string | null }

/** The free price check: search a card, see its market price. Nothing else, by design. */
export default function PriceCheck() {
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<SearchResult | null>(null)
  const [error, setError] = useState('')
  const [enlarged, setEnlarged] = useState<Card | null>(null)

  useEffect(() => {
    if (query.trim().length < 2) { setResult(null); setError(''); return }
    const timer = setTimeout(() => {
      api<SearchResult>(`/api/public/cards?q=${encodeURIComponent(query.trim())}&v=${__BUILD_ID__}`)
        .then(r => { setResult(r); setError('') })
        .catch(e => setError(e.message))
    }, 300)
    return () => clearTimeout(timer)
  }, [query])

  return (
    <section>
      <h1>Magic card price check</h1>
      <p className="lede">Free, no account. Search by name, set code or collector number.</p>
      <div className="search">
        <SearchIcon />
        <input autoFocus placeholder="e.g. Lightning Bolt or DMU 391" value={query}
               onChange={e => setQuery(e.target.value)} aria-label="Card name, set or number" />
        {result && <span className="aside">{result.cards.length} {result.cards.length === 1 ? 'match' : 'matches'}</span>}
      </div>
      {error && <p className="error">{error}</p>}
      {result && (
        <>
          {result.cards.length === 0 ? <p className="muted">No cards found.</p> : (
            <div className="rows">
              <div className="rows-head"><span style={{ flex: 1 }}>Card</span><span className="price-col">Normal</span><span className="price-col">Foil</span></div>
              {result.cards.map(card => (
                <article key={card.id} className="result">
                  {card.image
                    ? <button type="button" className="thumb" onClick={() => setEnlarged(card)} aria-label={`Enlarge ${card.name}`}>
                        <img src={card.image} alt="" loading="lazy" />
                      </button>
                    : <div className="noimg" />}
                  <div className="info">
                    <h3>{card.name}</h3>
                    <div className="meta"><span>{card.setName}</span><b>{card.set.toUpperCase()} #{card.number}</b><span className={`rarity ${card.rarity}`}>{card.rarity}</span></div>
                  </div>
                  <div className="price-col"><small>Normal</small>{money(card.usd)}</div>
                  <div className="price-col foil"><small>Foil</small>{money(card.usdFoil)}
                    {card.usdEtched != null && <span className="etched">Etched {money(card.usdEtched)}</span>}</div>
                </article>
              ))}
            </div>
          )}
          {result.pricesUpdatedAt && <p className="muted small">Prices updated {new Date(result.pricesUpdatedAt).toLocaleString()}.</p>}
        </>
      )}
      {enlarged?.image && (
        <CardLightbox image={enlarged.image} onClose={() => setEnlarged(null)}
                      caption={`${enlarged.name} · ${enlarged.setName} (${enlarged.set}) #${enlarged.number}`} />
      )}
    </section>
  )
}
