<template>
  <main class="route-problem" aria-labelledby="route-problem-title">
    <p class="route-problem__code">{{ statusCode }}</p>
    <h1 id="route-problem-title">{{ title }}</h1>
    <p>{{ description }}</p>
    <p v-if="sourcePath" class="route-problem__path">{{ sourcePath }}</p>
    <el-space wrap>
      <el-button type="primary" @click="$router.push('/workspaces')">返回工作区</el-button>
      <el-button @click="$router.back()">返回上一页</el-button>
    </el-space>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

defineProps<{ statusCode: string; title: string; description: string }>()

const route = useRoute()
const sourcePath = computed(() => typeof route.query.from === 'string' ? route.query.from : '')
</script>

<style scoped>
.route-problem {
  max-width: 640px;
  margin: 12vh auto 0;
  text-align: center;
}

.route-problem__code {
  margin: 0 0 var(--space-3);
  color: var(--color-accent);
  font: 700 var(--font-size-xs) / 1 var(--font-mono);
  letter-spacing: 0.16em;
}

h1 { margin: 0 0 var(--space-3); font-size: var(--font-size-2xl); }
p { color: var(--color-text-muted); line-height: 1.7; }
.route-problem__path { margin: var(--space-4) 0; font-family: var(--font-mono); word-break: break-all; }
</style>
