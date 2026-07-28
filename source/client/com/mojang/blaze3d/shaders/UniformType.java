package com.mojang.blaze3d.shaders;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public enum UniformType {
    UNIFORM_BUFFER,
    TEXEL_BUFFER;
}
