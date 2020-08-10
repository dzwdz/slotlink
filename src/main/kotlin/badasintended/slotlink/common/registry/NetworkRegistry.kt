package badasintended.slotlink.common.registry

import badasintended.slotlink.Slotlink
import badasintended.slotlink.block.entity.*
import badasintended.slotlink.common.util.RedstoneMode
import badasintended.slotlink.common.util.readInventory
import badasintended.slotlink.gui.screen.RequestScreenHandler
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.network.*
import net.minecraft.item.Item
import net.minecraft.network.PacketByteBuf
import net.minecraft.util.Identifier
import net.minecraft.util.math.Direction

object NetworkRegistry {

    val REQUEST_SAVE = Slotlink.id("request_save")
    val REMOTE_SAVE = Slotlink.id("remote_save")
    val CRAFT_ONCE = Slotlink.id("craft_once")
    val CRAFT_STACK = Slotlink.id("craft_stack")
    val CRAFT_CLEAR = Slotlink.id("craft_clear")
    val CRAFT_PULL = Slotlink.id("craft_pull")
    val LINK_WRITE = Slotlink.id("link_write")
    val TRANSFER_WRITE = Slotlink.id("transfer_write")

    val REQUEST_REMOVE = Slotlink.id("request_remove")
    val REQUEST_CURSOR = Slotlink.id("request_cursor")

    fun initMain() {
        rS(REQUEST_SAVE) { context, buf ->
            val pos = buf.readBlockPos()
            val sort = buf.readInt()

            context.taskQueue.execute {
                val blockEntity = context.player.world.getBlockEntity(pos)

                if (blockEntity is RequestBlockEntity) {
                    blockEntity.lastSort = sort
                    blockEntity.markDirty()
                }
            }
        }

        rS(REMOTE_SAVE) { context, buf ->
            val offHand = buf.readBoolean()
            val sort = buf.readInt()

            context.taskQueue.execute {
                val stack = if (offHand) context.player.offHandStack else context.player.mainHandStack
                stack.orCreateTag.putInt("lastSort", sort)
            }
        }

        rS(CRAFT_ONCE) { context, _ ->
            context.taskQueue.execute {
                (context.player.currentScreenHandler as RequestScreenHandler).craftOnce()
            }
        }

        rS(CRAFT_STACK) { context, _ ->
            context.taskQueue.execute {
                (context.player.currentScreenHandler as RequestScreenHandler).craftStack()
            }
        }

        rS(CRAFT_CLEAR) { context, _ ->
            context.taskQueue.execute {
                (context.player.currentScreenHandler as RequestScreenHandler).clearCraft()
            }
        }

        rS(CRAFT_PULL) { context, buf ->
            val outside = arrayListOf<ArrayList<Item>>()

            for (i in 0 until buf.readInt()) {
                val inside = arrayListOf<Item>()
                for (j in 0 until buf.readInt()) {
                    inside.add(buf.readItemStack().item)
                }
                outside.add(inside)
            }

            context.taskQueue.execute {
                (context.player.currentScreenHandler as RequestScreenHandler).pullInput(outside)
            }
        }

        rS(LINK_WRITE) { context, buf ->
            val pos = buf.readBlockPos()
            val priority = buf.readVarInt()
            val isBlackList = buf.readBoolean()
            val filter = buf.readInventory()

            context.taskQueue.execute {
                val blockEntity = context.player.world.getBlockEntity(pos)
                if (blockEntity is LinkCableBlockEntity) {
                    blockEntity.priority = priority
                    blockEntity.isBlackList = isBlackList
                    blockEntity.filter = filter
                    blockEntity.markDirty()
                }
            }
        }

        rS(TRANSFER_WRITE) { context, buf ->
            val pos = buf.readBlockPos()
            val priority = buf.readVarInt()
            val isBlackList = buf.readBoolean()
            val filter = buf.readInventory()
            val side = Direction.byId(buf.readVarInt())
            val redstone = RedstoneMode.of(buf.readVarInt())

            context.taskQueue.execute {
                val blockEntity = context.player.world.getBlockEntity(pos)
                if (blockEntity is TransferCableBlockEntity) {
                    blockEntity.redstone = redstone
                    blockEntity.priority = priority
                    blockEntity.side = side
                    blockEntity.isBlackList = isBlackList
                    blockEntity.filter = filter
                    blockEntity.markDirty()
                }
            }
        }
    }

    @Environment(EnvType.CLIENT)
    fun initClient() {
        rC(REQUEST_REMOVE) { context, buf ->
            val deletedInv = buf.readIntArray()

            context.taskQueue.execute {
                val screenHandler = context.player.currentScreenHandler
                if (screenHandler is RequestScreenHandler) {
                    screenHandler.linkedSlots.removeIf { it.inventoryNumber in deletedInv }
                }
            }
        }

        rC(REQUEST_CURSOR) { context, buf ->
            val stack = buf.readItemStack()

            context.taskQueue.execute {
                context.player.inventory.cursorStack = stack
            }
        }
    }

    private fun rS(id: Identifier, function: (PacketContext, PacketByteBuf) -> Unit) {
        ServerSidePacketRegistry.INSTANCE.register(id, PacketConsumer(function))
    }

    @Environment(EnvType.CLIENT)
    private fun rC(id: Identifier, function: (PacketContext, PacketByteBuf) -> Unit) {
        ClientSidePacketRegistry.INSTANCE.register(id, PacketConsumer(function))
    }

}
