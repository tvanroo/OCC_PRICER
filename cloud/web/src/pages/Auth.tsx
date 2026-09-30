import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api'

export function Login({ onDone }: { onDone: () => Promise<void> }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const navigate = useNavigate()
  async function submit(e: React.FormEvent) {
    e.preventDefault()
    try {
      await api('/api/auth/login', { method: 'POST', body: { email, password } })
      await onDone()
      navigate('/app')
    } catch (err) { setError((err as Error).message) }
  }
  return (
    <form className="panel narrow" onSubmit={submit}>
      <h1>Store sign in</h1>
      <label>Email<input type="email" autoComplete="email" required value={email} onChange={e => setEmail(e.target.value)} /></label>
      <label>Password<input type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} /></label>
      {error && <p className="error">{error}</p>}
      <button type="submit">Sign in</button>
      <p className="muted">New store? <Link to="/signup">Start a free trial</Link></p>
    </form>
  )
}

export function Signup({ onDone }: { onDone: () => Promise<void> }) {
  const [form, setForm] = useState({ storeName: '', name: '', email: '', password: '' })
  const [error, setError] = useState('')
  const navigate = useNavigate()
  const set = (key: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [key]: e.target.value })
  async function submit(e: React.FormEvent) {
    e.preventDefault()
    try {
      await api('/api/auth/signup', { method: 'POST', body: form })
      await onDone()
      navigate('/app')
    } catch (err) { setError((err as Error).message) }
  }
  return (
    <form className="panel narrow" onSubmit={submit}>
      <h1>Start your store's free trial</h1>
      <p className="muted">30 days free. Trade-ins, payouts, history and POS exports for your whole staff.</p>
      <label>Store name<input required value={form.storeName} onChange={set('storeName')} /></label>
      <label>Your name<input required autoComplete="name" value={form.name} onChange={set('name')} /></label>
      <label>Email<input type="email" required autoComplete="email" value={form.email} onChange={set('email')} /></label>
      <label>Password (10+ characters)<input type="password" required minLength={10} autoComplete="new-password" value={form.password} onChange={set('password')} /></label>
      {error && <p className="error">{error}</p>}
      <button type="submit">Create store</button>
    </form>
  )
}
