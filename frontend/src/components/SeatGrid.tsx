import { memo, useCallback, useState, type KeyboardEvent } from 'react'

import type { SeatResponse, SeatRowResponse } from '../api/types'
import { formatPrice } from '../lib/format'

/**
 * How a seat is DRAWN. Two of these come from the API's status and the rest are things only
 * this page knows:
 *
 *   available  the API says AVAILABLE and the page knows nothing else about it
 *   sold       the API says BOOKED
 */
export type SeatView = 'available' | 'sold'

const VIEW_WORDS: Record<SeatView, string> = {
  available: 'available',
  sold: 'sold',
}

const VIEW_CLASSES: Record<SeatView, string> = {
  available: 'border-slate-400 bg-white text-slate-800 hover:border-slate-900',
  // Sold is marked by MORE than colour: the number is struck through and the fill is
  // different, so the state survives a monochrome screen and a colour-blind reader.
  sold: 'cursor-not-allowed border-slate-200 bg-slate-200 text-slate-400 line-through',
}

interface SeatGridProps {
  /** What the grid is of, for a screen reader: "Seat map for Coldplay, Fri 9 Oct ...". */
  label: string
  rows: SeatRowResponse[]
  viewOf: (seat: SeatResponse) => SeatView
}

/**
 * The seat map: one row per venue row, one button per seat.
 *
 * =======================================================================================
 * ONE TAB STOP, NOT ONE PER SEAT
 * =======================================================================================
 * The obvious markup - a button per seat, each in the tab order - makes a 60-seat map 60
 * presses of Tab to get past, and a keyboard user has to get past it to reach anything
 * below. This follows the ARIA grid pattern instead:
 *
 *  - The whole grid is ONE tab stop ("roving tabindex": exactly one seat has tabindex 0,
 *    every other has -1).
 *  - Arrow keys move between seats. Home and End go to the ends of the row.
 *  - Up and Down keep the column where they can and clamp where a row is shorter.
 *
 * SOLD SEATS ARE REACHABLE. They are aria-disabled, not disabled: a disabled button is
 * skipped by focus entirely, and then a screen-reader user moving along a row has no way
 * to learn that seat 7 exists and is sold - the row would simply seem to have a gap.
 *
 * Each seat announces itself in full - "Row C, seat 14, ₹450, available" - because the
 * visible label is only the seat number, which means nothing out of context.
 *
 * =======================================================================================
 * HOW BIG A MAP THIS CAN DRAW - MEASURED, NOT ESTIMATED
 * =======================================================================================
 * Every seat is a DOM button. There is no virtualisation and no canvas, deliberately: the
 * demo venue is 60 seats and the technique that suits 100,000 is a different component.
 * event-service's validators allow a venue of 200 rows x 500 seats, so the question of
 * where this stops working has a real answer, and it was measured rather than guessed.
 *
 *   seats    time to first render    response (JSON, uncompressed)
 *   -----    --------------------    -----------------------------
 *      60                  139 ms                      4,897 bytes
 *   5,000                  408 ms                    405,398 bytes
 *  20,000                1,156 ms                  1,667,552 bytes
 *
 * How: real shows of 6x10, 50x100 and 100x200 seats created through event-service, the
 * PRODUCTION build served by `vite preview`, headless Chrome 154 on the development
 * machine, everything on localhost. "Time to first render" is navigation start to two
 * animation frames after the last seat is in the DOM; the median of three runs. The
 * response is not compressed by anything on the path (no content-encoding).
 *
 * What the numbers do and do not say:
 *  - They are FIRST RENDER on a fast machine with no network. A phone on a real
 *    connection is slower on both halves.
 *  - Roughly 80 bytes of JSON per seat, and THE POLL REPEATS IT. At 20,000 seats that is
 *    1.67 MB every five seconds, about 330 kB/s for as long as the tab is visible. The
 *    payload stops being reasonable well before the DOM does.
 *  - Not measured: scrolling and click responsiveness, memory, or the cost of a poll's
 *    re-render. And 100,000 seats, the validators' ceiling, was not tried at all.
 *
 * Each Seat is memoised on primitive props, so a five-second poll re-renders only the seats
 * whose state changed - but the FIRST render, and the JSON, are paid in full every time the
 * page opens, and the JSON again on every poll.
 */
export function SeatGrid({ label, rows, viewOf }: SeatGridProps) {
  // The seat that holds the grid's single tab stop. Null until the user has moved: the
  // first seat stands in, so Tab always has somewhere to land.
  const [activeId, setActiveId] = useState<number | null>(null)
  const tabStopId = activeId ?? rows[0]?.seats[0]?.id ?? null

  const onKeyDown = useCallback(
    (event: KeyboardEvent<HTMLDivElement>) => {
      const from = positionOf(rows, tabStopId)
      if (!from) {
        return
      }
      const to = move(rows, from, event.key)
      if (!to) {
        return
      }
      // Arrow keys would otherwise scroll the page (or the grid's own horizontal scroll).
      event.preventDefault()
      const next = rows[to.row]?.seats[to.column]
      if (next) {
        setActiveId(next.id)
        document.getElementById(seatElementId(next.id))?.focus()
      }
    },
    [rows, tabStopId],
  )

  return (
    // The scroll container. On a narrow screen a row is wider than the viewport and this
    // scrolls sideways; the row label stays pinned so the user always knows which row.
    //
    // No horizontal padding HERE, on purpose: the sideways padding is on the label and on
    // the row instead. A sticky element pins to the container's padding edge, so with
    // padding on the container the label pinned 16px in and the seats scrolling past showed
    // through the gap to its left. The label is also as TALL as a seat (h-8), not as tall
    // as its text: at text height it covered the middle of each seat sliding under it and
    // left the top and bottom showing. Both seen in a 400px screenshot, not predicted.
    <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white py-4">
      <div role="grid" aria-label={label} onKeyDown={onKeyDown} className="inline-flex flex-col gap-1.5">
        {rows.map((row) => (
          <div key={row.rowLabel} role="row" className="flex items-center gap-1.5 pr-4">
            <div
              role="rowheader"
              aria-label={`Row ${row.rowLabel}`}
              className="sticky left-0 z-10 flex h-8 w-12 shrink-0 items-center justify-center bg-white pl-4 text-xs font-semibold text-slate-500"
            >
              {row.rowLabel}
            </div>
            {row.seats.map((seat) => (
              <Seat
                key={seat.id}
                id={seat.id}
                rowLabel={seat.rowLabel}
                seatNumber={seat.seatNumber}
                price={seat.price}
                view={viewOf(seat)}
                isTabStop={seat.id === tabStopId}
                onFocusSeat={setActiveId}
              />
            ))}
          </div>
        ))}
      </div>
    </div>
  )
}

interface SeatProps {
  id: number
  rowLabel: string
  seatNumber: number
  price: number
  view: SeatView
  isTabStop: boolean
  onFocusSeat: (id: number) => void
}

/**
 * One seat. Memoised, and every prop is a primitive or a stable function, so a poll that
 * changes one seat's status re-renders one Seat rather than all of them.
 */
const Seat = memo(function Seat({ id, rowLabel, seatNumber, price, view, isTabStop, onFocusSeat }: SeatProps) {
  return (
    <div role="gridcell">
      <button
        type="button"
        id={seatElementId(id)}
        data-seat-view={view}
        tabIndex={isTabStop ? 0 : -1}
        aria-disabled={view === 'sold'}
        aria-label={`Row ${rowLabel}, seat ${seatNumber}, ${formatPrice(price)}, ${VIEW_WORDS[view]}`}
        onFocus={() => onFocusSeat(id)}
        className={`flex h-8 w-8 items-center justify-center rounded border text-xs font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-slate-900 focus-visible:ring-offset-1 ${VIEW_CLASSES[view]}`}
      >
        {seatNumber}
      </button>
    </div>
  )
})

function seatElementId(id: number): string {
  return `seat-${id}`
}

interface Position {
  row: number
  column: number
}

function positionOf(rows: SeatRowResponse[], seatId: number | null): Position | null {
  for (let row = 0; row < rows.length; row++) {
    const column = rows[row]?.seats.findIndex((seat) => seat.id === seatId) ?? -1
    if (column >= 0) {
      return { row, column }
    }
  }
  return null
}

/** Where a key takes the focus from `from`, or null when the key is not a movement. */
function move(rows: SeatRowResponse[], from: Position, key: string): Position | null {
  const lastColumn = (row: number) => Math.max((rows[row]?.seats.length ?? 1) - 1, 0)
  switch (key) {
    case 'ArrowRight':
      return { row: from.row, column: Math.min(from.column + 1, lastColumn(from.row)) }
    case 'ArrowLeft':
      return { row: from.row, column: Math.max(from.column - 1, 0) }
    case 'ArrowDown': {
      const row = Math.min(from.row + 1, rows.length - 1)
      return { row, column: Math.min(from.column, lastColumn(row)) }
    }
    case 'ArrowUp': {
      const row = Math.max(from.row - 1, 0)
      return { row, column: Math.min(from.column, lastColumn(row)) }
    }
    case 'Home':
      return { row: from.row, column: 0 }
    case 'End':
      return { row: from.row, column: lastColumn(from.row) }
    default:
      return null
  }
}

const LEGEND: { view: SeatView; text: string }[] = [
  { view: 'available', text: 'Available' },
  { view: 'sold', text: 'Sold' },
]

/** What each kind of seat looks like. Outside the scrolling grid, so it is always visible. */
export function SeatLegend() {
  return (
    <ul className="flex flex-wrap gap-x-5 gap-y-2 text-sm text-slate-600" aria-label="Seat map legend">
      {LEGEND.map((entry) => (
        <li key={entry.view} className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className={`flex h-5 w-5 items-center justify-center rounded border text-[10px] ${VIEW_CLASSES[entry.view]}`}
          >
            1
          </span>
          {entry.text}
        </li>
      ))}
    </ul>
  )
}
