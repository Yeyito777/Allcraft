package com.mojang.blaze3d.textures;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public enum AddressMode {
    REPEAT,
    CLAMP_TO_EDGE;
}
