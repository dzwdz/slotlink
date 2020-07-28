package badasintended.slotlink.block.entity

import badasintended.slotlink.block.LinkCableBlock
import badasintended.slotlink.common.toPos
import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.block.BlockState
import net.minecraft.block.entity.BlockEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.World

class MasterBlockEntity : BlockEntity(BlockEntityTypeRegistry.MASTER) {

    private var linkCables = ListTag()

    private fun validateConnectors(world: World) {
        val linkCableSet = HashSet<CompoundTag>()
        linkCables.forEach { linkCableTag ->
            linkCableTag as CompoundTag
            val linkCablePos = linkCableTag.toPos()
            val linkCableBlock = world.getBlockState(linkCablePos).block
            if (linkCableBlock is LinkCableBlock) {
                val linkCableNbt = world.getBlockEntity(linkCablePos)!!.toTag(CompoundTag())

                val linkCableHasMaster = linkCableNbt.getBoolean("hasMaster")
                val linkCableMasterPos = linkCableNbt.getCompound("masterPos").toPos()

                if (linkCableHasMaster and (linkCableMasterPos == pos)) {
                    linkCableSet.add(linkCableTag)
                }
            }
        }
        linkCables.clear()
        linkCables.addAll(linkCableSet)
    }

    override fun toTag(tag: CompoundTag): CompoundTag {
        super.toTag(tag)

        tag.put("linkCables", linkCables)

        return tag
    }

    override fun fromTag(state: BlockState, tag: CompoundTag) {
        super.fromTag(state, tag)

        linkCables = tag.getList("linkCables", NbtType.COMPOUND)
    }

    override fun markDirty() {
        if (world != null) validateConnectors(world!!)
        super.markDirty()
    }

}
