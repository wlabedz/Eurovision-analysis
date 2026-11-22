<script setup>
import { computed, onMounted, ref } from 'vue'

const api = import.meta.env.VITE_API_URL ?? 'http://localhost:8080'

const contests = ref([])
const loading = ref(true)
const error = ref('')
const failedLogos = ref(new Set())

const decades = computed(() => {
  const groups = new Map()
  const sorted = [...contests.value].sort((a, b) => b.year - a.year)

  for (const contest of sorted) {
    const decade = Math.floor(contest.year / 10) * 10
    if (!groups.has(decade)) groups.set(decade, [])
    groups.get(decade).push(contest)
  }

  return [...groups].map(([decade, items]) => ({ decade, items }))
})

onMounted(async () => {
  try {
    const response = await fetch(`${api}/api/contests`)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    contests.value = await response.json()
  } catch {
    error.value = 'No data available. Please try again later.'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <main class="contests">
    <h1>Eurovision</h1>

    <p v-if="loading" role="status">Loading contests...</p>
    <p v-else-if="error" role="alert">{{ error }}</p>

    <template v-else>
      <nav class="toc" aria-label="Spis treści">
        <a v-for="group in decades" :key="group.decade" :href="`#d${group.decade}`">
          {{ group.decade }}s
        </a>
      </nav>

      <section
        v-for="group in decades"
        :id="`d${group.decade}`"
        :key="group.decade"
        class="decade"
      >
        <h2>{{ group.decade }}s</h2>

        <ul class="grid">
          <li v-for="contest in group.items" :key="contest.year" class="card">
            <div class="logo">
              <img
                v-if="contest.logoUrl && !failedLogos.has(contest.year)"
                :src="contest.logoUrl"
                :alt="`Logo ${contest.year}`"
                loading="lazy"
                @error="failedLogos.add(contest.year)"
              />
              <span v-else class="fallback">{{ contest.year }}</span>
            </div>
            <strong>{{ contest.year }}</strong>
            <span>{{ contest.city }}, {{ contest.country }}</span>
            <small v-if="contest.slogan">{{ contest.slogan }}</small>
          </li>
        </ul>
      </section>
    </template>
  </main>
</template>

<style scoped>
.contests {
  padding: 16px 24px 48px;
}

.toc {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  padding: 12px 0;
  background: var(--bg);
  border-bottom: 1px solid var(--border);
}

.toc a {
  padding: 4px 12px;
  border-radius: 6px;
  color: var(--text-h);
  background: var(--social-bg);
  font-size: 15px;
  text-decoration: none;
}

.toc a:hover,
.toc a:focus-visible {
  color: var(--accent);
  background: var(--accent-bg);
}

.decade {
  padding-top: 32px;
  scroll-margin-top: 56px;
  text-align: left;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 16px;
  margin: 16px 0 0;
  padding: 0;
  list-style: none;
}

.card {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 15px;
  line-height: 1.3;
}

.card strong {
  color: var(--text-h);
  font-size: 18px;
}

.logo {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 120px;
  margin-bottom: 8px;
  padding: 8px;
  box-sizing: border-box;
  border-radius: 6px;
  background: #fff;
}

.logo img {
  display: block;
  width: 100%;
  height: 100%;
  min-width: 0;
  object-fit: contain;
}

.fallback {
  color: #6b6375;
  font-size: 24px;
}

@media (max-width: 480px) {
  .contests {
    padding-inline: 16px;
  }

  .grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
}
</style>
