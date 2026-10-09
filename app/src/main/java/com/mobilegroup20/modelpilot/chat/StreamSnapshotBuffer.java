package com.mobilegroup20.modelpilot.chat;

/* Adaptation of Pydantic AI chat_app.py's debounced cumulative output and
 * chat_app.ts's single-message snapshot updates. See docs/PYDANTIC_REUSE.md.
 * Copyright (c) Pydantic Services Inc. 2024 to present. MIT license in
 * third_party/pydantic-ai-chat/LICENSE. Android adaptation: ModelPilot, 2026.
 */

/** Thread-safe cumulative text, with at most one pending UI snapshot per answer.
 * Finishing/cancelling invalidates queued snapshots so they cannot revive a completed stream.
 */
public final class StreamSnapshotBuffer {
    public static final long UI_INTERVAL_MS = 32;
    private final StringBuilder text = new StringBuilder();
    private boolean pending;
    private boolean closed;

    /** True means the caller should schedule one render, not one render per network chunk. */
    public synchronized boolean append(String delta) {
        if (closed || delta == null || delta.isEmpty()) return false;
        text.append(delta);
        if (pending) return false;
        pending = true;
        return true;
    }
    public synchronized String poll() {
        if (closed || !pending) return null;
        pending = false;
        return text.toString();
    }
    /** Terminal content for persistence; null means already finished or cancelled. */
    public synchronized String finish() {
        if (closed) return null;
        closed = true;
        pending = false;
        return text.toString();
    }
    public synchronized void discard() {
        closed = true;
        pending = false;
    }
}
