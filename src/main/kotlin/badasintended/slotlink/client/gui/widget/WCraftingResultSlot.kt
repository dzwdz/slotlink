package badasintended.slotlink.client.gui.widget

import badasintended.slotlink.network.NetworkRegistry.CRAFT_ONCE
import badasintended.slotlink.network.NetworkRegistry.CRAFT_STACK
import badasintended.slotlink.screen.AbstractRequestScreenHandler
import io.netty.buffer.Unpooled
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry.INSTANCE
import net.minecraft.client.gui.screen.Screen
import net.minecraft.network.PacketByteBuf
import spinnery.common.registry.NetworkRegistry.SLOT_CLICK_PACKET
import spinnery.common.registry.NetworkRegistry.createSlotClickPacket
import spinnery.common.utility.StackUtilities.equalItemAndTag
import spinnery.widget.WSlot
import spinnery.widget.api.Action.CLONE

@Environment(EnvType.CLIENT)
class WCraftingResultSlot(
    private val sort: () -> Any
) : WSlot() {

    override fun onMouseReleased(mouseX: Float, mouseY: Float, button: Int) {
        sort.invoke()
    }

    override fun onMouseDragged(mouseX: Float, mouseY: Float, button: Int, deltaX: Double, deltaY: Double) {}

    override fun onMouseClicked(mouseX: Float, mouseY: Float, button: Int) {
        if (!isFocused) return

        val container = `interface`.handler as AbstractRequestScreenHandler
        val player = container.player

        val cursorStack = player.inventory.cursorStack

        if (Screen.hasShiftDown()) {
            container.craftStack()
            if (button == LEFT) INSTANCE.sendToServer(CRAFT_STACK, PacketByteBuf(Unpooled.buffer()))
        } else {
            if ((button == LEFT) or (button == RIGHT)) {
                if (
                    (!equalItemAndTag(cursorStack, stack) and !cursorStack.isEmpty)
                    or ((cursorStack.count + stack.count) > cursorStack.maxCount)
                ) return
                container.craftOnce()
                INSTANCE.sendToServer(CRAFT_ONCE, PacketByteBuf(Unpooled.buffer()))
            } else if (button == MIDDLE) {
                container.onSlotAction(slotNumber, inventoryNumber, button, CLONE, player)
                INSTANCE.sendToServer(
                    SLOT_CLICK_PACKET,
                    createSlotClickPacket(container.syncId, slotNumber, inventoryNumber, button, CLONE)
                )
            }
        }
    }

}
