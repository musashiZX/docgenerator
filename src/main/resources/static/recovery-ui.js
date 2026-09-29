/**
 * Recovery UI module — commits, checkpoints, restore.
 *
 * Startup integration:
 *   <script src="/recovery-ui.js"></script>
 *   RecoveryUI.mount(document.getElementById('history-root'), {
 *     getDocName: () => yourApp.currentDocumentName,
 *     apiBase: '/api',                    // optional, default '/api'
 *     onRestored: async () => { ... },    // refresh editor after restore
 *     onCommitted: async () => { ... },
 *     log: (kind, message) => { ... },    // optional activity log hook
 *   });
 */
"use strict";

window.RecoveryUI = (function () {
  const esc = (s) =>
    String(s ?? "").replace(/[&<>"']/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function fmtTime(iso) {
    if (!iso) return "";
    try {
      return new Date(iso).toLocaleString();
    } catch {
      return iso;
    }
  }

  function shortId(id) {
    if (!id) return "";
    return id.length > 10 ? id.slice(0, 10) + "…" : id;
  }

  async function apiJson(url, options) {
    const res = await fetch(url, options);
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      const msg = body.error || body.message || res.statusText;
      throw new Error(msg);
    }
    return body;
  }

  async function loadHistory(docName, apiBase) {
    const enc = encodeURIComponent(docName);
    const [commitsBody, checkpointsBody] = await Promise.all([
      apiJson(`${apiBase}/documents/${enc}/commits`),
      apiJson(`${apiBase}/documents/${enc}/checkpoints`),
    ]);
    const commits = (commitsBody.commits || []).map((c) => ({
      type: "commit",
      id: c.commit_id,
      label: c.message || "Commit",
      createdAt: c.created_at,
      parentId: c.parent_commit_id,
      isHead: c.commit_id === commitsBody.head_commit_id,
      raw: c,
    }));
    const checkpoints = (checkpointsBody.checkpoints || []).map((c) => ({
      type: "checkpoint",
      id: c.checkpoint_id,
      label: c.change_summary || "Checkpoint",
      createdAt: c.created_at,
      proposalId: c.proposal_id,
      isHead: false,
      raw: c,
    }));
    const timeline = [...commits, ...checkpoints].sort(
      (a, b) => new Date(b.createdAt) - new Date(a.createdAt));
    return {
      docName,
      headCommitId: commitsBody.head_commit_id || null,
      commits,
      checkpoints,
      timeline,
    };
  }

  function renderTimeline(mount, data, handlers) {
    const { docName, headCommitId, timeline } = data;
    const enc = encodeURIComponent(docName);

    let html =
      `<div class="recovery-panel">` +
      `<div class="recovery-head">` +
      `<div class="recovery-head-meta">` +
      `<span class="recovery-label">HEAD</span>` +
      (headCommitId
        ? `<code class="tid recovery-head-id" title="${esc(headCommitId)}">${esc(shortId(headCommitId))}</code>`
        : `<span class="muted">none</span>`) +
      `<span class="recovery-stat">${timeline.length} snapshot(s)</span>` +
      `</div>` +
      `<div class="recovery-head-actions">` +
      `<button type="button" class="btn ghost sm" data-act="refresh" title="Refresh history">&#8635;</button>` +
      `</div></div>`;

    html +=
      `<div class="recovery-commit-box">` +
      `<input type="text" class="recovery-commit-input" data-commit-msg ` +
      `placeholder="Commit message (e.g. Baseline, v1.1)" maxlength="200">` +
      `<button type="button" class="btn primary sm" data-act="commit">Commit</button>` +
      `</div>` +
      `<p class="hint recovery-hint">` +
      `<b>Commit</b> saves a named milestone you can download or restore. ` +
      `<b>Checkpoints</b> are auto-saved before each <em>Approve &amp; save</em>.` +
      `</p>`;

    if (timeline.length === 0) {
      html += `<div class="recovery-empty">No commits or checkpoints yet. ` +
        `Open the index (loads initial commit) or approve a proposal.</div>`;
    } else {
      html += `<ul class="recovery-timeline">`;
      for (const entry of timeline) {
        const isCommit = entry.type === "commit";
        const badge = isCommit
          ? `<span class="recovery-badge commit">commit</span>`
          : `<span class="recovery-badge checkpoint">checkpoint</span>`;
        const headMark = entry.isHead ? `<span class="recovery-badge head">HEAD</span>` : "";
        const proposal = entry.proposalId
          ? `<span class="muted">proposal ${esc(shortId(entry.proposalId))}</span>`
          : "";
        html +=
          `<li class="recovery-entry ${entry.type}${entry.isHead ? " is-head" : ""}">` +
          `<div class="recovery-entry-main">` +
          `<div class="recovery-entry-title">${badge}${headMark}` +
          `<strong>${esc(entry.label)}</strong></div>` +
          `<div class="recovery-entry-meta">` +
          `<code class="tid" title="${esc(entry.id)}">${esc(shortId(entry.id))}</code> · ` +
          `${esc(fmtTime(entry.createdAt))} ${proposal}` +
          `</div></div>` +
          `<div class="recovery-entry-actions">`;
        if (isCommit) {
          html +=
            `<a class="btn ghost sm" href="${handlers.apiBase}/documents/${enc}/commits/${esc(entry.id)}/download" ` +
            `download title="Download this commit">&#8681;</a>`;
          html +=
            `<button type="button" class="btn ghost sm" data-restore-commit="${esc(entry.id)}">Restore</button>`;
        } else {
          html +=
            `<button type="button" class="btn ghost sm" data-restore-checkpoint="${esc(entry.id)}">Restore</button>`;
        }
        if (!entry.isHead) {
          html +=
            `<button type="button" class="btn ghost sm" data-diff-toggle="${esc(entry.type)}:${esc(entry.id)}" ` +
            `title="Show what changed between this and the current HEAD commit">Trace changes</button>`;
        }
        html += `</div></li>`;
        html += `<li class="recovery-diff-slot" data-diff-slot="${esc(entry.type)}:${esc(entry.id)}" hidden></li>`;
      }
      html += `</ul>`;
    }
    html += `</div>`;
    mount.innerHTML = html;

    mount.querySelector("[data-act=refresh]")?.addEventListener("click", () => handlers.refresh());
    mount.querySelector("[data-act=commit]")?.addEventListener("click", async () => {
      const input = mount.querySelector("[data-commit-msg]");
      const message = input?.value?.trim();
      if (!message) {
        handlers.log?.("err", "Enter a commit message.");
        return;
      }
      try {
        await apiJson(`${handlers.apiBase}/documents/${enc}/commits`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ message }),
        });
        if (input) input.value = "";
        handlers.log?.("ok", `Committed <b>${esc(message)}</b>.`);
        await handlers.onCommitted?.();
        await handlers.refresh();
      } catch (e) {
        handlers.log?.("err", `Commit failed: ${esc(e.message)}`);
      }
    });

    mount.querySelectorAll("[data-restore-commit]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.dataset.restoreCommit;
        if (!confirm(`Restore commit ${id}?\n\nPending proposals will be rejected. Working document will be overwritten.`)) {
          return;
        }
        try {
          const body = await apiJson(
            `${handlers.apiBase}/documents/${enc}/restore/commit/${encodeURIComponent(id)}`,
            { method: "POST" });
          handlers.log?.("ok",
            `Restored commit — ${body.rejected_proposals || 0} pending proposal(s) rejected.`);
          await handlers.onRestored?.();
          await handlers.refresh();
        } catch (e) {
          handlers.log?.("err", `Restore failed: ${esc(e.message)}`);
        }
      });
    });

    mount.querySelectorAll("[data-restore-checkpoint]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.dataset.restoreCheckpoint;
        if (!confirm(`Restore checkpoint ${id}?\n\nPending proposals will be rejected. HEAD commit is unchanged.`)) {
          return;
        }
        try {
          const body = await apiJson(
            `${handlers.apiBase}/documents/${enc}/restore/checkpoint/${encodeURIComponent(id)}`,
            { method: "POST" });
          handlers.log?.("ok",
            `Restored checkpoint — ${body.rejected_proposals || 0} pending proposal(s) rejected.`);
          await handlers.onRestored?.();
          await handlers.refresh();
        } catch (e) {
          handlers.log?.("err", `Restore failed: ${esc(e.message)}`);
        }
      });
    });

    mount.querySelectorAll("[data-diff-toggle]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const key = btn.dataset.diffToggle;
        const sep = key.indexOf(":");
        const type = key.slice(0, sep);
        const id = key.slice(sep + 1);
        const slot = mount.querySelector(`[data-diff-slot="${CSS.escape(key)}"]`);
        if (!slot) return;
        if (!slot.hidden) {
          slot.hidden = true;
          slot.innerHTML = "";
          return;
        }
        slot.hidden = false;
        slot.innerHTML = `<div class="recovery-diff-loading">Comparing against HEAD…</div>`;
        try {
          const headId = data.headCommitId;
          if (!headId) throw new Error("No HEAD commit to compare against.");
          const params = new URLSearchParams({
            fromType: type, from: id, toType: "commit", to: headId,
          });
          const diff = await apiJson(`${handlers.apiBase}/documents/${enc}/diff?${params}`);
          slot.innerHTML = renderDiff(diff);
        } catch (e) {
          slot.innerHTML = `<div class="recovery-diff-error">Diff failed: ${esc(e.message)}</div>`;
        }
      });
    });
  }

  function renderDiff(diff) {
    const rows = (list, label, cls) =>
      list.map((b) =>
        `<div class="recovery-diff-row ${cls}">` +
        `<span class="recovery-diff-badge ${cls}">${label}</span>` +
        `<code class="tid">${esc(b.target_id)}</code>` +
        (b.before_text != null
          ? `<span class="recovery-diff-before">${esc(truncate(b.before_text))}</span>`
          : "") +
        (b.before_text != null && b.after_text != null ? `<span class="recovery-diff-arrow">&#8594;</span>` : "") +
        (b.after_text != null
          ? `<span class="recovery-diff-after">${esc(truncate(b.after_text))}</span>`
          : "") +
        `</div>`
      ).join("");

    const total = diff.added.length + diff.removed.length + diff.modified.length;
    if (total === 0) {
      return `<div class="recovery-diff-panel"><div class="recovery-empty">No content differences vs HEAD (${diff.unchanged_count} block(s) unchanged).</div></div>`;
    }
    return (
      `<div class="recovery-diff-panel">` +
      `<div class="recovery-diff-summary">` +
      `${diff.modified.length} modified · ${diff.added.length} added · ${diff.removed.length} removed · ${diff.unchanged_count} unchanged` +
      `</div>` +
      rows(diff.modified, "modified", "modified") +
      rows(diff.added, "added", "added") +
      rows(diff.removed, "removed", "removed") +
      `</div>`
    );
  }

  function truncate(s, n = 120) {
    if (!s) return "";
    return s.length > n ? s.slice(0, n) + "…" : s;
  }

  /**
   * Mount recovery UI into container. Returns { refresh, destroy }.
   */
  function mount(container, options) {
    if (!container) throw new Error("RecoveryUI.mount: container required");
    const apiBase = (options.apiBase || "/api").replace(/\/$/, "");
    const getDocName = options.getDocName || (() => null);
    let destroyed = false;

    async function refresh() {
      if (destroyed) return;
      const docName = getDocName();
      if (!docName) {
        container.innerHTML =
          `<div class="recovery-empty">Select a document to view commits and checkpoints.</div>`;
        return;
      }
      container.innerHTML = `<div class="recovery-loading">Loading history…</div>`;
      try {
        const data = await loadHistory(docName, apiBase);
        renderTimeline(container, data, {
          apiBase,
          refresh,
          log: options.log,
          onRestored: options.onRestored,
          onCommitted: options.onCommitted,
        });
        options.onLoaded?.(data);
      } catch (e) {
        container.innerHTML =
          `<div class="recovery-empty recovery-error">Could not load history: ${esc(e.message)}</div>`;
        options.log?.("err", `History load failed: ${esc(e.message)}`);
      }
    }

    refresh();
    return {
      refresh,
      destroy() { destroyed = true; container.innerHTML = ""; },
    };
  }

  return { mount, loadHistory, esc };
})();
