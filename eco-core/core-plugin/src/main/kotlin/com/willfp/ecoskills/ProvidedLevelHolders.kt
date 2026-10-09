package com.willfp.ecoskills

/** Select and wrap level holders in one pass; no player or registry state is cached. */
internal inline fun <T, H : Any, R : Any> collectProvidedLevelHolders(
    entries: Iterable<T>,
    levelHolder: (T) -> H?,
    provide: (H) -> R
): List<R> = entries.mapNotNull { entry -> levelHolder(entry)?.let(provide) }
