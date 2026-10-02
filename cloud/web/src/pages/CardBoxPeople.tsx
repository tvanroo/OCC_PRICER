import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from '../api'

/*
 * People, stores and roles live on CardBox (cardbox.club). These screens read and change them through
 * /api/cardbox/*, which Trading's server forwards to CardBox as the signed-in person; CardBox decides what each
 * person may see and change, and its error messages are shown as they are.
 */

export type CardBoxRole = 'user' | 'platform_owner' | 'store_manager' | 'store_employee'
export interface CardBoxStore { id: string; name: string; slug?: string }
/* Shapes as cardbox.club's backend/cardbox/account_roles.py returns them. */
interface Grant { id: string; role: CardBoxRole; role_label?: string; store_id: string | null; store_name: string | null; removable: boolean; locked_reason?: string }
interface Person { id: string; email: string; display_name: string; is_self?: boolean; roles: Grant[] }
interface AccountRoles { roles: CardBoxRole[]; stores: (CardBoxStore & { role: CardBoxRole })[] }
interface CatalogRole { role: CardBoxRole; label: string; description: string }
interface RoleEvent { id: string; action: string; actor: string; target: string | null; role_label: string | null
  store_name: string | null; detail: string; created_at: string }

const LABELS: Record<CardBoxRole, string> = {
  user: 'User', platform_owner: 'Platform owner', store_manager: 'Store manager', store_employee: 'Store employee',
}

const EVENT_VERBS: Record<string, string> = {
  role_granted: 'gave', role_removed: 'removed', store_created: 'created store', store_renamed: 'renamed store',
}

/** One line of the shared audit log, e.g. "Toby gave Store employee to Sam at Pat's Cards". */
function eventText(ev: RoleEvent) {
  const verb = EVENT_VERBS[ev.action] ?? ev.action.replace(/_/g, ' ')
  if (ev.action.startsWith('store_')) return `${ev.actor} ${verb} ${ev.store_name ?? ''}`
  const what = [ev.role_label, ev.action === 'role_removed' ? 'from' : 'to', ev.target].filter(Boolean).join(' ')
  return `${ev.actor} ${verb} ${what}${ev.store_name ? ` at ${ev.store_name}` : ''}`
}

/** What the signed-in person can do, from CardBox's live answer rather than what was true at sign-in. */
function useCardBoxAccount() {
  const [roles, setRoles] = useState<AccountRoles | null>(null)
  const [error, setError] = useState('')
  useEffect(() => { api<AccountRoles>('/api/cardbox/account/roles').then(setRoles).catch(e => setError(e.message)) }, [])
  const platformOwner = roles?.roles.includes('platform_owner') ?? false
  const managed = useMemo(() => new Set((roles?.stores ?? []).filter(s => s.role === 'store_manager').map(s => s.id)), [roles])
  return { loaded: roles !== null, platformOwner, managed, error }
}

/** Everyone the signed-in person may see on CardBox, with the roles they may give and take away. */
export function CardBoxPeople({ me, onChange }: { me: { email: string }; onChange: () => Promise<void> }) {
  const account = useCardBoxAccount()
  const [people, setPeople] = useState<Person[]>([])
  const [stores, setStores] = useState<CardBoxStore[]>([])
  const [catalog, setCatalog] = useState<CatalogRole[]>([])
  const [events, setEvents] = useState<RoleEvent[]>([])
  const [form, setForm] = useState({ email: '', role: 'store_employee' as CardBoxRole, store_id: '' })
  const [filter, setFilter] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')

  const load = useCallback(() => Promise.all([
    api<Person[]>('/api/cardbox/people').then(setPeople),
    api<RoleEvent[]>('/api/cardbox/role-events?limit=50').then(setEvents).catch(() => setEvents([])),
  ]), [])
  useEffect(() => {
    Promise.all([
      load(),
      api<CardBoxStore[]>('/api/cardbox/stores').then(setStores),
      api<{ roles: CatalogRole[] }>('/api/cardbox/role-catalog').then(r => setCatalog(r.roles)).catch(() => setCatalog([])),
    ]).catch(e => setError(e.message))
  }, [load])

  // A platform owner gives any role anywhere; a store manager gives store employee at their own stores.
  const grantable: CardBoxRole[] = account.platformOwner ? ['store_employee', 'store_manager', 'platform_owner'] : account.managed.size ? ['store_employee'] : []
  const grantStores = account.platformOwner ? stores : stores.filter(s => account.managed.has(s.id))
  const storeName = (g: Grant) => g.store_name ?? stores.find(s => s.id === g.store_id)?.name ?? ''
  const nameOf = (p: Person) => p.display_name || p.email
  const isSelf = (p: Person) => p.is_self ?? p.email.toLowerCase() === me.email.toLowerCase()

  async function run(request: Promise<unknown>, done: string, self = false) {
    try {
      await request
      await load()
      setMessage(done); setError('')
      // Your own roles decide what the rest of the app shows.
      if (self) await onChange()
      return true
    } catch (e) { setError((e as Error).message); setMessage(''); return false }
  }
  async function grant(e: React.FormEvent) {
    e.preventDefault()
    const body = { email: form.email.trim(), role: form.role, store_id: form.role === 'platform_owner' ? null : form.store_id || grantStores[0]?.id }
    const where = form.role === 'platform_owner' ? '' : ` at ${stores.find(s => s.id === body.store_id)?.name ?? 'the store'}`
    if (await run(api('/api/cardbox/role-grants', { method: 'POST', body }), `${body.email} is now ${LABELS[form.role].toLowerCase()}${where}.`,
      body.email.toLowerCase() === me.email.toLowerCase())) setForm({ ...form, email: '' })
  }
  function revoke(p: Person, g: Grant) {
    const what = `${LABELS[g.role]}${storeName(g) ? ` at ${storeName(g)}` : ''}`
    if (confirm(`Remove ${what} from ${nameOf(p)}?`))
      run(api(`/api/cardbox/role-grants/${encodeURIComponent(g.id)}`, { method: 'DELETE' }), `${nameOf(p)} is no longer ${what.toLowerCase()}.`, isSelf(p))
  }

  const term = filter.trim().toLowerCase()
  const shown = people.filter(p => !term || p.email.toLowerCase().includes(term) || nameOf(p).toLowerCase().includes(term)
    || p.roles.some(g => storeName(g).toLowerCase().includes(term)))

  return (
    <>
      <p className="lede">People and roles are shared with cardbox.club: a change here shows there too, and the other way round.</p>
      {(account.error || error) && <p className="error">{account.error || error}</p>}
      {message && <p className="notice">{message}</p>}
      {people.length > 8 && (
        <div className="toolbar">
          <input placeholder="Find a person, email or store" aria-label="Find a person, email or store" value={filter} onChange={e => setFilter(e.target.value)} />
        </div>
      )}
      <div className="table-wrap"><table className="grid">
        <thead><tr><th>Person</th><th>Roles</th></tr></thead>
        <tbody>{shown.map(p => (
          <tr key={p.id}>
            <td>{nameOf(p)}{isSelf(p) && <span className="muted"> (you)</span>}
              {nameOf(p) !== p.email && <div className="muted small">{p.email}</div>}</td>
            <td>{p.roles.map(g => (
              <span key={g.id} className="chip" title={g.locked_reason}>
                {g.role_label ?? LABELS[g.role] ?? g.role}{storeName(g) && <> · {storeName(g)}</>}
                {g.removable && <button className="link" aria-label={`Remove ${g.role_label ?? LABELS[g.role]} from ${nameOf(p)}`} onClick={() => revoke(p, g)}>×</button>}
              </span>
            ))}{p.roles.length === 0 && <span className="muted">User</span>}</td>
          </tr>
        ))}</tbody>
      </table></div>
      {people.length > 0 && shown.length === 0 && <p className="empty">No one matches.</p>}

      {grantable.length > 0 && (
        <form className="panel narrow" onSubmit={grant} style={{ marginTop: 20 }}>
          <h2>Give someone a role</h2>
          <label>Email<input type="email" required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
          <label>Role
            <select value={form.role} onChange={e => setForm({ ...form, role: e.target.value as CardBoxRole })}>
              {grantable.map(r => <option key={r} value={r}>{LABELS[r]}</option>)}
            </select>
          </label>
          {form.role !== 'platform_owner' && (
            <label>Store
              <select required value={form.store_id || grantStores[0]?.id || ''} onChange={e => setForm({ ...form, store_id: e.target.value })}>
                {grantStores.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
            </label>
          )}
          <p className="muted">They sign in with their own CardBox login using this email.</p>
          <button type="submit">Give role</button>
        </form>
      )}

      {catalog.length > 0 && (
        <details style={{ marginTop: 20 }}>
          <summary>What each role can do</summary>
          <dl>{catalog.map(c => <div key={c.role}><dt><strong>{c.label ?? LABELS[c.role] ?? c.role}</strong></dt><dd className="muted">{c.description}</dd></div>)}</dl>
        </details>
      )}
      {events.length > 0 && (
        <details style={{ marginTop: 12 }}>
          <summary>Recent changes</summary>
          <ul className="plain">{events.slice(0, 30).map((ev, i) => (
            <li key={ev.id ?? i}><span className="muted small">{new Date(ev.created_at).toLocaleString()}</span> {eventText(ev)}
              {ev.detail && <span className="muted small"> · {ev.detail}</span>}</li>
          ))}</ul>
        </details>
      )}
    </>
  )
}

interface TradingStore { id: string; name: string; planStatus: string; trialEndsAt: string; entitled: boolean; cardboxStoreId: string | null
  people: number; trades: number; cards: number }

const PLANS = ['trial', 'active', 'past_due', 'canceled'] as const

/**
 * The platform owner's store list with the link on: CardBox's stores (created and renamed there), each with the
 * Trading plan and trial that go with it, and any Trading store not yet tied to a CardBox store.
 */
export function CardBoxStores() {
  const [stores, setStores] = useState<CardBoxStore[]>([])
  const [trading, setTrading] = useState<TradingStore[]>([])
  const [newName, setNewName] = useState('')
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const load = useCallback(() => Promise.all([
    api<CardBoxStore[]>('/api/cardbox/stores').then(setStores),
    api<TradingStore[]>('/api/admin/stores').then(setTrading),
  ]), [])
  useEffect(() => { load().catch(e => setError(e.message)) }, [load])

  async function run(request: Promise<unknown>, done: string) {
    try { await request; await load(); setMessage(done); setError('') } catch (e) { setError((e as Error).message); setMessage('') }
  }
  const byCardBox = new Map(trading.filter(t => t.cardboxStoreId).map(t => [t.cardboxStoreId as string, t]))
  const unlinked = trading.filter(t => !t.cardboxStoreId)
  const linkable = stores.filter(s => !byCardBox.has(s.id) || (byCardBox.get(s.id)!.trades === 0 && byCardBox.get(s.id)!.cards === 0))

  return (
    <>
      {message && <p className="notice">{message}</p>}
      {error && <p className="error">{error}</p>}
      <div className="table-wrap"><table className="grid">
        <thead><tr><th>Store</th><th>Plan</th><th>Trial ends</th><th className="r">Trades</th><th className="r">Cards</th></tr></thead>
        <tbody>{stores.map(s => <CardBoxStoreRow key={`${s.id}:${s.name}`} store={s} trading={byCardBox.get(s.id)} run={run} />)}</tbody>
      </table></div>
      <form className="inline-form" onSubmit={e => { e.preventDefault(); run(api('/api/cardbox/stores', { method: 'POST', body: { name: newName.trim() } }), `${newName.trim()} created.`).then(() => setNewName('')) }}>
        <label>New store<input required maxLength={120} value={newName} onChange={e => setNewName(e.target.value)} /></label>
        <button type="submit" className="small">Create on CardBox</button>
      </form>
      {unlinked.length > 0 && (
        <>
          <h2>Trading stores not yet tied to CardBox</h2>
          <p className="muted">Most link themselves by name the first time their manager signs in. Tie the rest here so their trades and inventory carry over.</p>
          <table className="grid"><tbody>{unlinked.map(t => (
            <tr key={t.id}><td>{t.name}</td><td className="muted small">{t.people} people · {t.trades} trades</td>
              <td><select aria-label={`CardBox store for ${t.name}`} value="" onChange={e => e.target.value &&
                run(api(`/api/admin/stores/${t.id}/cardbox`, { method: 'PUT', body: { cardboxStoreId: e.target.value } }), `${t.name} now belongs to ${stores.find(s => s.id === e.target.value)?.name}.`)}>
                <option value="">Tie to a CardBox store…</option>
                {linkable.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select></td></tr>
          ))}</tbody></table>
        </>
      )}
    </>
  )
}

function CardBoxStoreRow({ store: s, trading: t, run }: { store: CardBoxStore; trading?: TradingStore; run: (r: Promise<unknown>, done: string) => Promise<void> }) {
  const [name, setName] = useState(s.name)
  return (
    <tr>
      <td>
        <form className="inline-form" onSubmit={e => { e.preventDefault(); run(api(`/api/cardbox/stores/${encodeURIComponent(s.id)}`, { method: 'PATCH', body: { name: name.trim() } }), `Renamed to ${name.trim()}.`) }}>
          <input aria-label={`Name of ${s.name}`} required maxLength={120} value={name} onChange={e => setName(e.target.value)} />
          {name.trim() !== s.name && <button type="submit" className="small secondary">Rename</button>}
        </form>
        {!t && <div className="muted small">No one has used it on Trading yet</div>}
        {t && !t.entitled && <div className="error small">Locked</div>}
      </td>
      <td>{t && <select aria-label={`Plan for ${s.name}`} value={t.planStatus}
        onChange={e => run(api(`/api/admin/stores/${t.id}`, { method: 'PUT', body: { planStatus: e.target.value } }), `${s.name} is now ${e.target.value.replace('_', ' ')}.`)}>
        {PLANS.map(p => <option key={p} value={p}>{p.replace('_', ' ')}</option>)}
      </select>}</td>
      <td>{t && <input type="date" aria-label={`Trial end for ${s.name}`} value={t.trialEndsAt.slice(0, 10)}
        onChange={e => e.target.value && run(api(`/api/admin/stores/${t.id}`, { method: 'PUT', body: { trialEndsAt: e.target.value } }), `${s.name}'s trial now ends ${e.target.value}.`)} />}</td>
      <td className="r">{t?.trades ?? '—'}</td><td className="r">{t?.cards ?? '—'}</td>
    </tr>
  )
}
