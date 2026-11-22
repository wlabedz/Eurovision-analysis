<script setup>
import { computed, ref, watch } from 'vue'
import {
  VOTE_TYPE_LABELS,
  availableVoteTypes,
  buildMatrix,
  votesGivenBy,
  votesReceivedBy,
} from '../../utils/contestStats'

const props = defineProps({
  rows: { type: Array, required: true },
  countries: { type: Object, required: true },
})

const voteType = ref('total')
const direction = ref('received')
const selectedCode = ref('')
const hovered = ref(null)

const voteTypes = computed(() => availableVoteTypes(props.rows))
const matrix = computed(() => buildMatrix(props.rows, voteType.value, props.countries))

const countryOptions = computed(() => {
  const options = new Map()
  for (const entry of [...matrix.value.receivers, ...matrix.value.voters]) {
    options.set(entry.code, entry.country)
  }
  return [...options]
    .map(([code, country]) => ({ code, country }))
    .sort((a, b) => a.country.localeCompare(b.country))
})

const selectedName = computed(
  () => props.countries[selectedCode.value] ?? selectedCode.value,
)

const breakdown = computed(() =>
  direction.value === 'given'
    ? votesGivenBy(matrix.value, selectedCode.value)
    : votesReceivedBy(matrix.value, selectedCode.value),
)
const breakdownMax = computed(() => Math.max(1, ...breakdown.value.map((e) => e.points)))
const breakdownTotal = computed(() => breakdown.value.reduce((sum, e) => sum + e.points, 0))

const readout = computed(() => {
  if (!hovered.value) return 'Hover or tap a cell to see who voted for whom.'

  const { from, to } = hovered.value
  const points = matrix.value.values[from]?.[to]
  const fromName = props.countries[from] ?? from
  const toName = props.countries[to] ?? to

  if (from === to) return `${fromName} cannot vote for itself.`
  return `${fromName} → ${toName}: ${points ?? 0} ${points === 1 ? 'point' : 'points'}`
})

watch(
  () => props.rows,
  (rows) => {
    if (!availableVoteTypes(rows).includes(voteType.value)) voteType.value = 'total'
    selectedCode.value = rows[0]?.code ?? ''
    direction.value = 'received'
    hovered.value = null
  },
  { immediate: true },
)

function selectCountry(code, nextDirection) {
  selectedCode.value = code
  direction.value = nextDirection
}

function onTableHover(event) {
  const cell = event.target.closest('td[data-from]')
  hovered.value = cell ? { from: cell.dataset.from, to: cell.dataset.to } : null
}

function cellStyle(points) {
  if (!points) return undefined

  const strength = points / matrix.value.max
  return {
    backgroundColor: `color-mix(in oklab, #ff78b8 ${Math.round(18 + strength * 82)}%, #1a1138)`,
    color: strength > 0.55 ? '#1a0b22' : '#fff',
  }
}
</script>

<template>
  <section class="panel votes">
    <div class="panel-heading">
      <h2>Who voted for whom</h2>
      <div v-if="voteTypes.length > 1" class="segmented" role="group" aria-label="Vote type">
        <button
          v-for="type in voteTypes"
          :key="type"
          type="button"
          :class="{ 'is-current': voteType === type }"
          :aria-pressed="voteType === type"
          @click="voteType = type"
        >
          {{ VOTE_TYPE_LABELS[type] }}
        </button>
      </div>
    </div>

    <div class="explorer">
      <div class="explorer-controls">
        <label>
          <span>Country</span>
          <select v-model="selectedCode">
            <option v-for="option in countryOptions" :key="option.code" :value="option.code">
              {{ option.country }}
            </option>
          </select>
        </label>
        <div class="segmented" role="group" aria-label="Vote direction">
          <button
            type="button"
            :class="{ 'is-current': direction === 'received' }"
            :aria-pressed="direction === 'received'"
            @click="direction = 'received'"
          >
            Received
          </button>
          <button
            type="button"
            :class="{ 'is-current': direction === 'given' }"
            :aria-pressed="direction === 'given'"
            @click="direction = 'given'"
          >
            Gave
          </button>
        </div>
      </div>

      <p v-if="!breakdown.length" class="note">
        {{ selectedName }} {{ direction === 'given' ? 'gave' : 'received' }} no points in this view.
      </p>

      <template v-else>
        <p class="explorer-summary">
          <strong>{{ selectedName }}</strong>
          {{ direction === 'given' ? 'gave' : 'received' }}
          <strong>{{ breakdownTotal }}</strong> points
          {{ direction === 'given' ? 'to' : 'from' }}
          <strong>{{ breakdown.length }}</strong>
          {{ breakdown.length === 1 ? 'country' : 'countries' }}
        </p>
        <ol class="breakdown">
          <li v-for="entry in breakdown" :key="entry.code">
            <span class="name">{{ entry.country }}</span>
            <span class="track" aria-hidden="true">
              <i :style="{ width: `${(entry.points / breakdownMax) * 100}%` }"></i>
            </span>
            <b>{{ entry.points }}</b>
          </li>
        </ol>
      </template>
    </div>

    <div class="matrix-heading">
      <p class="readout" aria-live="off">{{ readout }}</p>
      <div class="scale" aria-hidden="true">
        <span>1</span>
        <i></i>
        <span>{{ matrix.max }}</span>
      </div>
    </div>

    <div class="matrix-scroll" tabindex="0" aria-label="Voting table, scrollable">
      <table @mouseover="onTableHover" @click="onTableHover" @mouseleave="hovered = null">
        <caption class="sr-only">
          Points given by each voting country, in rows, to each competing country, in columns.
        </caption>
        <colgroup>
          <col />
          <col
            v-for="receiver in matrix.receivers"
            :key="receiver.code"
            :class="{ 'is-hovered': hovered?.to === receiver.code }"
          />
        </colgroup>
        <thead>
          <tr>
            <th class="corner" scope="col">From ↓ To →</th>
            <th v-for="receiver in matrix.receivers" :key="receiver.code" scope="col">
              <button
                type="button"
                :title="receiver.country"
                :aria-label="`Show votes received by ${receiver.country}`"
                :class="{ 'is-selected': direction === 'received' && selectedCode === receiver.code }"
                @click="selectCountry(receiver.code, 'received')"
              >
                {{ receiver.code }}
              </button>
            </th>
          </tr>
        </thead>
        <tbody v-memo="[matrix, selectedCode, direction]">
          <tr v-for="voter in matrix.voters" :key="voter.code">
            <th scope="row">
              <button
                type="button"
                :class="{ 'is-selected': direction === 'given' && selectedCode === voter.code }"
                :aria-label="`Show votes given by ${voter.country}`"
                @click="selectCountry(voter.code, 'given')"
              >
                {{ voter.country }}
              </button>
            </th>
            <td
              v-for="receiver in matrix.receivers"
              :key="receiver.code"
              :data-from="voter.code"
              :data-to="receiver.code"
              :class="{ self: voter.code === receiver.code }"
              :style="cellStyle(matrix.values[voter.code]?.[receiver.code])"
            >
              {{ matrix.values[voter.code]?.[receiver.code] || '' }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.explorer {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--glass-line);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.05);
}

.explorer-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: end;
  justify-content: space-between;
  gap: 10px;
}

.explorer-controls label {
  display: grid;
  flex: 1 1 200px;
  gap: 4px;
  max-width: 320px;
  color: var(--muted);
  font-size: 13px;
}

select {
  min-height: 42px;
  padding: 0 10px;
  border: 1px solid var(--glass-line);
  border-radius: 10px;
  color: #fff;
  background: #21174a;
  font: inherit;
  font-size: 16px;
}

.explorer-summary,
.note {
  color: var(--muted);
}

.explorer-summary strong {
  color: #fff;
  font-weight: 600;
}

.breakdown {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 7px 26px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.breakdown li {
  display: grid;
  grid-template-columns: 124px minmax(0, 1fr) 30px;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.track {
  display: block;
  height: 8px;
  overflow: hidden;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
}

.track i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: #ff78b8;
}

.breakdown b {
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.matrix-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin: 20px 0 8px;
}

.readout {
  min-height: 22px;
  color: #fff;
  font-size: 15px;
  font-weight: 500;
}

.scale {
  display: flex;
  flex: none;
  align-items: center;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
}

.scale i {
  width: 84px;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, color-mix(in oklab, #ff78b8 18%, #1a1138), #ff78b8);
}

.matrix-scroll {
  max-height: min(72vh, 760px);
  overflow: auto;
  border: 1px solid var(--glass-line);
  border-radius: 14px;
  background: rgba(14, 8, 38, 0.72);
}

table {
  border-collapse: separate;
  border-spacing: 2px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

col.is-hovered {
  background: rgba(255, 255, 255, 0.11);
}

th {
  position: sticky;
  z-index: 2;
  padding: 0;
  background: #17102f;
  font-weight: 500;
}

thead th {
  top: 0;
  height: 30px;
  min-width: 30px;
  z-index: 3;
}

tbody th {
  left: 0;
  text-align: left;
}

thead .corner {
  left: 0;
  z-index: 4;
  min-width: 136px;
  padding: 0 6px;
  color: var(--muted);
  font-size: 11px;
  text-align: left;
  white-space: nowrap;
}

th button {
  width: 100%;
  height: 100%;
  min-height: 26px;
  padding: 0 4px;
  border: 0;
  border-radius: 5px;
  color: var(--ink);
  background: transparent;
  font: inherit;
  text-align: inherit;
  white-space: nowrap;
  cursor: pointer;
}

th button:hover {
  background: var(--glass-strong);
}

th button.is-selected {
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
}

td {
  width: 30px;
  min-width: 30px;
  height: 26px;
  padding: 0;
  border-radius: 4px;
  color: #fff;
  font-weight: 600;
  text-align: center;
}

td:hover {
  outline: 2px solid #fff;
  outline-offset: -1px;
}

td.self {
  background: repeating-linear-gradient(
    45deg,
    rgba(255, 255, 255, 0.1) 0 2px,
    transparent 2px 6px
  );
}

tbody tr:hover th {
  background: #2c2058;
}

@media (max-width: 640px) {
  .explorer {
    padding: 10px;
  }

  .explorer-controls label {
    max-width: none;
  }

  .breakdown {
    grid-template-columns: 1fr;
  }

  .breakdown li {
    grid-template-columns: 104px minmax(0, 1fr) 28px;
  }

  .matrix-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 4px;
  }

  thead .corner {
    min-width: 96px;
  }

  tbody th button {
    max-width: 104px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
}
</style>
