package net.minecraft.world.level.levelgen.structure.templatesystem;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jspecify.annotations.Nullable;

public interface StructureProcessor {
    default StructureTemplate.@Nullable StructureBlockInfo processBlock(
        LevelReader level,
        BlockPos targetPosition,
        BlockPos referencePos,
        BlockPos templateRelativePos,
        StructureTemplate.StructureBlockInfo processedBlockInfo,
        StructurePlaceSettings settings
    ) {
        return processedBlockInfo;
    }

    MapCodec<? extends StructureProcessor> codec();

    default List<StructureTemplate.StructureBlockInfo> finalizeProcessing(
        ServerLevelAccessor level,
        BlockPos position,
        BlockPos referencePos,
        List<StructureTemplate.StructureBlockInfo> originalBlockInfoList,
        List<StructureTemplate.StructureBlockInfo> processedBlockInfoList,
        StructurePlaceSettings settings
    ) {
        return processedBlockInfoList;
    }

    default boolean evaluatesEntirePieceState() {
        return false;
    }
}
