import type { ReactNode } from 'react'

interface Props {
  number: string
  caption: ReactNode
  children: ReactNode
  className?: string
}

/** A figure with a numbered caption underneath, as in the paper: "Fig. 1 — ...". */
export function Figure({ number, caption, children, className = '' }: Props) {
  return (
    <figure className={`min-w-0 ${className}`}>
      <div className="rounded-[4px] border border-rule bg-paper-raised p-block">{children}</div>
      <figcaption className="mt-3 max-w-[78ch] text-small leading-relaxed text-ink-soft">
        <span className="kicker mr-2 text-ink">Fig. {number}</span>
        {caption}
      </figcaption>
    </figure>
  )
}
