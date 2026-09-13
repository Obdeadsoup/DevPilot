<template>
  <div class="auth-shell">
    <aside class="auth-shell__context" aria-label="DevPilot product context">
      <div class="auth-brand"><span>DP</span><strong>DevPilot</strong></div>
      <div class="auth-copy">
        <p class="eyebrow">ENGINEERING CONTEXT</p>
        <h1>把协作事实放在同一条工作流里。</h1>
        <p>从 Workspace 到 GitHub 活动、任务和 Agent Run，关键状态始终由业务系统验证。</p>
      </div>
      <ol class="context-rail">
        <li><span>01</span><div><strong>组织协作</strong><small>Workspace · Project · RBAC</small></div></li>
        <li><span>02</span><div><strong>连接研发活动</strong><small>Repository · Task · Activity</small></div></li>
        <li><span>03</span><div><strong>受控 Agent 执行</strong><small>Tool lifecycle · Audit · SSE</small></div></li>
      </ol>
    </aside>

    <main class="auth-shell__main">
      <section class="auth-panel" aria-labelledby="auth-title">
        <p class="eyebrow"><slot name="eyebrow" /></p>
        <h2 id="auth-title"><slot name="title" /></h2>
        <p class="auth-panel__description"><slot name="description" /></p>
        <slot />
        <footer><slot name="footer" /></footer>
      </section>
    </main>
  </div>
</template>

<style scoped>
.auth-shell { min-height: 100vh; display: grid; grid-template-columns: minmax(360px, 0.9fr) minmax(440px, 1.1fr); background: var(--color-surface); }
.auth-shell__context { display: flex; flex-direction: column; justify-content: space-between; min-height: 100vh; padding: clamp(32px, 6vw, 72px); color: #fff; background: var(--color-sidebar); }
.auth-brand { display: flex; align-items: center; gap: var(--space-3); font-size: var(--font-size-lg); }
.auth-brand span { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid rgba(255,255,255,.25); border-radius: var(--radius-sm); background: var(--color-accent); font: 700 12px/1 var(--font-mono); }
.auth-copy { max-width: 520px; margin-block: var(--space-10); }
.eyebrow { margin: 0 0 var(--space-4); color: #8195ff; font: 700 var(--font-size-xs)/1 var(--font-mono); letter-spacing: .14em; }
.auth-copy h1 { max-width: 460px; margin: 0; font-size: clamp(32px, 4vw, 52px); line-height: 1.12; letter-spacing: -.035em; }
.auth-copy > p:last-child { max-width: 480px; margin: var(--space-6) 0 0; color: #b4becd; line-height: 1.8; }
.context-rail { display: grid; gap: var(--space-3); margin: 0; padding: 0; list-style: none; }
.context-rail li { display: flex; align-items: center; gap: var(--space-4); padding-top: var(--space-3); border-top: 1px solid rgba(255,255,255,.11); }
.context-rail li > span { color: #71809a; font: var(--font-size-xs)/1 var(--font-mono); }
.context-rail strong, .context-rail small { display: block; }
.context-rail strong { font-size: var(--font-size-sm); }
.context-rail small { margin-top: var(--space-1); color: #8f9bad; font-size: 11px; }
.auth-shell__main { display: grid; place-items: center; padding: clamp(24px, 6vw, 80px); background: var(--color-surface-subtle); }
.auth-panel { width: min(100%, 460px); padding: clamp(24px, 4vw, 40px); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-lg); box-shadow: 0 16px 40px rgba(29, 38, 53, .08); }
.auth-panel > .eyebrow { color: var(--color-accent); }
.auth-panel h2 { margin: 0; font-size: var(--font-size-xl); letter-spacing: -.02em; }
.auth-panel__description { margin: var(--space-2) 0 var(--space-6); color: var(--color-text-muted); line-height: 1.6; }
.auth-panel footer { margin-top: var(--space-6); padding-top: var(--space-5); border-top: 1px solid var(--color-border); color: var(--color-text-muted); text-align: center; font-size: var(--font-size-sm); }
@media (max-width: 860px) {
  .auth-shell { grid-template-columns: 1fr; }
  .auth-shell__context { min-height: auto; padding: var(--space-5); }
  .auth-copy, .context-rail { display: none; }
  .auth-shell__main { min-height: calc(100vh - 76px); padding: var(--space-4); }
  .auth-panel { padding: var(--space-6); }
}
</style>
