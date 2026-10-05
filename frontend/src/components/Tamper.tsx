import { useState } from 'react'
import { api, type AnchorCheck, type AnchorView, type DatasetInfo, type EntryView, type TamperOutcome, type TamperResult } from '../api'
import { fmt, SUBJECT_NOTES, SUBJECTS } from '../strategies'
import { Field } from '../ui/Field'
import { Figure } from '../ui/Figure'
import { Hash } from '../ui/Hash'
import { Seal } from '../ui/Seal'
import { Section } from '../ui/Section'

type Operation = 'edit' | 'insert'

const DEFAULT_MESSAGE: Record<Operation, string> = {
  edit: 'PAYMENT APPROVED',
  insert: 'injected entry',
}

/**
 * §3. One change, every strategy. The change is applied in memory by the API (the stored log is
 * untouched) and each strategy restores a valid super-root, re-hashing only what it must. The
 * second half demonstrates the trusted anchor against a real rewrite of the database.
 */
export function Tamper({ dataset }: { dataset: DatasetInfo }) {
  const [operation, setOperation] = useState<Operation>('insert')
  const [position, setPosition] = useState(String(Math.floor(dataset.size / 2)))
  const [message, setMessage] = useState(DEFAULT_MESSAGE.insert)
  const [result, setResult] = useState<TamperResult | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [run, setRun] = useState(0)

  const apply = () => {
    const value = Number(position.trim().replace(/,/g, ''))
    const max = operation === 'insert' ? dataset.size : dataset.size - 1
    const min = operation === 'insert' ? 1 : 0
    if (!Number.isInteger(value) || value < min || value > max) {
      setError(`Position must be a whole number from ${fmt(min)} to ${fmt(max)}.`)
      return
    }
    setBusy(true)
    setError(null)
    api
      .tamper(dataset.id, operation, value, message || DEFAULT_MESSAGE[operation])
      .then((r) => {
        setResult(r)
        setRun((n) => n + 1)
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false))
  }

  return (
    <div className="space-y-section">
      <Section
        number="3"
        title="What a change costs"
        lede="Edit or insert one entry. Every strategy notices (its super-root changes), and every strategy must re-hash something to publish a valid root again. What differs is how much."
      >
        <div className="mb-block flex flex-wrap items-end gap-x-gutter gap-y-5">
          <Field label="Change">
            <div className="flex rounded-[3px] border border-rule-strong p-0.5" role="radiogroup">
              {(['edit', 'insert'] as Operation[]).map((op) => (
                <button
                  key={op}
                  role="radio"
                  aria-checked={operation === op}
                  onClick={() => {
                    setOperation(op)
                    setMessage(DEFAULT_MESSAGE[op])
                  }}
                  className={`rounded-[2px] px-3 py-1 text-small font-medium transition-colors duration-150 ${
                    operation === op ? 'bg-ink text-paper' : 'text-ink-soft hover:text-ink'
                  }`}
                >
                  {op === 'edit' ? 'Edit an entry' : 'Insert an entry'}
                </button>
              ))}
            </div>
          </Field>
          <Field label={operation === 'insert' ? 'Insert before position' : 'Entry to edit'}>
            <input
              className="field figures w-28"
              inputMode="numeric"
              value={position}
              onChange={(e) => setPosition(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && apply()}
            />
          </Field>
          <Field label="New message" grow>
            <input className="field" value={message} onChange={(e) => setMessage(e.target.value)} />
          </Field>
          <button className="btn-ink" onClick={apply} disabled={busy}>
            {busy ? 'Computing…' : 'Apply change'}
          </button>
        </div>

        {error && (
          <p className="mb-block rounded-[3px] border border-tampered bg-tampered-wash px-4 py-3 text-small text-tampered">
            {error}
          </p>
        )}

        {result ? (
          <Comparison key={run} result={result} datasetName={dataset.name} />
        ) : (
          <p className="max-w-[62ch] text-ink-soft">
            Choose a change and apply it. It is computed in memory: the stored log is not modified. Try inserting near
            the middle of <span className="figures">demo-10k</span>; the gap between strategies grows with the log.
          </p>
        )}
      </Section>

      <AnchorDemo key={dataset.id} dataset={dataset} />
    </div>
  )
}

/** The headline sentence and the six rows: where each strategy changed, and what it re-hashed. */
function Comparison({ result, datasetName }: { result: TamperResult; datasetName: string }) {
  const caac = result.outcomes.find((o) => o.subject === 'caac')!
  const paper = result.outcomes.find((o) => o.subject === 'paper-pipeline')!
  const fixed = result.outcomes.find((o) => o.subject === 'fixed-size')!
  const maxCost = Math.max(...result.outcomes.map((o) => o.rebuildHashOps))
  const verb = result.operation === 'insert' ? 'inserting an entry before' : 'editing entry'

  return (
    <div className="space-y-block">
      <div className="arrive grid gap-x-gutter gap-y-6 border-y border-rule py-block md:grid-cols-3">
        <Headline value={fmt(caac.rebuildHashOps)} unit="hashes" label={`CAAC re-hashes after ${verb} ${fmt(result.position)}`} ours />
        <Headline
          value={`${(paper.rebuildHashOps / Math.max(1, caac.rebuildHashOps)).toFixed(1)}×`}
          unit="more"
          label={`for the base paper's pipeline (${fmt(paper.rebuildHashOps)}), which rebuilds its one global tree`}
        />
        <Headline
          value={`${caac.changedChunks.length} vs ${fixed.changedChunks.length}`}
          unit="chunks"
          label="changed under CAAC and under fixed-size chunking"
        />
      </div>

      <Figure
        number="4"
        caption={
          <>
            {result.operation === 'insert' ? 'Inserting one entry before' : 'Editing entry'} {fmt(result.position)} of{' '}
            {datasetName}. Each strip is the whole log after the change; chunks whose root changed are marked. The bar is
            the number of SHA-256 operations needed to publish a valid super-root again, re-hashing only those chunks plus
            the super-tree (a count, not a timing).
          </>
        }
      >
        <div className="grid grid-cols-[minmax(9rem,13rem)_minmax(0,1fr)] gap-x-gutter md:grid-cols-[minmax(9rem,13rem)_minmax(0,1fr)_minmax(10rem,16rem)]">
          <div />
          <div className="kicker pb-2">Chunks after the change · changed in vermilion</div>
          <div className="kicker hidden pb-2 md:block">Hashes to restore the root</div>
          {result.outcomes.map((o, i) => (
            <OutcomeRow
              key={o.subject}
              outcome={o}
              position={result.position}
              total={o.chunkSizesAfter.reduce((a, b) => a + b, 0)}
              maxCost={maxCost}
              delayMs={i * 70}
            />
          ))}
        </div>
      </Figure>
    </div>
  )
}

function Headline({ value, unit, label, ours = false }: { value: string; unit: string; label: string; ours?: boolean }) {
  return (
    <div>
      <div className="flex items-baseline gap-2">
        <span className={`font-display text-display leading-none font-light ${ours ? 'text-signal' : 'text-ink'}`}>{value}</span>
        <span className="kicker">{unit}</span>
      </div>
      <p className="mt-2 max-w-[34ch] text-small text-ink-soft">{label}</p>
    </div>
  )
}

function OutcomeRow({
  outcome,
  position,
  total,
  maxCost,
  delayMs,
}: {
  outcome: TamperOutcome
  position: number
  total: number
  maxCost: number
  delayMs: number
}) {
  const meta = SUBJECTS[outcome.subject]
  const changed = new Set(outcome.changedChunks)
  const starts = startPositions(outcome.chunkSizesAfter)
  const chunks = outcome.chunkSizesAfter.map((size, index) => ({ index, start: starts[index], size }))
  const share = outcome.rebuildHashOps / Math.max(1, maxCost)

  return (
    <>
      <div className="border-t border-rule py-3.5">
        <div className="flex items-center gap-2.5">
          <span className="h-3 w-1 rounded-full" style={{ background: meta.color }} />
          <span className={`font-medium ${meta.ours ? 'text-signal' : 'text-ink'}`}>{meta.label}</span>
        </div>
        <div className="mt-0.5 pl-3.5 text-small text-ink-faint">{SUBJECT_NOTES[outcome.subject]}</div>
        <div className="figures mt-1 pl-3.5 text-micro text-ink-soft">
          {fmt(outcome.changedChunks.length)} of {fmt(outcome.chunkCountAfter)} chunks changed
        </div>
      </div>

      <div className="flex flex-col justify-center gap-1.5 border-t border-rule py-3.5">
        <svg viewBox={`0 0 ${total} 10`} preserveAspectRatio="none" className="h-7 w-full">
          {chunks.map((c) => (
            <rect
              key={c.index}
              x={c.start}
              width={c.size}
              height={10}
              fill={changed.has(c.index) ? 'var(--tampered)' : meta.color}
              fillOpacity={changed.has(c.index) ? 0.95 : c.index % 2 === 0 ? 0.32 : 0.16}
              stroke="var(--paper-raised)"
              strokeWidth={chunks.length > 400 ? 0 : 1}
              vectorEffect="non-scaling-stroke"
              className={changed.has(c.index) ? 'arrive' : undefined}
              style={changed.has(c.index) ? { animationDelay: `${delayMs + 200}ms` } : undefined}
            >
              <title>{`Chunk ${c.index}: entries ${c.start}–${c.start + c.size - 1}${changed.has(c.index) ? ' · changed' : ''}`}</title>
            </rect>
          ))}
          <line x1={position} x2={position} y1={-2} y2={12} stroke="var(--ink)" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
        </svg>
        <div className="flex items-center gap-2 text-micro">
          <span className="rounded-[2px] bg-tampered-wash px-1.5 py-0.5 font-mono tracking-wide text-tampered uppercase">
            {outcome.detected ? 'detected' : 'not detected'}
          </span>
          <span className="figures flex items-center gap-1 text-ink-faint">
            root <Hash value={outcome.superRootBefore} chars={8} /> → <Hash value={outcome.superRootAfter} chars={8} />
          </span>
        </div>
      </div>

      <div className="col-span-2 flex items-center gap-3 pb-3.5 md:col-span-1 md:border-t md:border-rule md:py-3.5">
        <div className="h-2.5 flex-1 overflow-hidden rounded-full bg-paper-sunk">
          <div
            className="grow-x h-full rounded-full"
            style={{
              width: `${Math.max(1.5, share * 100)}%`,
              background: meta.ours ? 'var(--signal)' : meta.color,
              animationDelay: `${delayMs}ms`,
            }}
          />
        </div>
        <span className={`figures w-16 text-right text-small ${meta.ours ? 'text-signal' : 'text-ink'}`}>
          {fmt(outcome.rebuildHashOps)}
        </span>
      </div>
    </>
  )
}

/** Where each chunk starts in the stream: the running sum of the sizes before it. */
function startPositions(sizes: number[]): number[] {
  const starts = new Array<number>(sizes.length)
  let position = 0
  for (let i = 0; i < sizes.length; i++) {
    starts[i] = position
    position += sizes[i]
  }
  return starts
}

// ------------------------------------------------------------------ the trusted anchor

type Step = 'start' | 'anchored' | 'attacked'

/**
 * The paper's trusted anchor (§3.1), against a real attack: publish the super-root, rewrite one
 * stored entry directly in the database, then recompute and compare. Uses CAAC; undo restores
 * the entry so the other views are unaffected.
 */
function AnchorDemo({ dataset }: { dataset: DatasetInfo }) {
  const [step, setStep] = useState<Step>('start')
  const [anchor, setAnchor] = useState<AnchorView | null>(null)
  const [target, setTarget] = useState(String(Math.floor(dataset.size / 3)))
  const [original, setOriginal] = useState<EntryView | null>(null)
  const [attacked, setAttacked] = useState<EntryView | null>(null)
  const [check, setCheck] = useState<AnchorCheck | null>(null)
  const [checkRun, setCheckRun] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const act = (work: () => Promise<void>) => {
    setBusy(true)
    setError(null)
    work()
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false))
  }

  const doAnchor = () =>
    act(async () => {
      setAnchor(await api.anchor(dataset.id, 'caac'))
      setCheck(null)
      setStep('anchored')
    })

  const doAttack = () =>
    act(async () => {
      const position = Number(target)
      if (!Number.isInteger(position) || position < 0 || position >= dataset.size) {
        throw new Error(`Entry must be from 0 to ${fmt(dataset.size - 1)}.`)
      }
      const page = await api.entries(dataset.id, position, 1)
      setOriginal(page.entries[0])
      setAttacked(await api.overwrite(dataset.id, position, 'PAYMENT APPROVED'))
      setCheck(null)
      setStep('attacked')
    })

  const doVerify = () =>
    act(async () => {
      setCheck(await api.verifyAnchor(dataset.id, 'caac'))
      setCheckRun((n) => n + 1)
    })

  const doUndo = () =>
    act(async () => {
      if (original) await api.overwrite(dataset.id, original.position, original.message)
      setAttacked(null)
      setOriginal(null)
      setCheck(await api.verifyAnchor(dataset.id, 'caac'))
      setCheckRun((n) => n + 1)
      setStep('anchored')
    })

  return (
    <Section
      number="3.1"
      title="Catching a rewritten log"
      lede="The base paper stores the root in a trusted anchor the attacker cannot reach. Here the attack is real: an entry is rewritten directly in PostgreSQL, and recomputing the super-root exposes it."
    >
      {error && (
        <p className="mb-block rounded-[3px] border border-tampered bg-tampered-wash px-4 py-3 text-small text-tampered">
          {error}
        </p>
      )}
      <ol className="grid gap-x-gutter gap-y-block lg:grid-cols-3">
        <Procedure n="1" title="Publish the root" done={step !== 'start'}>
          <p className="text-small text-ink-soft">Record the current CAAC super-root as trusted.</p>
          <button className="btn-ink mt-4" onClick={doAnchor} disabled={busy}>
            Anchor the super-root
          </button>
          {anchor && (
            <div className="arrive figures mt-4 text-micro text-ink-soft">
              anchored <Hash value={anchor.superRoot} chars={16} />
              <div className="mt-1">
                {fmt(anchor.entryCount)} entries · {fmt(anchor.chunkCount)} chunks
              </div>
            </div>
          )}
        </Procedure>

        <Procedure n="2" title="Rewrite an entry" done={step === 'attacked'} disabled={step === 'start'}>
          <p className="text-small text-ink-soft">
            Overwrite one stored entry in the database, as an attacker with write access would.
          </p>
          <div className="mt-4 flex items-end gap-2">
            <Field label="Entry">
              <input
                className="field figures w-24"
                inputMode="numeric"
                value={target}
                onChange={(e) => setTarget(e.target.value)}
                disabled={step !== 'anchored'}
              />
            </Field>
            <button
              className="btn border-tampered text-tampered hover:border-tampered hover:bg-tampered-wash"
              onClick={doAttack}
              disabled={busy || step !== 'anchored'}
            >
              Overwrite in the database
            </button>
          </div>
          {original && attacked && (
            <div className="arrive mt-4 space-y-1 text-small">
              <div className="text-ink-faint line-through">{original.message}</div>
              <div className="font-medium text-tampered">{attacked.message}</div>
            </div>
          )}
        </Procedure>

        <Procedure n="3" title="Verify against the anchor" done={check !== null} disabled={step === 'start'}>
          <p className="text-small text-ink-soft">Recompute the super-root from the stored entries and compare.</p>
          <div className="mt-4 flex flex-wrap gap-2">
            <button className="btn-ink" onClick={doVerify} disabled={busy || step === 'start'}>
              Verify
            </button>
            {step === 'attacked' && (
              <button className="btn" onClick={doUndo} disabled={busy}>
                Undo the attack
              </button>
            )}
          </div>
          {check && (
            <div key={checkRun} className="mt-4 space-y-3">
              <Seal ok={check.matches} okLabel="Intact" badLabel="Rewritten" />
              <div className="figures space-y-0.5 text-micro text-ink-soft">
                <div>
                  anchored <Hash value={check.anchor.superRoot} chars={16} />
                </div>
                <div>
                  recomputed <Hash value={check.recomputedRoot} chars={16} />
                </div>
              </div>
            </div>
          )}
        </Procedure>
      </ol>
    </Section>
  )
}

function Procedure({
  n,
  title,
  done,
  disabled = false,
  children,
}: {
  n: string
  title: string
  done: boolean
  disabled?: boolean
  children: React.ReactNode
}) {
  return (
    <li className={`border-t-2 pt-4 transition-opacity duration-300 ${done ? 'border-ink' : 'border-rule'} ${disabled ? 'opacity-45' : ''}`}>
      <div className="flex items-baseline gap-3">
        <span className="figures text-micro text-ink-faint">{n.padStart(2, '0')}</span>
        <h3 className="font-display text-lede">{title}</h3>
        {done && <span className="kicker text-verified">done</span>}
      </div>
      <div className="mt-2 pl-7">{children}</div>
    </li>
  )
}
