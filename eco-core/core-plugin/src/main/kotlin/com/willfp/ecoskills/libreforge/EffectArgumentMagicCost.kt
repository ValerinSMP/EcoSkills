package com.willfp.ecoskills.libreforge

import com.willfp.ecoskills.magic.MagicType
import com.willfp.ecoskills.magic.magic
import com.willfp.libreforge.ConfigurableElement
import com.willfp.libreforge.NoCompileData
import com.willfp.libreforge.effects.arguments.EffectArgument
import com.willfp.libreforge.get
import com.willfp.libreforge.triggers.DispatchedTrigger
import org.bukkit.entity.Player

class EffectArgumentMagicCost(private val type: MagicType) : EffectArgument<NoCompileData>("${type.id}_cost") {
    override fun isMet(element: ConfigurableElement, trigger: DispatchedTrigger, compileData: NoCompileData): Boolean {
        val player = trigger.dispatcher.get<Player>() ?: return false

        val cost = element.config.getIntFromExpression("${type.id}_cost", player)

        val balance = player.magic[type]
        traceMagicCost(player) {
            "Magic cost check: player=${player.name} resource=${type.id} cost=$cost balance=$balance allowed=${balance >= cost}"
        }
        return balance >= cost
    }

    override fun ifMet(element: ConfigurableElement, trigger: DispatchedTrigger, compileData: NoCompileData) {
        val player = trigger.dispatcher.get<Player>() ?: return

        val cost = element.config.getIntFromExpression("${type.id}_cost", player)

        val before = player.magic[type]
        player.magic[type] -= cost
        val after = player.magic[type]
        traceMagicCost(player) {
            "Magic cost payment: player=${player.name} resource=${type.id} cost=$cost before=$before after=$after"
        }
    }
}
