#version 330
#extension GL_ARB_separate_shader_objects : require

// Adds the weighted bloom pyramid onto the main target (additive blend, color channels only).
// BLOOM_LEVELS (1-6) is a pipeline define so inactive levels are neither bound nor sampled.

#include <shine:bloom_depth.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D Level0Sampler;
#if BLOOM_LEVELS > 1
uniform sampler2D Level1Sampler;
#endif
#if BLOOM_LEVELS > 2
uniform sampler2D Level2Sampler;
#endif
#if BLOOM_LEVELS > 3
uniform sampler2D Level3Sampler;
#endif
#if BLOOM_LEVELS > 4
uniform sampler2D Level4Sampler;
#endif
#if BLOOM_LEVELS > 5
uniform sampler2D Level5Sampler;
#endif

layout(std140) uniform BloomCompositeConfig {
    vec4 Weights0;      // levels 0-3
    vec4 Weights1;      // levels 4-5
    vec4 HalfTexelX0;   // 0.5 / level width, levels 0-3
    vec4 HalfTexelX1;   // levels 4-5
    float Strength;
    float MaxDistance;
    float NearPlane;
    float FarPlane;
    float DistanceFadeRange;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 centered_sample(sampler2D levelSampler, vec2 uv, float halfTexelX) {
    vec2 offset = vec2(halfTexelX, 0.0);
    return (texture(levelSampler, uv - offset).rgb + texture(levelSampler, uv + offset).rgb) * 0.5;
}

void main() {
    vec2 uv = texCoord;
    float depth = texture(DepthSampler, uv).r;
    if (shine_is_sky(depth)) {
        fragColor = vec4(0.0);
        return;
    }

    float sceneDistance = shine_linearize_depth(depth, NearPlane, FarPlane);
    float distanceMask = 1.0 - smoothstep(MaxDistance, MaxDistance + DistanceFadeRange, sceneDistance);
    if (distanceMask <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 bloom = centered_sample(Level0Sampler, uv, HalfTexelX0.x) * Weights0.x;
#if BLOOM_LEVELS > 1
    bloom += centered_sample(Level1Sampler, uv, HalfTexelX0.y) * Weights0.y;
#endif
#if BLOOM_LEVELS > 2
    bloom += centered_sample(Level2Sampler, uv, HalfTexelX0.z) * Weights0.z;
#endif
#if BLOOM_LEVELS > 3
    bloom += centered_sample(Level3Sampler, uv, HalfTexelX0.w) * Weights0.w;
#endif
#if BLOOM_LEVELS > 4
    bloom += centered_sample(Level4Sampler, uv, HalfTexelX1.x) * Weights1.x;
#endif
#if BLOOM_LEVELS > 5
    bloom += centered_sample(Level5Sampler, uv, HalfTexelX1.y) * Weights1.y;
#endif
    fragColor = vec4(bloom * (Strength * distanceMask), 0.0);
}
