package com.jan1a234.counterstrike.client.mixin;

import com.jan1a234.counterstrike.client.SniperScope;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Zoomt beim Zielen mit dem Scharfschuetzengewehr heran. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@ModifyReturnValue(method = "calculateFov", at = @At("RETURN"))
	private float counterstrike$scopeZoom(float original) {
		return original * SniperScope.fovMultiplier();
	}
}
