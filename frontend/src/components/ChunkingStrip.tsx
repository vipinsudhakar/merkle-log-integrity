import { useEffect, useMemo, useState } from 'react'
import { api, type DatasetInfo, type EntryView, type ForestView, type Params } from '../api'
import { fmt, PRESSURE_PROFILES, STRATEGY_ORDER, SUBJECT_NOTES, SUBJECTS } from '../strategies'
import { Field } from '../ui/Field'
import { Figure } from '../ui/Figure'
import { Hash } from '../ui/Hash'
import { Section } from '../ui/Section'

interface Props {
  dataset: DatasetInfo
  /** Opens the proof view at an entry: the first entry of the clicked chunk. */
  onOpenChunk: (strategy: string, entryIndex: number, params: Params) => void
}

const SPANS = [100, 250, 500, 2000]

/**
 * §1. The same log stream cut by every strategy, drawn to scale. Chunks keep their identity
 * across parameter changes and glide to their new place, so you can see which boundaries move
 * and which hold still. Each super-root re-resolves when it changes.
 */
export function ChunkingStrip({ dataset, onOpenChunk }: Props) {
  const [chunkSize, setChunkSize] = useState(64)
  const [pressure, setPressure] = useState<keyof typeof PRESSURE_PROFILES>('baseline')
  const [span, setSpan] = useState(250)
  const [viewStart, setViewStart] = useState(0)
  const [forests, setForests] = useState<Record<string, ForestView>>({})
  const [entries, setEntries] = useState<EntryView[]>([])
  const [error, setError] = useState<string | null>(null)

  const n = dataset.size
  const viewSpan = Math.min(span, n)
  const start = Math.min(viewStart, Math.max(0, n - viewSpan))

  // The pressure window is scaled to the dataset so the paper's five-window profile always fits.
  const paramsFor = useMemo(() => {
    const pressureParams: Params = {
      pressureProfile: PRESSURE_PROFILES[pressure].value,
      pressureWindow: String(Math.max(1, Math.ceil(n / 5))),
    }
    return (strategy: string): Params => {
      if (strategy === 'fixed-size') return { chunkSize: String(chunkSize) }
      if (strategy === 'resource-aware' || strategy === 'caac') return pressureParams
      return {}
    }
  }, [chunkSize, pressure, n])

  useEffect(() => {
    let cancelled = false
    Promise.all(STRATEGY_ORDER.map((s) => api.chunks(dataset.id, s, paramsFor(s))))
      .then((views) => {
        if (!cancelled) {
          setError(null)
          setForests(Object.fromEntries(views.map((v) => [v.strategy, v])))
        }
      })
      .catch((e: Error) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [dataset.id, paramsFor])

  useEffect(() => {
    let cancelled = false
    if (viewSpan > 500) return
    api.entries(dataset.id, start, viewSpan).then((page) => !cancelled && setEntries(page.entries))
    return () => {
      cancelled = true
    }
  }, [dataset.id, start, viewSpan])

  return (
    <Section
      number="1"
      title="How each strategy cuts the log"
      lede="Five strategies, one stream. Every block is a chunk with its own Merkle tree, and where a strategy cuts decides what a later edit or insertion costs."
    >
      <div className="mb-block flex flex-wrap items-end gap-x-gutter gap-y-5">
        <Field label="Window">
          <select className="field" value={span} onChange={(e) => setSpan(Number(e.target.value))}>
            {SPANS.map((s) => (
              <option key={s} value={s}>
                {fmt(Math.min(s, n))} entries
              </option>
            ))}
          </select>
        </Field>
        <Field
          grow
          label={
            <>
              Position <span className="figures normal-case text-ink">{fmt(start)}–{fmt(start + viewSpan)}</span> of{' '}
              {fmt(n)}
            </>
          }
        >
          <input
            type="range"
            className="range w-full"
            min={0}
            max={Math.max(0, n - viewSpan)}
            step={Math.max(1, Math.floor(viewSpan / 10))}
            value={start}
            onChange={(e) => setViewStart(Number(e.target.value))}
          />
        </Field>
        <Field
          label={
            <>
              Fixed-size <span className="figures normal-case text-ink">{chunkSize}</span>
            </>
          }
        >
          <input
            type="range"
            className="range w-40"
            min={8}
            max={256}
            step={8}
            value={chunkSize}
            onChange={(e) => setChunkSize(Number(e.target.value))}
          />
        </Field>
        <Field label="Memory pressure">
          <select className="field" value={pressure} onChange={(e) => setPressure(e.target.value as typeof pressure)}>
            {Object.entries(PRESSURE_PROFILES).map(([key, p]) => (
              <option key={key} value={key}>
                {p.label}
              </option>
            ))}
          </select>
        </Field>
      </div>

      {error && (
        <p className="mb-block rounded-[3px] border border-tampered bg-tampered-wash px-4 py-3 text-small text-tampered">
          {error}
        </p>
      )}

      <Figure
        number="1"
        caption={
          <>
            Entries {fmt(start)}–{fmt(start + viewSpan)} of {dataset.name}, cut by each strategy. The ticks above the
            strips are WARN/ERROR entries, whose payloads carry random-looking tokens: the signal entropy chunking reacts
            to. Click any chunk to prove one of its entries in §2.
          </>
        }
      >
        <div className="grid grid-cols-[minmax(10rem,15rem)_minmax(0,1fr)] gap-x-gutter">
          <div />
          <div>
            <Axis start={start} span={viewSpan} />
            <EntryLane entries={viewSpan <= 500 ? entries : []} span={viewSpan} />
          </div>

          {STRATEGY_ORDER.map((name) => (
            <StrategyRow
              key={name}
              name={name}
              forest={forests[name]}
              start={start}
              span={viewSpan}
              onOpen={(entry) => onOpenChunk(name, entry, paramsFor(name))}
            />
          ))}
        </div>
      </Figure>
    </Section>
  )
}

function StrategyRow({
  name,
  forest,
  start,
  span,
  onOpen,
}: {
  name: string
  forest?: ForestView
  start: number
  span: number
  onOpen: (entryIndex: number) => void
}) {
  const meta = SUBJECTS[name]
  const end = start + span
  const visible = forest ? forest.chunks.filter((c) => c.start < end && c.start + c.size > start) : []

  return (
    <>
      <div className={`border-t border-rule py-4 ${meta.ours ? 'border-t-signal/40' : ''}`}>
        <div className="flex items-center gap-2.5">
          <span className="h-3 w-1 rounded-full" style={{ background: meta.color }} />
          <span className={`font-medium ${meta.ours ? 'text-signal' : 'text-ink'}`}>{meta.label}</span>
        </div>
        <div className="mt-0.5 pl-3.5 text-small text-ink-faint">{SUBJECT_NOTES[name]}</div>
        {forest ? (
          <div className="figures mt-2 space-y-0.5 pl-3.5 text-micro text-ink-soft">
            <div>
              {fmt(forest.chunkCount)} chunks · μ {(forest.entryCount / Math.max(1, forest.chunkCount)).toFixed(1)}
            </div>
            <div className="flex items-center gap-1">
              root <Hash value={forest.superRoot} chars={10} />
            </div>
          </div>
        ) : (
          <div className="placeholder mt-2 ml-3.5 h-8 w-32 rounded-[2px]" />
        )}
      </div>

      <div className={`flex items-center border-t border-rule py-4 ${meta.ours ? 'border-t-signal/40' : ''}`}>
        {forest ? (
          <svg viewBox={`0 0 ${span} 10`} preserveAspectRatio="none" className="h-10 w-full overflow-visible">
            {visible.map((c) => {
              const x = Math.max(c.start, start) - start
              const w = Math.min(c.start + c.size, end) - Math.max(c.start, start)
              return (
                <rect
                  key={c.index}
                  x={x}
                  y={0}
                  width={Math.max(0, w)}
                  height={10}
                  fill={meta.color}
                  fillOpacity={c.index % 2 === 0 ? (meta.ours ? 0.9 : 0.55) : meta.ours ? 0.55 : 0.28}
                  stroke="var(--paper-raised)"
                  strokeWidth={1.5}
                  vectorEffect="non-scaling-stroke"
                  className="chunk-rect cursor-pointer hover:[fill-opacity:1]"
                  onClick={() => onOpen(c.start)}
                >
                  <title>
                    {`Chunk ${c.index} · entries ${c.start}–${c.start + c.size - 1} (${c.size}) · tree depth ${c.depth}\nroot ${c.root}\nclick to prove`}
                  </title>
                </rect>
              )
            })}
          </svg>
        ) : (
          <div className="placeholder h-10 w-full rounded-[2px]" />
        )}
      </div>
    </>
  )
}

function EntryLane({ entries, span }: { entries: EntryView[]; span: number }) {
  return (
    <svg viewBox={`0 0 ${span} 4`} preserveAspectRatio="none" className="mt-2 mb-1 h-2.5 w-full">
      {entries.map((e, i) =>
        e.level === 'WARN' || e.level === 'ERROR' ? (
          <rect key={e.position} x={i} width={0.7} height={4} fill="var(--ink)" fillOpacity={0.55}>
            <title>{`#${e.position} ${e.level} · ${e.message}`}</title>
          </rect>
        ) : null,
      )}
    </svg>
  )
}

function Axis({ start, span }: { start: number; span: number }) {
  const ticks = 5
  return (
    <div className="figures flex justify-between text-micro text-ink-faint">
      {Array.from({ length: ticks + 1 }, (_, i) => (
        <span key={i}>{fmt(Math.round(start + (span * i) / ticks))}</span>
      ))}
    </div>
  )
}
