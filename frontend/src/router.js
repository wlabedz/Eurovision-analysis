import { createRouter, createWebHashHistory } from 'vue-router'
import ContestDetailsView from './views/ContestDetailsView.vue'
import CountrySummaryView from './views/CountrySummaryView.vue'
import ContestsView from './views/ContestsView.vue'
import HostCountriesView from './views/HostCountriesView.vue'
import VotingGraphView from './views/VotingGraphView.vue'

export default createRouter({
  // Hash URLs keep page refreshes working on GitHub Pages.
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'contests', component: ContestsView },
    { path: '/host-countries', name: 'host-countries', component: HostCountriesView },
    { path: '/voting-graph', name: 'voting-graph', component: VotingGraphView },
    { path: '/country-summary', name: 'country-summary', component: CountrySummaryView },
    {
      path: '/contests/:year(\\d{4})',
      name: 'contest',
      component: ContestDetailsView,
      props: true,
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior(to, from, savedPosition) {
    // Changing a tab or round only updates the query, so keep the scroll position.
    if (to.path === from.path) return false
    return { ...(savedPosition ?? { top: 0 }), behavior: 'instant' }
  },
})
