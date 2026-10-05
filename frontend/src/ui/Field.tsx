import type { ReactNode } from 'react'

/** A labelled control: kicker above, control below. */
export function Field({ label, children, grow = false }: { label: ReactNode; children: ReactNode; grow?: boolean }) {
  return (
    <label className={`flex flex-col gap-1.5 ${grow ? 'min-w-56 flex-1' : ''}`}>
      <span className="kicker">{label}</span>
      {children}
    </label>
  )
}

/** A labelled figure: the number is the hero, the label explains it. */
export function Stat({ label, value, emphasis = false }: { label: string; value: ReactNode; emphasis?: boolean }) {
  return (
    <div>
      <div className="kicker">{label}</div>
      <div className={`figures mt-1 text-lede ${emphasis ? 'text-signal' : 'text-ink'}`}>{value}</div>
    </div>
  )
}
