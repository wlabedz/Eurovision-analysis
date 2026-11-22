<script setup>
import { computed } from 'vue'

const props = defineProps({
  rows: { type: Array, required: true },
})

const hasSplit = computed(() =>
  props.rows.some((row) => row.jury !== null && row.televote !== null),
)
const hasPoints = computed(() => props.rows.some((row) => row.total !== null))
const maxTotal = computed(() => Math.max(1, ...props.rows.map((row) => row.total ?? 0)))

const width = (points) => `${((points ?? 0) / maxTotal.value) * 100}%`
</script>

<template>
  <section class="panel results">
    <div class="panel-heading">
      <h2>Results</h2>
      <div v-if="hasSplit" class="legend">
        <span><i class="swatch jury"></i>Jury</span>
        <span><i class="swatch televote"></i>Televote</span>
      </div>
    </div>

    <p v-if="!hasPoints" class="note">
      Scores from this contest were never published. Only the winner is known.
    </p>

    <ol class="list">
      <li
        v-for="row in rows"
        :key="row.id"
        class="row"
        :class="{ 'is-winner': row.place === 1 }"
      >
        <span class="place">{{ row.place ?? '–' }}</span>

        <div class="main">
          <div class="title">
            <strong>{{ row.country }}</strong>
            <em v-if="row.place === 1">Winner</em>
            <em v-if="row.qualified" class="badge-soft">Qualified</em>
            <em v-if="row.disqualified" class="badge-alert">Disqualified</em>
          </div>
          <span class="song">{{ row.artist }} · {{ row.song }}</span>

          <div v-if="hasPoints" class="bar" aria-hidden="true">
            <template v-if="row.jury !== null && row.televote !== null">
              <i class="jury" :style="{ width: width(row.jury) }"></i>
              <i class="televote" :style="{ width: width(row.televote) }"></i>
            </template>
            <i v-else class="single" :style="{ width: width(row.total) }"></i>
          </div>

          <span v-if="row.jury !== null && row.televote !== null" class="split">
            Jury {{ row.jury }} · Televote {{ row.televote }}
          </span>
        </div>

        <span class="running" :title="`Running order ${row.running}`">
          <small>Draw</small>{{ row.running ?? '–' }}
        </span>
        <span v-if="hasPoints" class="points">
          <b>{{ row.total ?? '–' }}</b>
          <small>pts</small>
        </span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.legend {
  display: flex;
  gap: 16px;
  color: var(--muted);
  font-size: 13px;
}

.legend span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.swatch {
  width: 12px;
  height: 12px;
  border-radius: 3px;
}

.jury {
  background: var(--jury);
}

.televote {
  background: var(--televote);
}

.note {
  margin-bottom: 14px;
  color: var(--muted);
}

.list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) 52px 72px;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border: 1px solid var(--glass-line);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.05);
}

.row.is-winner {
  border-color: rgba(255, 255, 255, 0.46);
  background: linear-gradient(110deg, rgba(255, 46, 147, 0.28), rgba(123, 60, 255, 0.18));
}

.place {
  color: var(--ink);
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
}

.title strong {
  font-size: 17px;
  font-weight: 600;
}

.title em {
  padding: 1px 8px;
  border-radius: 999px;
  color: #fff;
  background: linear-gradient(120deg, var(--pink), var(--violet));
  font-size: 12px;
  font-style: normal;
  font-weight: 600;
}

.title em.badge-soft {
  color: var(--muted);
  background: var(--glass-strong);
}

.title em.badge-alert {
  color: #fff;
  background: rgba(230, 103, 103, 0.42);
}

.song {
  overflow: hidden;
  color: var(--muted);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar {
  display: flex;
  gap: 2px;
  height: 7px;
  margin-top: 4px;
  overflow: hidden;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
}

.bar i {
  display: block;
  height: 100%;
  border-radius: 2px;
}

.bar .single {
  background: linear-gradient(90deg, var(--pink), var(--blue));
}

.split {
  color: var(--muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.running {
  display: grid;
  color: var(--ink);
  font-size: 15px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.running small,
.points small {
  color: var(--muted);
  font-size: 11px;
}

.points {
  display: grid;
  text-align: right;
}

.points b {
  font-size: 22px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

@media (max-width: 640px) {
  .row {
    grid-template-columns: 28px minmax(0, 1fr) 54px;
    gap: 8px;
    padding: 10px;
  }

  .running {
    display: none;
  }
}
</style>
