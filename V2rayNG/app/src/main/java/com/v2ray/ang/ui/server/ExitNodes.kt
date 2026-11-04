package com.v2ray.ang.ui.server

/**
 * The labels a pick list shows for [names], in their order, as the subscriptions of the policy group editor
 * show theirs: each name, made unique by a number after it, which [numbered] writes, where it is one of
 * [reserved], the labels of the list's own entries, or came up before. The list hands back the label picked, so no two
 * may be alike.
 */
internal fun distinctLabels(names: List<String>, reserved: Set<String>, numbered: (String, Int) -> String): List<String> {
    val taken = reserved.toMutableSet()
    return names.map { name ->
        var label = name
        var number = 2
        while (label in taken) label = numbered(name, number++)
        taken += label
        label
    }
}
