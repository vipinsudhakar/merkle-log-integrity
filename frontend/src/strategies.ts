// Display metadata for each subject. Colours are the --s-* tokens in index.css: baselines are
// graphite, CAAC is the one saturated ink, so the eye lands on the contribution.

export interface SubjectMeta {
  label: string
  short: string
  color: string
  ours?: boolean
}

export const SUBJECTS: Record<string, SubjectMeta> = {
  'fixed-size': { label: 'Fixed-size', short: 'Fixed', color: 'var(--s-fixed)' },
  'time-window': { label: 'Time-window', short: 'Time', color: 'var(--s-time)' },
  entropy: { label: 'Entropy', short: 'Entropy', color: 'var(--s-entropy)' },
  'resource-aware': { label: 'Resource-aware', short: 'Paper sizing', color: 'var(--s-paper)' },
  caac: { label: 'CAAC', short: 'CAAC', color: 'var(--s-caac)', ours: true },
  'paper-pipeline': { label: "Paper's pipeline", short: 'Paper pipeline', color: 'var(--s-pipeline)' },
}

/** A one-line note under each strategy's name. */
export const SUBJECT_NOTES: Record<string, string> = {
  'fixed-size': 'every N entries',
  'time-window': 'every 60 s window',
  entropy: 'where payload entropy jumps',
  'resource-aware': 'base paper · size from memory',
  caac: 'ours · paper’s size, cut on content',
  'paper-pipeline': 'one global tree',
}

export const STRATEGY_ORDER = ['fixed-size', 'time-window', 'entropy', 'resource-aware', 'caac']

/** The paper's §5.3 stress profile: baseline, three stress windows, recovery. */
export const PRESSURE_PROFILES: Record<string, { label: string; value: string }> = {
  baseline: { label: 'Steady · P 0.25', value: '0.25' },
  stress: { label: 'Paper’s stress test', value: '0.25,0.85,0.85,0.85,0.25' },
}

export function fmt(n: number): string {
  return n.toLocaleString('en-US')
}

/**
 * One decimal place, rounded half away from zero on the decimal value. toFixed(1) works on the
 * binary value, so 1.15 (stored as 1.1499…) would print as 1.1 while the docs say 1.2.
 */
export function oneDecimal(n: number): string {
  return (Math.round(n * 10 + Number.EPSILON * 10) / 10).toFixed(1)
}
