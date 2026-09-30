export type Money = number | string | null

export interface Card {
  id: string
  name: string
  set: string
  setName: string
  number: string
  rarity: string
  image: string | null
  usd: Money
  usdFoil: Money
  usdEtched: Money
}

export interface Me {
  name: string
  email: string
  role: 'owner' | 'staff'
  store: string
  planStatus: string
  trialEndsAt: string
  entitled: boolean
}

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

export async function api<T>(path: string, options: { method?: string; body?: unknown } = {}): Promise<T> {
  const response = await fetch(path, {
    method: options.method ?? 'GET',
    credentials: 'same-origin',
    headers: options.body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : null
  if (!response.ok) throw new ApiError(response.status, data?.error ?? `Request failed (${response.status})`)
  return data as T
}

export function money(value: Money | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return `$${Number(value).toFixed(2)}`
}

export const FINISHES = [
  { key: 'normal', label: 'Normal', price: (c: Card) => c.usd },
  { key: 'foil', label: 'Foil', price: (c: Card) => c.usdFoil },
  { key: 'etched', label: 'Etched', price: (c: Card) => c.usdEtched },
] as const

export const CONDITIONS = ['NM', 'LP', 'MP', 'HP', 'DMG'] as const
