interface Props {
  ok: boolean
  okLabel?: string
  badLabel?: string
  /** Delay before the stamp lands, e.g. after a proof has finished drawing. */
  delayMs?: number
}

/**
 * A verdict, pressed onto the page like a notary's stamp. Green only for a verified proof,
 * vermilion only for a mismatch: colour is reserved for meaning. Re-mount it (via key) to stamp
 * again when the verdict is recomputed.
 */
export function Seal({ ok, okLabel = 'Verified', badLabel = 'Tampered', delayMs = 0 }: Props) {
  return (
    <span
      role="status"
      style={{ ['--stamp-delay' as string]: `${delayMs}ms` }}
      className={`stamp inline-flex items-center gap-2 rounded-[3px] border-2 px-3 py-1 font-mono text-small font-medium tracking-[0.12em] uppercase ${
        ok ? 'border-verified bg-verified-wash text-verified' : 'border-tampered bg-tampered-wash text-tampered'
      }`}
    >
      <span aria-hidden>{ok ? '✓' : '✗'}</span>
      {ok ? okLabel : badLabel}
    </span>
  )
}
