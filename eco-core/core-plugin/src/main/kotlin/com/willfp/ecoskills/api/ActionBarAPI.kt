@file:JvmName("EcoSkillsActionBarAPI")
package com.willfp.ecoskills.api

import com.willfp.ecoskills.actionbar.ActionBarHandler
import org.bukkit.entity.Player

/** MAIN only. 50..10000 ms; returns false for offline players or disabled provider.
 * Restores the current native bar, honoring settings and newer external pauses.
 * Does not load profiles itself; normal native rendering retains its existing behavior.
 */
fun showTemporaryActionBar(player: Player, message: String, durationMillis: Long): Boolean =
    ActionBarHandler.showTemporaryActionBar(player, message, durationMillis)
