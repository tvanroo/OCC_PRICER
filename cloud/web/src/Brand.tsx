/** The CardBox Trading mark: two opposing cards from the CardBox bloom, one in and one out. */
export function Mark({ size = 30 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="107 107 810 810" aria-hidden="true">
      <rect x="347" y="191" width="330" height="226" rx="64" fill="#4F86FF" transform="rotate(45 512 304)" />
      <rect x="347" y="607" width="330" height="226" rx="64" fill="#F5A640" transform="rotate(225 512 720)" />
    </svg>
  )
}

export function Wordmark() {
  return <span className="wordmark">CardBox <span>Trading</span></span>
}
