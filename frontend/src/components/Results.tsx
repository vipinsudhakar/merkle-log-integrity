import { useEffect, useMemo, useState, type ReactNode } from 'react'
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ReferenceDot,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { api, type BenchmarkResults, type BenchmarkRow } from '../api'
import { fmt, oneDecimal, SUBJECTS } from '../strategies'
import { Figure } from '../ui/Figure'
import { Section } from '../ui/Section'

const ALL = ['fixed-size', 'time-window', 'entropy', 'resource-aware', 'caac', 'paper-pipeline']
const FORESTS = ALL.filter((s) => s !== 'paper-pipeline')

/**
 * §4. The committed benchmark (docs/benchmarks/results.json), served by GET /api/benchmarks.
 * Hovering a subject in the legend dims the others in every chart at once, so one strategy can
 * be followed across all the metrics.
 */
export function Results() {
  const [data, setData] = useState<BenchmarkResults | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [focus, setFocus] = useState<string | null>(null)

  useEffect(() => {
    api.benchmarks().then(setData).catch((e: Error) => setError(e.message))
  }, [])

  const derived = useMemo(() => (data ? derive(data) : null), [data])

  return (
    <Section
      number="4"
      title="The evidence"
      lede="The same comparison at the base paper's dataset sizes, from 1,000 to 100,000 entries. Hash counts are exact; timings are the mean of five runs on one machine."
      aside={data && <Legend subjects={ALL} focus={focus} onFocus={setFocus} />}
    >
      {error && (
        <p className="rounded-[3px] border border-tampered bg-tampered-wash px-4 py-3 text-small text-tampered">{error}</p>
      )}
      {!data || !derived ? (
        !error && <div className="placeholder h-64 w-full rounded-[4px]" />
      ) : (
        <div className="space-y-section">
          <div className="arrive grid gap-x-gutter gap-y-6 border-b border-rule pb-block md:grid-cols-3">
            <Headline
              value={`${derived.insertRatioVsPaper.toFixed(0)}×`}
              ours
              label={`fewer hashes than the base paper's pipeline to absorb one insertion at ${fmt(derived.n)} entries (${fmt(
                Math.round(derived.caac.insertHashOps.mean),
              )} vs ${fmt(Math.round(derived.paper.insertHashOps.mean))})`}
            />
            <Headline
              value={oneDecimal(derived.caac.insertChunkRootsChanged!.mean)}
              label={`chunks changed by one insertion under CAAC, against ${fmt(
                Math.round(derived.fixed.insertChunkRootsChanged!.mean),
              )} under fixed-size chunking`}
            />
            <Headline
              value={`${(derived.minRecall * 100).toFixed(0)}%`}
              label="of tampered entries detected, with no false alarms, for every strategy and corruption ratio (1–50 %)"
            />
          </div>

          <div className="grid gap-x-gutter gap-y-section xl:grid-cols-2">
            <Chart
              number="5"
              caption="Hashes to restore a valid root after inserting one entry, against log size (log–log). Count-based chunking and the paper's global tree grow with the log; CAAC stays near one chunk plus the super-tree."
            >
              <SizeLines data={data} subjects={ALL} focus={focus} value={(r) => r.insertHashOps.mean} unit="hashes" log />
            </Chart>
            <Chart
              number="6"
              caption="Hashes to restore a valid root after editing one entry. Every forest with similar chunk sizes pays about the same; the paper's pipeline rebuilds all n. Time-window and entropy pay for their many small chunks."
            >
              <SizeLines data={data} subjects={ALL} focus={focus} value={(r) => r.editHashOps.mean} unit="hashes" log />
            </Chart>
            <Chart
              number="7"
              caption="Chunk roots that change when one entry is inserted (mean of 20 positions). Count-based cutting shifts every later boundary; content- and time-based cutting re-synchronise immediately."
            >
              <SizeLines
                data={data}
                subjects={FORESTS}
                focus={focus}
                value={(r) => r.insertChunkRootsChanged?.mean ?? NaN}
                unit="chunks"
                log
              />
            </Chart>
            <Chart
              number="8"
              caption="Proof length in hashes. All are O(log n). The marked point is the base paper's reported 14 hashes at 10,000 entries, which the global tree reproduces exactly."
            >
              <SizeLines
                data={data}
                subjects={ALL}
                focus={focus}
                value={(r) => r.proofSteps.mean}
                unit="hashes"
                reference={{ x: 10_000, y: 14, label: 'paper: 14' }}
              />
            </Chart>
          </div>

          <Chart
            number="9"
            caption="Mean chunk size per 2,000-entry window under the base paper's stress profile (§5.3): baseline, three windows at high memory pressure, recovery. CAAC keeps the paper's adaptivity; fixed-size ignores memory."
          >
            <Pressure data={data} focus={focus} />
          </Chart>

          <SummaryTable rows={derived.atMax} n={derived.n} />
          <TamperTable data={data} />
          <Caveats notes={data.notes} generatedAt={data.generatedAt} runs={data.runs} java={data.java} />
        </div>
      )}
    </Section>
  )
}

// ------------------------------------------------------------------ derived numbers

function derive(data: BenchmarkResults) {
  const n = Math.max(...data.sizes)
  const atMax = data.results.filter((r) => r.size === n)
  const row = (s: string) => atMax.find((r) => r.subject === s)!
  const caac = row('caac')
  const paper = row('paper-pipeline')
  const fixed = row('fixed-size')
  return {
    n,
    atMax,
    caac,
    paper,
    fixed,
    insertRatioVsPaper: paper.insertHashOps.mean / caac.insertHashOps.mean,
    minRecall: Math.min(...data.tamper.map((t) => t.recall)),
  }
}

// ------------------------------------------------------------------ charts

const axisTick = { fill: 'var(--ink-faint)', fontSize: 11, fontFamily: 'var(--font-mono)' }

function compact(v: number): string {
  if (v >= 1_000_000) return `${+(v / 1_000_000).toFixed(1)}M`
  if (v >= 1_000) return `${+(v / 1_000).toFixed(1)}k`
  return `${+v.toFixed(1)}`
}

function SizeLines({
  data,
  subjects,
  focus,
  value,
  unit,
  log = false,
  reference,
}: {
  data: BenchmarkResults
  subjects: string[]
  focus: string | null
  value: (r: BenchmarkRow) => number
  unit: string
  log?: boolean
  reference?: { x: number; y: number; label: string }
}) {
  const rows = data.sizes.map((size) => {
    const row: Record<string, number> = { size }
    for (const s of subjects) {
      const r = data.results.find((x) => x.subject === s && x.size === size)
      if (r) row[s] = value(r)
    }
    return row
  })

  return (
    <ResponsiveContainer width="100%" height={280}>
      <LineChart data={rows} margin={{ top: 8, right: 16, bottom: 4, left: 4 }}>
        <CartesianGrid stroke="var(--rule)" strokeDasharray="2 4" vertical={false} />
        <XAxis
          dataKey="size"
          type="number"
          scale="log"
          domain={['dataMin', 'dataMax']}
          ticks={data.sizes}
          tickFormatter={compact}
          tick={axisTick}
          stroke="var(--rule-strong)"
        />
        <YAxis
          scale={log ? 'log' : 'linear'}
          domain={log ? ['auto', 'auto'] : [0, 'auto']}
          allowDataOverflow={false}
          tickFormatter={compact}
          tick={axisTick}
          stroke="var(--rule-strong)"
          width={48}
        />
        <Tooltip content={<ChartTooltip unit={unit} />} cursor={{ stroke: 'var(--rule-strong)' }} />
        {subjects.map((s) => {
          const meta = SUBJECTS[s]
          const dimmed = focus !== null && focus !== s
          return (
            <Line
              key={s}
              dataKey={s}
              type="monotone"
              stroke={meta.color}
              strokeWidth={meta.ours ? 3 : 1.75}
              strokeOpacity={dimmed ? 0.12 : 1}
              dot={{ r: meta.ours ? 3.5 : 2.5, fill: meta.color, strokeWidth: 0, fillOpacity: dimmed ? 0.12 : 1 }}
              activeDot={{ r: 5 }}
              isAnimationActive
              animationDuration={700}
              connectNulls
            />
          )
        })}
        {reference && (
          <ReferenceDot
            x={reference.x}
            y={reference.y}
            r={6}
            fill="none"
            stroke="var(--ink)"
            strokeWidth={1.5}
            label={{ value: reference.label, position: 'top', fill: 'var(--ink-soft)', fontSize: 11, fontFamily: 'var(--font-mono)' }}
          />
        )}
      </LineChart>
    </ResponsiveContainer>
  )
}

function Pressure({ data, focus }: { data: BenchmarkResults; focus: string | null }) {
  const subjects = ['fixed-size', 'resource-aware', 'caac']
  const windows = [...new Set(data.pressure.map((p) => p.window))]
  const rows = windows.map((w) => {
    const row: Record<string, number | string> = {}
    for (const s of subjects) {
      const p = data.pressure.find((x) => x.subject === s && x.window === w)
      if (p) {
        row[s] = p.avgChunkSize
        row.label = `W${w + 1} · P ${p.pressure.toFixed(2)}`
      }
    }
    return row
  })
  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={rows} margin={{ top: 8, right: 16, bottom: 4, left: 4 }} barGap={3} barCategoryGap="22%">
        <CartesianGrid stroke="var(--rule)" strokeDasharray="2 4" vertical={false} />
        <XAxis dataKey="label" tick={axisTick} stroke="var(--rule-strong)" />
        <YAxis tick={axisTick} stroke="var(--rule-strong)" width={48} />
        <Tooltip content={<ChartTooltip unit="entries per chunk" />} cursor={{ fill: 'var(--paper-sunk)' }} />
        {subjects.map((s) => (
          <Bar
            key={s}
            dataKey={s}
            fill={SUBJECTS[s].color}
            fillOpacity={focus !== null && focus !== s ? 0.15 : SUBJECTS[s].ours ? 1 : 0.7}
            radius={[2, 2, 0, 0]}
            animationDuration={700}
          />
        ))}
      </BarChart>
    </ResponsiveContainer>
  )
}

interface TooltipProps {
  active?: boolean
  label?: string | number
  unit: string
  payload?: { dataKey?: string | number; value?: number }[]
}

function ChartTooltip({ active, payload, label, unit }: TooltipProps) {
  if (!active || !payload?.length) return null
  const sorted = [...payload].filter((p) => Number.isFinite(p.value)).sort((a, b) => (b.value ?? 0) - (a.value ?? 0))
  return (
    <div className="rounded-[3px] border border-rule-strong bg-paper-raised px-3 py-2 shadow-sm">
      <div className="kicker mb-1.5">{typeof label === 'number' ? `${fmt(label)} entries` : label}</div>
      {sorted.map((p) => {
        const meta = SUBJECTS[String(p.dataKey)]
        return (
          <div key={String(p.dataKey)} className="flex items-center justify-between gap-6 text-small">
            <span className="flex items-center gap-2">
              <span className="h-2 w-2 rounded-full" style={{ background: meta.color }} />
              <span className={meta.ours ? 'font-medium text-signal' : ''}>{meta.label}</span>
            </span>
            <span className="figures">
              {fmt(+(p.value ?? 0).toFixed(1))} <span className="text-ink-faint">{unit}</span>
            </span>
          </div>
        )
      })}
    </div>
  )
}

// ------------------------------------------------------------------ pieces

function Chart({ number, caption, children }: { number: string; caption: string; children: ReactNode }) {
  return (
    <Figure number={number} caption={caption}>
      {children}
    </Figure>
  )
}

function Legend({ subjects, focus, onFocus }: { subjects: string[]; focus: string | null; onFocus: (s: string | null) => void }) {
  return (
    <div className="flex max-w-[34rem] flex-wrap gap-x-4 gap-y-2" onMouseLeave={() => onFocus(null)}>
      {subjects.map((s) => {
        const meta = SUBJECTS[s]
        return (
          <button
            key={s}
            type="button"
            onMouseEnter={() => onFocus(s)}
            onFocus={() => onFocus(s)}
            onBlur={() => onFocus(null)}
            className={`flex items-center gap-2 text-small transition-opacity duration-150 ${
              focus !== null && focus !== s ? 'opacity-35' : ''
            }`}
          >
            <span className="h-[3px] w-5 rounded-full" style={{ background: meta.color }} />
            <span className={meta.ours ? 'font-medium text-signal' : 'text-ink-soft'}>{meta.label}</span>
          </button>
        )
      })}
      <span className="kicker basis-full">Hover a strategy to follow it through every figure</span>
    </div>
  )
}

function Headline({ value, label, ours = false }: { value: string; label: string; ours?: boolean }) {
  return (
    <div>
      <div className={`font-display text-display leading-none font-light ${ours ? 'text-signal' : 'text-ink'}`}>{value}</div>
      <p className="mt-3 max-w-[36ch] text-small text-ink-soft">{label}</p>
    </div>
  )
}

function SummaryTable({ rows, n }: { rows: BenchmarkRow[]; n: number }) {
  const caac = rows.find((r) => r.subject === 'caac')!
  const ordered = ALL.map((s) => rows.find((r) => r.subject === s)!).filter(Boolean)
  return (
    <figure>
      <div className="kicker mb-3">Table 1 · {fmt(n)} entries</div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[46rem] border-collapse text-small">
          <thead>
            <tr className="border-y border-rule-strong text-left">
              <Th>Subject</Th>
              <Th right>Chunks</Th>
              <Th right>Insert · hashes</Th>
              <Th right>vs CAAC</Th>
              <Th right>Edit · hashes</Th>
              <Th right>Chunks changed per insert</Th>
              <Th right>Proof · hashes</Th>
              <Th right>Ingest · logs/s</Th>
            </tr>
          </thead>
          <tbody>
            {ordered.map((r) => {
              const meta = SUBJECTS[r.subject]
              return (
                <tr key={r.subject} className={`border-b border-rule ${meta.ours ? 'bg-signal-wash/60' : ''}`}>
                  <td className="py-2.5 pr-4">
                    <span className="flex items-center gap-2">
                      <span className="h-3 w-1 rounded-full" style={{ background: meta.color }} />
                      <span className={meta.ours ? 'font-medium text-signal' : ''}>{meta.label}</span>
                    </span>
                  </td>
                  <Td>{fmt(r.chunkCount)}</Td>
                  <Td strong={meta.ours}>{fmt(Math.round(r.insertHashOps.mean))}</Td>
                  <Td muted>{meta.ours ? '—' : `${(r.insertHashOps.mean / caac.insertHashOps.mean).toFixed(1)}×`}</Td>
                  <Td>{fmt(Math.round(r.editHashOps.mean))}</Td>
                  <Td>{r.insertChunkRootsChanged ? oneDecimal(r.insertChunkRootsChanged.mean) : '—'}</Td>
                  <Td>{oneDecimal(r.proofSteps.mean)}</Td>
                  <Td>{fmt(Math.round(r.ingestLogsPerSec.mean))}</Td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </figure>
  )
}

function TamperTable({ data }: { data: BenchmarkResults }) {
  const ratios = [...new Set(data.tamper.map((t) => t.ratio))]
  return (
    <figure>
      <div className="kicker mb-3">Table 2 · Tamper detection, F1 (10,000 entries) — cf. the paper's Table 6</div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[36rem] border-collapse text-small">
          <thead>
            <tr className="border-y border-rule-strong text-left">
              <Th>Subject</Th>
              {ratios.map((r) => (
                <Th key={r} right>
                  {(r * 100).toFixed(0)}% corrupted
                </Th>
              ))}
            </tr>
          </thead>
          <tbody>
            {ALL.map((s) => (
              <tr key={s} className="border-b border-rule">
                <td className="py-2 pr-4">{SUBJECTS[s].label}</td>
                {ratios.map((r) => {
                  const t = data.tamper.find((x) => x.subject === s && x.ratio === r)
                  return (
                    <Td key={r} muted={t?.f1 === 1}>
                      {t ? `${t.f1.toFixed(2)} (${fmt(t.detected)}/${fmt(t.tampered)})` : '—'}
                    </Td>
                  )
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </figure>
  )
}

function Caveats({ notes, generatedAt, runs, java }: { notes: string[]; generatedAt: string; runs: number; java: string }) {
  return (
    <aside className="max-w-[78ch] border-l-2 border-rule-strong pl-5">
      <div className="kicker mb-2">How to read these numbers</div>
      <ul className="list-disc space-y-1.5 pl-4 text-small text-ink-soft marker:text-ink-faint">
        {notes.map((n) => (
          <li key={n}>{n}</li>
        ))}
      </ul>
      <p className="figures mt-3 text-micro text-ink-faint">
        Generated {generatedAt.slice(0, 16).replace('T', ' ')} UTC · Java {java} · {runs} runs · docs/benchmarks/results.json
      </p>
    </aside>
  )
}

function Th({ children, right = false }: { children: ReactNode; right?: boolean }) {
  return <th className={`kicker py-2 pr-4 font-normal ${right ? 'text-right' : ''}`}>{children}</th>
}

function Td({ children, strong = false, muted = false }: { children: ReactNode; strong?: boolean; muted?: boolean }) {
  return (
    <td className={`figures py-2 pr-4 text-right ${strong ? 'font-medium text-signal' : muted ? 'text-ink-soft' : 'text-ink'}`}>
      {children}
    </td>
  )
}
