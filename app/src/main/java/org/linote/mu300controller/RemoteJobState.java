package org.linote.mu300controller;

/** A completed or interrupted helper job is not active work and must not lock a fresh transfer. */
final class RemoteJobState {
    private RemoteJobState() { }
    static boolean blocksTransfer(String kind, String state, int exitCode) { return "running".equals(state); }
}
