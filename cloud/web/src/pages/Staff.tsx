import { useEffect, useState } from 'react'
import { api, type Me } from '../api'

interface Member { id: string; name: string; email: string; role: string }

export default function Staff({ me }: { me: Me }) {
  const [staff, setStaff] = useState<Member[]>([])
  const [form, setForm] = useState({ name: '', email: '', password: '' })
  const [error, setError] = useState('')
  useEffect(() => { api<Member[]>('/api/app/staff').then(setStaff).catch(e => setError(e.message)) }, [])
  async function add(e: React.FormEvent) {
    e.preventDefault()
    try {
      setStaff(await api<Member[]>('/api/app/staff', { method: 'POST', body: form }))
      setForm({ name: '', email: '', password: '' }); setError('')
    } catch (err) { setError((err as Error).message) }
  }
  return (
    <section>
      <h1>Staff</h1>
      <table className="grid">
        <thead><tr><th>Name</th><th>Email</th><th>Role</th></tr></thead>
        <tbody>{staff.map(s => <tr key={s.id}><td>{s.name}</td><td>{s.email}</td><td>{s.role}</td></tr>)}</tbody>
      </table>
      {me.role === 'owner' && (
        <form className="panel narrow" onSubmit={add}>
          <h2>Add a staff account</h2>
          <label>Name<input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>Email<input type="email" required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
          <label>Temporary password (10+ characters)<input type="password" required minLength={10} autoComplete="new-password"
            value={form.password} onChange={e => setForm({ ...form, password: e.target.value })} /></label>
          <button type="submit">Add staff</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
