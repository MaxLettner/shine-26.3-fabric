#ifndef SHINE_BLOOM_DEPTH_GLSL
#define SHINE_BLOOM_DEPTH_GLSL

// Minecraft 26.x renders with reversed-Z: the depth buffer clears to 0 (sky) and the near plane maps to 1.
bool shine_is_sky(float depth) {
    return depth <= 0.0;
}

float shine_linearize_depth(float depth, float nearPlane, float farPlane) {
    #ifdef RENDERPEARL_DEPTH_IS_ZERO_TO_ONE
    return (nearPlane * farPlane) / max(depth * (farPlane - nearPlane) + nearPlane, 1.0e-6);
    #else
    float zNdc = depth * 2.0 - 1.0;
    return (2.0 * nearPlane * farPlane) / max(zNdc * (farPlane - nearPlane) + nearPlane + farPlane, 1.0e-6);
    #endif
}

#endif
