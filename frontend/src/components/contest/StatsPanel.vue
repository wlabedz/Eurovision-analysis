<script setup>
import { computed } from 'vue'
import {
  buildHighlights,
  buildMatrix,
  juryTelevoteGaps,
  topExchanges,
} from '../../utils/contestStats'

const props = defineProps({
  rows: { type: Array, required: true },
  countries: { type: Object, required: true },
})

const matrix = computed(() => buildMatrix(props.rows, 'total', props.countries))
const highlights = computed(() => buildHighlights(props.rows, matrix.value))
const exchanges = computed(() => topExchanges(matrix.value))
const exchangeMax = computed(() => Math.max(1, ...exchanges.value.map((pair) => pair.sum)))
const gaps = computed(() => juryTelevoteGaps(props.rows))
const gapMax = computed(() => Math.max(1, ...gaps.value.map((row) => Math.abs(row.gap))))

const gapStyle = (gap) => ({ width: `${(Math.abs(gap) / gapMax.value) * 50}%` })
const signed = (value) => (value > 0 ? `+${value}` : `${value}`)
</script>

<template>
  <section class="panel stats">
    <div class="panel-heading">
      <h2>Round statistics</h2>
    </div>

    <dl class="tiles">
      <div v-for="item in highlights" :key="item.label" class="tile">
        <dt>{{ item.label }}</dt>
        <dd>{{ item.value }}</dd>
        <p>{{ item.detail }}</p>
      </div>
    </dl>

    <div class="charts">
      <article v-if="exchanges.length" class="chart">
        <h3>Strongest vote exchanges</h3>
        <p class="caption">Points two competing countries gave each other</p>
        <ol class="pairs">
          <li v-for="pair in exchanges" :key="`${pair.a.code}-${pair.b.code}`">
            <span class="pair-name">{{ pair.a.country }} ↔ {{ pair.b.country }}</span>
            <span class="track" aria-hidden="true">
              <i :style="{ width: `${(pair.sum / exchangeMax) * 100}%` }"></i>
            </span>
            <b>{{ pair.sum }}</b>
            <small>{{ pair.aToB }} + {{ pair.bToA }}</small>
          </li>
        </ol>
      </article>

      <article v-if="gaps.length" class="chart">
        <h3>Jury vs televote</h3>
        <p class="caption">Difference in points for each country</p>
        <div class="legend">
          <span><i class="swatch televote"></i>Televote favourite</span>
          <span><i class="swatch jury"></i>Jury favourite</span>
        </div>
        <ol class="gaps">
          <li
            v-for="row in gaps"
            :key="row.code"
            :title="`${row.country}: jury ${row.jury}, televote ${row.televote}`"
          >
            <span class="pair-name">{{ row.country }}</span>
            <span class="diverging" aria-hidden="true">
              <i v-if="row.gap > 0" class="jury right" :style="gapStyle(row.gap)"></i>
              <i v-else-if="row.gap < 0" class="televote left" :style="gapStyle(row.gap)"></i>
            </span>
            <b>{{ signed(row.gap) }}</b>
          </li>
        </ol>
      </article>
    </div>
  </section>
</template>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(190px, 1fr));
  gap: 12px;
  margin: 0;
}

.tile {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--glass-line);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.06);
}

.tile dt {
  color: var(--muted);
  font-size: 13px;
}

.tile dd {
  margin: 2px 0;
  font-size: 34px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
}

.tile p {
  color: var(--muted);
  font-size: 14px;
  line-height: 1.3;
}

.charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 400px), 1fr));
  gap: 14px;
  margin-top: 14px;
}

.chart {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--glass-line);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.05);
}

h3 {
  margin: 0;
  color: #fff;
  font-size: 18px;
  font-weight: 600;
}

.caption {
  margin-bottom: 12px;
  color: var(--muted);
  font-size: 13px;
}

.legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-bottom: 10px;
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

ol {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.pairs li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 34px 50px;
  align-items: center;
  gap: 3px 8px;
  font-size: 14px;
}

.pair-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pairs .track {
  grid-column: 1;
  grid-row: 2;
}

.pairs b {
  grid-column: 2;
  grid-row: 1 / 3;
  font-size: 18px;
  text-align: right;
}

.pairs small {
  grid-column: 3;
  grid-row: 1 / 3;
  color: var(--muted);
  font-size: 12px;
  text-align: right;
  white-space: nowrap;
}

.track {
  display: block;
  height: 7px;
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

.gaps li {
  display: grid;
  grid-template-columns: 116px minmax(0, 1fr) 46px;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.gaps b {
  font-variant-numeric: tabular-nums;
  text-align: right;
}

.diverging {
  position: relative;
  display: block;
  height: 12px;
}

.diverging::before {
  position: absolute;
  top: -3px;
  bottom: -3px;
  left: 50%;
  width: 1px;
  background: rgba(255, 255, 255, 0.34);
  content: '';
}

.diverging i {
  position: absolute;
  top: 1px;
  height: 10px;
}

.diverging .right {
  left: calc(50% + 1px);
  border-radius: 0 4px 4px 0;
}

.diverging .left {
  right: calc(50% + 1px);
  border-radius: 4px 0 0 4px;
}

@media (max-width: 640px) {
  .tiles {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .tile {
    padding: 10px;
  }

  .tile dd {
    font-size: 27px;
  }

  .chart {
    padding: 12px;
  }

  .gaps li {
    grid-template-columns: 92px minmax(0, 1fr) 42px;
  }
}
</style>
