export const ROUND_LABELS = {
  final: 'Final',
  semifinal: 'Semi-final',
  semifinal1: 'Semi-final 1',
  semifinal2: 'Semi-final 2',
}

export const VOTE_TYPE_LABELS = {
  total: 'Combined',
  jury: 'Jury',
  public: 'Televote',
}

const ROUND_ORDER = ['final', 'semifinal1', 'semifinal2', 'semifinal']
const VOTE_TYPES = ['total', 'jury', 'public']

export function sortRounds(rounds) {
  return [...rounds].sort(
    (a, b) => ROUND_ORDER.indexOf(a.name) - ROUND_ORDER.indexOf(b.name),
  )
}

function scoreOf(performance, name) {
  return performance.scores.find((score) => score.name === name)
}

export function buildRows(contest, round) {
  const contestants = new Map(contest.contestants.map((c) => [c.id, c]))
  const finalRound = contest.rounds.find((r) => r.name === 'final')
  const finalists = new Set(finalRound?.performances.map((p) => p.contestantId))

  return round.performances
    .map((performance) => {
      const contestant = contestants.get(performance.contestantId)
      const jury = scoreOf(performance, 'jury')
      const televote = scoreOf(performance, 'public')

      return {
        id: performance.contestantId,
        code: contestant.country,
        country: contest.countries[contestant.country] ?? contestant.country,
        artist: contestant.artist,
        song: contestant.song,
        running: performance.running,
        place: performance.place,
        total: scoreOf(performance, 'total')?.points ?? null,
        jury: jury?.points ?? null,
        televote: televote?.points ?? null,
        votes: Object.fromEntries(
          VOTE_TYPES.map((type) => [type, scoreOf(performance, type)?.votes ?? {}]),
        ),
        disqualified: round.disqualifieds.includes(performance.contestantId),
        qualified: round.name !== 'final' && finalists.has(performance.contestantId),
      }
    })
    .sort(
      (a, b) =>
        (a.place ?? Number.MAX_SAFE_INTEGER) - (b.place ?? Number.MAX_SAFE_INTEGER) ||
        (a.running ?? 0) - (b.running ?? 0),
    )
}

export function availableVoteTypes(rows) {
  return VOTE_TYPES.filter((type) =>
    rows.some((row) => Object.keys(row.votes[type]).length > 0),
  )
}

export function buildMatrix(rows, type, countries) {
  const values = {}
  const voterCodes = new Set()
  let max = 0

  for (const row of rows) {
    for (const [from, points] of Object.entries(row.votes[type])) {
      voterCodes.add(from)
      values[from] ??= {}
      values[from][row.code] = points
      max = Math.max(max, points)
    }
  }

  return {
    values,
    max,
    receivers: rows.map(({ code, country }) => ({ code, country })),
    voters: [...voterCodes]
      .map((code) => ({ code, country: countries[code] ?? code }))
      .sort((a, b) => a.country.localeCompare(b.country)),
  }
}

export function votesGivenBy(matrix, code) {
  return matrix.receivers
    .map((receiver) => ({ ...receiver, points: matrix.values[code]?.[receiver.code] ?? 0 }))
    .filter((entry) => entry.points > 0)
    .sort((a, b) => b.points - a.points)
}

export function votesReceivedBy(matrix, code) {
  return matrix.voters
    .map((voter) => ({ ...voter, points: matrix.values[voter.code]?.[code] ?? 0 }))
    .filter((entry) => entry.points > 0)
    .sort((a, b) => b.points - a.points)
}

// Everyone tied for the highest count, so a shared record is not shown as one country's.
function leaders(entries) {
  const count = Math.max(0, ...entries.map((entry) => entry.count))
  const names = entries
    .filter((entry) => entry.count === count)
    .map((entry) => entry.country)
    .join(', ')

  return { count, names }
}

export function buildHighlights(rows, matrix) {
  const highlights = []
  const winner = rows.find((row) => row.place === 1)
  const runnerUp = rows.find((row) => row.place !== 1 && row.total !== null)
  const tiedWinners = rows.filter((row) => row.place === 1)

  if (winner && tiedWinners.length > 1) {
    highlights.push({
      label: 'Shared win',
      value: tiedWinners.length,
      detail: tiedWinners.map((row) => row.country).join(', '),
    })
  } else if (winner?.total !== null && runnerUp) {
    highlights.push({
      label: 'Winning margin',
      value: `+${winner.total - runnerUp.total}`,
      detail: `${winner.country} over ${runnerUp.country}`,
    })
  }

  const supportCounts = matrix.receivers.map((receiver) => ({
    ...receiver,
    count: votesReceivedBy(matrix, receiver.code).length,
  }))
  const widestSupport = leaders(supportCounts)

  if (widestSupport.count) {
    highlights.push({
      label: 'Widest support',
      value: `${widestSupport.count}/${matrix.voters.length}`,
      detail: `${widestSupport.names} got points from the most countries`,
    })
  }

  const topMarkCounts = matrix.receivers.map((receiver) => ({
    ...receiver,
    count: votesReceivedBy(matrix, receiver.code).filter((v) => v.points === matrix.max).length,
  }))
  const mostTopMarks = leaders(topMarkCounts)

  if (mostTopMarks.count) {
    highlights.push({
      label: `Most ${matrix.max}-point votes`,
      value: mostTopMarks.count,
      detail: mostTopMarks.names,
    })
  }

  const [closest] = topExchanges(matrix, 1)
  if (closest) {
    highlights.push({
      label: 'Strongest exchange',
      value: closest.sum,
      detail: `${closest.a.country} and ${closest.b.country}`,
    })
  }

  return highlights
}

export function topExchanges(matrix, limit = 8) {
  const pairs = []

  matrix.receivers.forEach((a, index) => {
    for (const b of matrix.receivers.slice(index + 1)) {
      const aToB = matrix.values[a.code]?.[b.code] ?? 0
      const bToA = matrix.values[b.code]?.[a.code] ?? 0
      if (aToB > 0 && bToA > 0) pairs.push({ a, b, aToB, bToA, sum: aToB + bToA })
    }
  })

  return pairs.sort((x, y) => y.sum - x.sum).slice(0, limit)
}

export function juryTelevoteGaps(rows) {
  return rows
    .filter((row) => row.jury !== null && row.televote !== null)
    .map((row) => ({
      code: row.code,
      country: row.country,
      jury: row.jury,
      televote: row.televote,
      gap: row.jury - row.televote,
    }))
    .sort((a, b) => b.gap - a.gap)
}
