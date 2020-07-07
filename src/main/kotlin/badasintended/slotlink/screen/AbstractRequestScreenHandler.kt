package badasintended.slotlink.screen

import badasintended.slotlink.common.SortBy
import badasintended.slotlink.inventory.DummyInventory
import net.fabricmc.fabric.api.network.ServerSidePacketRegistry
import net.minecraft.block.BlockState
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.inventory.CraftingInventory
import net.minecraft.inventory.CraftingResultInventory
import net.minecraft.inventory.Inventory
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.network.PacketByteBuf
import net.minecraft.recipe.RecipeType
import net.minecraft.screen.CraftingScreenHandler
import net.minecraft.screen.ScreenHandlerContext
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.util.math.BlockPos
import spinnery.common.registry.NetworkRegistry.SLOT_UPDATE_PACKET
import spinnery.common.registry.NetworkRegistry.createSlotUpdatePacket
import spinnery.common.utility.StackUtilities
import spinnery.widget.WSlot
import spinnery.widget.api.Action
import spinnery.widget.api.Action.PICKUP_ALL
import spinnery.widget.api.Action.QUICK_MOVE
import spinnery.widget.api.Action.Subtype.FROM_SLOT_TO_CURSOR_CUSTOM_FULL_STACK
import spinnery.widget.api.Action.Subtype.FROM_SLOT_TO_SLOT_CUSTOM_FULL_STACK
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set


abstract class AbstractRequestScreenHandler(syncId: Int, player: PlayerEntity, buf: PacketByteBuf) :
    ModScreenHandler(syncId, player) {

    val blockPos: BlockPos = buf.readBlockPos()
    var lastSort = SortBy.of(buf.readInt())

    private val hasMaster = buf.readBoolean()

    private val totalInventory = if (hasMaster) buf.readInt() else 0

    private val inventoryPos = arrayListOf<BlockPos>()
    private val inventoryStates = arrayListOf<BlockState>()

    private val craftingInv = CraftingInventory(this, 3, 3)
    private val resultInv = CraftingResultInventory()

    private val inputSlots = arrayListOf<WSlot>()
    private val outputSlot: WSlot

    private val invMap = HashMap<Int, Inventory>()

    val playerSlots = arrayListOf<WSlot>()
    val linkedSlots = arrayListOf<WSlot>()

    private val context: ScreenHandlerContext = ScreenHandlerContext.create(player.world, blockPos)

    private val fixedSplitSlots = linkedSetOf<WSlot>()
    private val fixedSingleSlots = linkedSetOf<WSlot>()

    init {
        for (i in 0 until totalInventory) inventoryPos.add(buf.readBlockPos())

        inventoryPos.forEachIndexed { index, blockPos ->
            context.run { world, _ ->
                val chunk = world.getWorldChunk(blockPos)
                val blockEntity = chunk.getBlockEntity(blockPos)!!
                inventoryStates.add(chunk.getBlockState(blockPos))
                invMap[index + 3] = blockEntity as Inventory
            }
        }

        inventories[-2] = DummyInventory(1, 1)
        inventories[-1] = DummyInventory(8, 6)
        inventories[1] = craftingInv
        inventories[2] = resultInv
        inventories.putAll(invMap)

        WSlot.addHeadlessArray(root, 0, -1, 8, 6)
        WSlot.addHeadlessArray(root, 0, -2, 1, 1)

        for (i in 0..8) {
            val slot = root.createChild { WSlot() }
            slot.setInventoryNumber<WSlot>(1)
            slot.setSlotNumber<WSlot>(i)
            inputSlots.add(slot)
        }

        invMap.forEach { (num, inv) ->
            for (i in 0 until inv.size()) {
                val slot = root.createChild { WSlot() }
                slot.setInventoryNumber<WSlot>(num)
                slot.setSlotNumber<WSlot>(i)
                linkedSlots.add(slot)
            }
        }

        outputSlot = root.createChild { WSlot() }
        outputSlot.setInventoryNumber<WSlot>(2)
        outputSlot.setSlotNumber<WSlot>(0)
        outputSlot.setWhitelist<WSlot>()

        playerSlots.addAll(WSlot.addHeadlessPlayerInventory(root))
        root.recalculateCache()
    }

    fun isDeleted(invNumber: Int): Boolean {
        var result = false
        context.run { world, _ ->
            val state = world.getBlockState(inventoryPos[invNumber - 3])
            result = (state != (inventoryStates[invNumber - 3]))
        }
        return result
    }

    fun craftOnce() {
        if (playerInventory.cursorStack.isEmpty) {
            playerInventory.cursorStack = outputSlot.stack
        } else {
            playerInventory.cursorStack.increment(outputSlot.stack.count)
        }
        outputSlot.setStack<WSlot>(ItemStack.EMPTY)

        val remainingStacks = world.recipeManager.getRemainingStacks(RecipeType.CRAFTING, craftingInv, world)

        val filledInput = inputSlots.filterNot { it.stack.isEmpty }
        filledInput.forEachIndexed { i, slot ->
            if (remainingStacks[i].isEmpty) {
                val first = linkedSlots.firstOrNull { StackUtilities.equalItemAndTag(it.stack, slot.stack) }
                if (first == null) slot.stack.decrement(1)
                else first.stack.decrement(1)
            } else {
                slot.setStack<WSlot>(remainingStacks[i])
            }
        }
        craftItem()
    }

    fun craftStack() {
        val outputStack = outputSlot.stack
        val craftMax = outputStack.maxCount / outputStack.count

        var crafted = 1
        for (i in 0 until craftMax) {
            val remainingStacks = world.recipeManager.getRemainingStacks(RecipeType.CRAFTING, craftingInv, world)
            val filledInput = inputSlots.filterNot { it.stack.isEmpty }

            var prevSuccess = true
            filledInput.forEachIndexed { j, slot ->
                if (remainingStacks[j].isEmpty) {
                    val first = linkedSlots.firstOrNull { StackUtilities.equalItemAndTag(it.stack, slot.stack) }
                    if (first == null) {
                        prevSuccess = prevSuccess and (slot.stack.count >= 1)
                        slot.stack.decrement(1)
                    } else {
                        prevSuccess = prevSuccess and true
                        first.stack.decrement(1)
                    }
                } else {
                    slot.setStack<WSlot>(remainingStacks[j])
                }
            }
            if (!prevSuccess) break
            crafted++
        }

        outputStack.count *= crafted.coerceAtMost(craftMax)

        onSlotAction(0, 2, 0, QUICK_MOVE, player)
        craftItem()
    }

    fun clearCraft() {
        val containerSlot = arrayListOf<WSlot>()
        for (widget in root.allWidgets) {
            if (widget is WSlot) when (widget.inventoryNumber) {
                0, -2, -1, 1, 2 -> Unit
                else -> {
                    if (!isDeleted(widget.inventoryNumber)) containerSlot.add(widget)
                }
            }
        }

        val filledInput = inputSlots.filterNot { it.stack.isEmpty }
        filledInput.forEach { slot ->
            for (i in 1..slot.stack.count) {
                containerSlot.sortByDescending { it.stack.count }
                val first = containerSlot.firstOrNull {
                    StackUtilities.equalItemAndTag(it.stack, slot.stack) and (it.stack.count < it.stack.maxCount)
                }
                if (first != null) {
                    first.stack.increment(1)
                    slot.stack.decrement(1)
                }
            }
            if (slot.stack.count > 0) {
                val first = containerSlot.firstOrNull { it.stack.isEmpty }
                if (first != null) {
                    first.setStack<WSlot>(slot.stack)
                    slot.setStack<WSlot>(ItemStack.EMPTY)
                }
            }
        }
        craftItem()
    }

    fun pullInput(outside: ArrayList<ArrayList<Item>>) {
        clearCraft()
        dropInventory(player, world, craftingInv)

        playerSlots.sortByDescending { it.stack.count }
        linkedSlots.sortByDescending { it.stack.count }

        outside.forEachIndexed { slotN, inside ->
            for (item in inside) {
                if (item == Items.AIR) continue
                var first = playerSlots.firstOrNull { it.stack.item == item }
                if (first == null) first = linkedSlots.firstOrNull { it.stack.item == item }
                if (first != null) {
                    val stack = first.stack.copy()
                    stack.count = 1
                    stack.tag = first.stack.tag
                    first.stack.decrement(1)
                    inputSlots[slotN].setStack<WSlot>(stack)
                    break
                }
            }
        }

        craftItem()
    }

    /**
     * Taken from [CraftingScreenHandler]
     */
    private fun craftItem() {
        context.run { world, _ ->
            if (!world.isClient) {
                player as ServerPlayerEntity
                var itemStack = ItemStack.EMPTY
                val optional = world.server!!.recipeManager.getFirstMatch(RecipeType.CRAFTING, craftingInv, world)
                if (optional.isPresent) {
                    val craftingRecipe = optional.get()
                    if (resultInv.shouldCraftRecipe(world, player, craftingRecipe)) {
                        itemStack = craftingRecipe.craft(craftingInv)
                    }
                }
                outputSlot.setStack<WSlot>(itemStack)
                ServerSidePacketRegistry.INSTANCE.sendToPlayer(
                    player, SLOT_UPDATE_PACKET,
                    createSlotUpdatePacket(syncId, outputSlot.slotNumber, outputSlot.inventoryNumber, itemStack)
                )
                resultInv.unlockLastRecipe(player)
            }
        }
    }

    /**
     * nvm i like mine more :3
     */
    override fun onSlotDrag(slotNumber: IntArray, inventoryNumber: IntArray, action: Action) {
        val slots: MutableSet<WSlot> = LinkedHashSet()

        for (i in slotNumber.indices) {
            val slot = getInterface().getSlot<WSlot>(inventoryNumber[i], slotNumber[i])
            if (slot != null) slots.add(slot)
        }

        if (slots.isEmpty()) return

        val split = if (action.isSplit) (playerInventory.cursorStack.count / slots.size).coerceAtLeast(1) else 1
        var stackA = if (action.isPreview) playerInventory.cursorStack.copy() else playerInventory.cursorStack

        if (stackA.isEmpty) return

        for (slotA in slots) {
            if (slotA.refuses(stackA)) continue
            val stackB: ItemStack = if (action.isPreview) slotA.stack.copy() else slotA.stack

            val stacks = StackUtilities.merge(stackA, stackB, split, stackA.maxCount.coerceAtMost(split + stackB.count))
            if (action.isPreview) {
                previewCursorStack = stacks.first.copy()
                previewCursorStack.count = stacks.first.count
                slotA.setPreviewStack<WSlot>(stacks.second.copy())
            } else {
                stackA = stacks.first
                previewCursorStack = ItemStack.EMPTY
                slotA.setStack(stacks.second)
            }
        }
    }

    override fun getDragSlots(mouseButton: Int): MutableSet<WSlot>? {
        return when (mouseButton) {
            0 -> fixedSplitSlots
            1 -> fixedSingleSlots
            else -> null
        }
    }

    override fun onContentChanged(inventory: Inventory) {
        if ((inventory == craftingInv)) {
            craftItem()
        } else super.onContentChanged(inventory)
    }

    /**
     * Make [Action.QUICK_MOVE] does not target crafting slots also
     * makes it target player inventory if the slot is one
     * of the [linkedSlots] and vice versa.
     */
    override fun onSlotAction(
        slotNumber: Int,
        inventoryNumber: Int,
        button: Int,
        action: Action,
        player: PlayerEntity
    ) {
        val source: WSlot = root.getSlot(inventoryNumber, slotNumber) ?: return
        if (source.isLocked) return

        val cursorStack = playerInventory.cursorStack

        linkedSlots.sortByDescending { it.stack.count }
        playerSlots.sortBy { it.slotNumber }
        playerSlots.sortByDescending { it.stack.count }

        if (action == QUICK_MOVE) {
            val targets = arrayListOf<WSlot>()
            when (inventoryNumber) {
                // when in player inventory, target container slots first
                0 -> {
                    targets.addAll(linkedSlots)
                    targets.addAll(playerSlots)
                }

                // when in crafting slots, target player inventory first
                1, 2 -> {
                    targets.addAll(playerSlots)
                    targets.addAll(linkedSlots)
                }

                // buffer
                -2 -> targets.addAll(linkedSlots)

                // when in container slots, only target player inventory
                else -> targets.addAll(playerSlots)
            }

            for (target in targets) {
                if ((target.inventoryNumber == inventoryNumber) and (target.slotNumber == slotNumber)) continue
                if (target.refuses(source.stack) or target.isLocked) continue

                if ((!source.stack.isEmpty and target.stack.isEmpty) or (StackUtilities.equalItemAndTag(
                        source.stack, target.stack
                    ) and (target.stack.count < target.maxCount))
                ) {
                    val max = if (target.stack.isEmpty) source.maxCount else target.maxCount
                    source.consume(action, FROM_SLOT_TO_SLOT_CUSTOM_FULL_STACK)
                    StackUtilities.merge(source::getStack, target::getStack, source::getMaxCount) { max }
                        .apply({ source.setStack<WSlot>(it) }, { target.setStack<WSlot>(it) })
                    if ((source.inventoryNumber in arrayOf(2, 0, -2)) and !source.stack.isEmpty) {
                        continue
                    } else break
                }
            }
            val buffer = root.getSlot<WSlot>(-2, 0)
            if ((inventoryNumber == -2) and !buffer.stack.isEmpty) {
                playerInventory.cursorStack = buffer.stack
                buffer.setStack<WSlot>(ItemStack.EMPTY)
            }
        } else if (action == PICKUP_ALL) {
            playerSlots.forEach { slot ->
                if (StackUtilities.equalItemAndTag(slot.stack, cursorStack) and !slot.isLocked) {
                    slot.consume(action, FROM_SLOT_TO_CURSOR_CUSTOM_FULL_STACK)
                    StackUtilities.merge(slot::getStack, { cursorStack }, slot::getMaxCount, { cursorStack.maxCount }
                    ).apply({ slot.setStack<WSlot>(it) }, { playerInventory.cursorStack = it })
                }
            }
        } else super.onSlotAction(slotNumber, inventoryNumber, button, action, player)
    }

    override fun close(player: PlayerEntity) {
        clearCraft()
        dropInventory(player, world, craftingInv)
        super.close(player)
    }

}
