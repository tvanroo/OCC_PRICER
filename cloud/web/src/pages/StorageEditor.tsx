import { useEffect, useMemo, useState } from 'react'
import { api, type StoreLocation } from '../api'
import { expandNames, flatTree, type Spot } from '../storage'

type Editing = { mode: 'add'; parentId: string | null } | { mode: 'rename'; spot: Spot }

/**
 * The storage layout inside each location, designed by the store: any number of tiers, each with its own label
 * and options (Store room › Shelf › Box › Section, or whatever fits). Owners edit it; everyone can see it.
 */
export default function StorageEditor({ owner, locations }: { owner: boolean; locations: StoreLocation[] }) {
  const open = locations.filter(l => !l.archived)
  const [locationId, setLocationId] = useState(open[0]?.id ?? '')
  const [spots, setSpots] = useState<Spot[]>([])
  const [editing, setEditing] = useState<Editing | null>(null)
  const [label, setLabel] = useState('')
  const [names, setNames] = useState('')
  const [error, setError] = useState('')
  useEffect(() => { api<Spot[]>('/api/app/storage').then(setSpots).catch(e => setError(e.message)) }, [])
  const tree = useMemo(() => flatTree(spots, locationId), [spots, locationId])
  const preview = expandNames(names)

  /** Labels to suggest: what siblings already use first, then anything used at the same depth, then everywhere. */
  const suggestions = useMemo(() => {
    if (!editing || editing.mode !== 'add') return []
    const depth = editing.parentId === null ? 0 : (tree.find(s => s.id === editing.parentId)?.depth ?? -1) + 1
    const ordered = [
      ...tree.filter(s => s.parentId === editing.parentId), ...tree.filter(s => s.depth === depth), ...spots]
    return [...new Set(ordered.map(s => s.label))]
  }, [editing, tree, spots])

  function start(next: Editing) {
    setEditing(next); setError('')
    if (next.mode === 'rename') { setLabel(next.spot.label); setNames(next.spot.name) }
    else {
      const siblings = tree.filter(s => s.parentId === next.parentId)
      setLabel(siblings.at(-1)?.label ?? ''); setNames('')
    }
  }
  async function save(e: React.FormEvent) {
    e.preventDefault()
    if (!editing) return
    try {
      setSpots(editing.mode === 'add'
        ? await api<Spot[]>('/api/app/storage', { method: 'POST', body: { locationId, parentId: editing.parentId, label, names: preview } })
        : await api<Spot[]>(`/api/app/storage/${editing.spot.id}`, { method: 'PUT', body: { label, name: names } }))
      setEditing(null); setError('')
    } catch (err) { setError((err as Error).message) }
  }
  async function remove(s: Spot) {
    const note = s.cards > 0 ? ` Its ${s.cards} card${s.cards === 1 ? '' : 's'} move up a level.` : ''
    if (!confirm(`Remove ${s.label} ${s.name}?${note}`)) return
    try { setSpots(await api<Spot[]>(`/api/app/storage/${s.id}/remove`, { method: 'POST', body: {} })); setError('') }
    catch (err) { setError((err as Error).message) }
  }

  const form = editing && (
    <form className="spot-form" onSubmit={save}>
      <label>Tier label
        <input required maxLength={40} list="tier-labels" placeholder="e.g. Store room, Shelf, Box, Section" value={label}
          autoFocus onChange={e => setLabel(e.target.value)} />
        <datalist id="tier-labels">{suggestions.map(s => <option key={s} value={s} />)}</datalist>
      </label>
      {editing.mode === 'add' ? (
        <label>Options
          <input required placeholder="e.g. A-F, 1-20, or Front, Back" value={names} onChange={e => setNames(e.target.value)} />
          {preview.length > 0 && <span className="muted small hint">Adds {preview.length}: {preview.slice(0, 12).map(n => `${label || '…'} ${n}`).join(', ')}{preview.length > 12 ? ', …' : ''}</span>}
        </label>
      ) : (
        <label>Name<input required maxLength={60} value={names} onChange={e => setNames(e.target.value)} /></label>
      )}
      <p className="actions">
        <button type="submit" className="small">{editing.mode === 'add' ? 'Add' : 'Save'}</button>
        <button type="button" className="secondary small" onClick={() => setEditing(null)}>Cancel</button>
      </p>
    </form>
  )

  return (
    <div className="panel storage">
      <div className="panel-head">
        <h2>Storage</h2>
        {open.length > 1 && (
          <select aria-label="Location" value={locationId} onChange={e => { setLocationId(e.target.value); setEditing(null) }}>
            {open.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
        )}
      </div>
      <p className="muted small">Lay out where cards live, as many tiers deep as you need. Each tier gets your own label and options, so a store room can hold shelves, a shelf boxes, and a box sections.</p>
      {error && <p className="error">{error}</p>}
      {tree.length === 0 && !editing && <p className="empty">No storage set up here yet. Cards still count toward the location until they're put away.</p>}
      <ul className="spot-tree">
        {tree.map(s => (
          <li key={s.id} style={{ paddingLeft: s.depth * 22 }}>
            <div className="spot-row">
              <span><span className="muted">{s.label}</span> <strong>{s.name}</strong>
                {s.cards > 0 && <span className="muted small"> · {s.cards} card{s.cards === 1 ? '' : 's'}</span>}</span>
              {owner && (
                <span className="row-actions">
                  <button className="link" onClick={() => start({ mode: 'add', parentId: s.id })}>Add inside</button>
                  <button className="link" onClick={() => start({ mode: 'rename', spot: s })}>Rename</button>
                  <button className="link" onClick={() => remove(s)}>Remove</button>
                </span>
              )}
            </div>
            {editing && ((editing.mode === 'add' && editing.parentId === s.id) || (editing.mode === 'rename' && editing.spot.id === s.id)) && form}
          </li>
        ))}
      </ul>
      {owner && (editing?.mode === 'add' && editing.parentId === null ? form
        : <button className="secondary small" onClick={() => start({ mode: 'add', parentId: null })}>Add top-level storage</button>)}
    </div>
  )
}
