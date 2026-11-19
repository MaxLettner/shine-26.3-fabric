#version 330
#extension GL_ARB_separate_shader_objects : require

// Writes the bloom source for opaque terrain: fogged color in rgb, source strength / 5 in alpha.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>
#ifndef MULTIDRAW_TERRAIN
    #include <minecraft:chunksection.glsl>
#endif

uniform sampler2D Sampler0;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;
layout(location = 4) in float chunkVisibility;
layout(location = 5) in float bloomStrength;

layout(location = 0) out vec4 fragColor;

void main() {
    if (bloomStrength <= 1.0e-5) {
        fragColor = vec4(0.0);
        return;
    }

    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize) : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, chunkVisibility);
    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    vec4 foggedColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd);
    float fogAttenuation = 1.0 - fogValue;
    fragColor = vec4(foggedColor.rgb * foggedColor.a * fogAttenuation, clamp(bloomStrength / 5.0, 0.0, 1.0));
}
