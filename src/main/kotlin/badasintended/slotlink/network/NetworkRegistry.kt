package badasintended.slotlink.network

import badasintended.slotlink.Mod
import badasintended.slotlink.block.RequestBlock
import badasintended.slotlink.client.gui.screen.AbstractRequestScreen
import badasintended.slotlink.screen.AbstractRequestScreenHandler
import net.fabricmc.api.EnvType
import net.fabricmc.api.Environment
import net.fabricmc.fabric.api.network.ClientSidePacketRegistry
import net.fabricmc.fabric.api.network.PacketConsumer
import net.fabricmc.fabric.api.network.PacketContext
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry
import net.minecraft.client.MinecraftClient
import net.minecraft.item.Item
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.PacketByteBuf
import net.minecraft.util.Identifier

object NetworkRegistry {

    val REQUEST_SAVE = Mod.id("request_save")
    val REMOTE_SAVE = Mod.id("remote_save")
    val CRAFT_ONCE = Mod.id("craft_once")
    val CRAFT_STACK = Mod.id("craft_stack")
    val CRAFT_CLEAR = Mod.id("craft_clear")
    val CRAFT_PULL = Mod.id("craft_pull")

    val FIRST_SORT = Mod.id("first_sort")

    fun initMain() {
        rS(REQUEST_SAVE, this::requestSave)
        rS(REMOTE_SAVE, this::remoteSave)
        rS(CRAFT_ONCE, this::craftOnce)
        rS(CRAFT_STACK, this::craftStack)
        rS(CRAFT_CLEAR, this::craftClear)
        rS(CRAFT_PULL, this::craftPull)
    }

    @Environment(EnvType.CLIENT)
    fun initClient() {
        rC(FIRST_SORT, this::firstSort)
    }

    private fun rS(id: Identifier, function: (PacketContext, PacketByteBuf) -> Unit) {
        ServerSidePacketRegistry.INSTANCE.register(id, PacketConsumer(function))
    }

    @Environment(EnvType.CLIENT)
    private fun rC(id: Identifier, function: (PacketContext, PacketByteBuf) -> Unit) {
        ClientSidePacketRegistry.INSTANCE.register(id, PacketConsumer(function))
    }

    private fun requestSave(context: PacketContext, buf: PacketByteBuf) {
        val pos = buf.readBlockPos()
        val sort = buf.readInt()

        context.taskQueue.execute {
            val world = context.player.world
            val blockState = world.getBlockState(pos)
            val block = blockState.block

            if (block is RequestBlock) {
                val blockEntity = world.getBlockEntity(pos)!!
                val nbt = blockEntity.toTag(CompoundTag())
                nbt.putInt("lastSort", sort)
                blockEntity.fromTag(blockState, nbt)
                blockEntity.markDirty()
            }
        }
    }

    private fun remoteSave(context: PacketContext, buf: PacketByteBuf) {
        val offHand = buf.readBoolean()
        val sort = buf.readInt()

        context.taskQueue.execute {
            val stack = if (offHand) context.player.offHandStack else context.player.mainHandStack
            stack.orCreateTag.putInt("lastSort", sort)
        }
    }

    private fun craftOnce(context: PacketContext, buf: PacketByteBuf) {
        context.taskQueue.execute {
            (context.player.currentScreenHandler as AbstractRequestScreenHandler).craftOnce()
        }
    }

    private fun craftStack(context: PacketContext, buf: PacketByteBuf) {
        context.taskQueue.execute {
            (context.player.currentScreenHandler as AbstractRequestScreenHandler).craftStack()
        }
    }

    private fun craftClear(context: PacketContext, buf: PacketByteBuf) {
        context.taskQueue.execute {
            (context.player.currentScreenHandler as AbstractRequestScreenHandler).clearCraft()
        }
    }

    private fun craftPull(context: PacketContext, buf: PacketByteBuf) {
        val outside = arrayListOf<ArrayList<Item>>()

        for (i in 0 until buf.readInt()) {
            val inside = arrayListOf<Item>()
            for (j in 0 until buf.readInt()) {
                inside.add(buf.readItemStack().item)
            }
            outside.add(inside)
        }

        context.taskQueue.execute {
            (context.player.currentScreenHandler as AbstractRequestScreenHandler).pullInput(outside)
        }
    }

    @Environment(EnvType.CLIENT)
    private fun firstSort(context: PacketContext, buf: PacketByteBuf) {
        context.taskQueue.run {
            (MinecraftClient.getInstance().currentScreen as AbstractRequestScreen<*>?)?.sort()
        }
    }

}
