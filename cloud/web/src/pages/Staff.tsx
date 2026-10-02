import { useEffect, useState } from 'react'
import { api, type Me } from '../api'

interface Member { id: string; name: string; email: string; role: string }

export default function Staff({ me }: { me: Me }) {
  const [staff, setStaff] = useState<Member[]>([])
  const [form, setForm] = useState({ name: '', email: '' })
  const [error, setError] = useState('')
  useEffect(() => { api<Member[]>('/api/app/staff').then(setStaff).catch(e => setError(e.message)) }, [])
  async function add(e: React.FormEvent) {
    e.preventDefault()
    try {
      setStaff(await api<Member[]>('/api/app/staff', { method: 'POST', body: form }))
      setForm({ name: '', email: '' }); setError('')
    } catch (err) { setError((err as Error).message) }
  }
  return (
    <section>
      <h1>Staff</h1>
      <div className="table-wrap"><table className="grid">
        <thead><tr><th>Name</th><th>Email</th><th>Role</th></tr></thead>
        <tbody>{staff.map(s => <tr key={s.id}><td>{s.name}</td><td>{s.email}</td><td>{s.role}</td></tr>)}</tbody>
      </table></div>
      {me.role === 'owner' && (
        <form className="panel narrow" onSubmit={add}>
          <h2>Add a staff account</h2>
          <label>Name<input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></label>
          <label>Email<input type="email" required value={form.email} onChange={e => setForm({ ...form, email: e.target.value })} /></label>
          <p className="muted">They sign in with their own CardBox login using this email, and join your store the first time they do.</p>
          <button type="submit">Add staff</button>
        </form>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  )
}
