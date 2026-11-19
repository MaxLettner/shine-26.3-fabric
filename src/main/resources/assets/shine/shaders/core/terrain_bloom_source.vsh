#version 330
#extension GL_ARB_separate_shader_objects : require

// Copy of minecraft:core/terrain.vsh (gl_Position must match it exactly for the depth-equal replay)
// plus decoding of the bloom source strength that Shine packs into the low lightmap bits.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV2;
#ifdef MULTIDRAW_TERRAIN
layout(location = 4) in ivec3 ChunkPosition;
layout(location = 5) in float ChunkVisibility;
#endif

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;
layout(location = 4) out float chunkVisibility;
layout(location = 5) out float bloomStrength;

float shine_decode_bloom_strength(ivec2 uv2) {
    int blockNibble = uv2.x & 0xF;
    int skyNibble = uv2.y & 0xF;
    int legacy = (blockNibble & 0x7) | ((skyNibble & 0x7) << 3);
    int mode = ((blockNibble >> 3) & 1) | (((skyNibble >> 3) & 1) << 1);
    if (mode == 0) {
        return float(legacy) / 63.0;
    }

    int code = (mode - 1) * 64 + legacy + 1;
    float t = float(code - 1) / 191.0;
    return 1.0 + t * 4.0;
}

void main() {
    vec3 pos = Position + (ChunkPosition - CameraBlockPos) + CameraOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
    texCoord0 = UV0;

    const float chunkFullyVisibleRange = 16.0;
    float dist = length(pos);
    chunkVisibility = mix(1.0, ChunkVisibility, clamp((dist - chunkFullyVisibleRange) / chunkFullyVisibleRange, 0.0, 1.0));
    bloomStrength = shine_decode_bloom_strength(UV2);
}
