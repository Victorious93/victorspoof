package com.victorious.spoof.bridge

import kotlinx.serialization.Serializable

@Serializable
enum class OpKind { read, action, destructive }

@Serializable
data class OpDescriptor(val name: String, val kind: OpKind, val remoteAllowed: Boolean, val requiresConfirm: Boolean)

/**
 * The RECEIVER's own classification of every spoof.* op. Envelope hints (destructive, trigger.*) are
 * never consulted: a sender claiming destructive=false cannot change kind/remoteAllowed/requiresConfirm.
 *
 * HARD RULE: no op that places, controls or ends a call is remote-allowed.
 */
object OpRegistry {
    val ops: List<OpDescriptor> = listOf(
        // Fleet STATUS-TRAY op (droidcommand-AI docs/specs/STATUS_TRAY.md).
        OpDescriptor("node.status.get", OpKind.read, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor("spoof.status.get", OpKind.read, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor("spoof.pool.list", OpKind.read, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor("spoof.usage.get", OpKind.read, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor("spoof.ui.open", OpKind.action, remoteAllowed = true, requiresConfirm = false),
        OpDescriptor("spoof.call.start", OpKind.action, remoteAllowed = false, requiresConfirm = true),
        OpDescriptor("spoof.call.end", OpKind.action, remoteAllowed = false, requiresConfirm = true),
        OpDescriptor("spoof.recording.delete", OpKind.destructive, remoteAllowed = false, requiresConfirm = true),
    )
    private val byName = ops.associateBy { it.name }
    fun find(name: String): OpDescriptor? = byName[name]

    init {
        // Fail fast if anyone ever edits the table to allow remote calls.
        ops.filter { it.name.startsWith("spoof.call") }.forEach {
            check(it.kind == OpKind.action && !it.remoteAllowed && it.requiresConfirm) { "call op ${it.name} must be local-only" }
        }
        ops.filter { it.kind == OpKind.destructive }.forEach { check(!it.remoteAllowed && it.requiresConfirm) }
    }
}
