#version 330
#extension GL_ARB_separate_shader_objects : require

#include <shine:bloom_depth.glsl>

uniform sampler2D DepthSampler;
uniform sampler2D SourceSampler;

layout(std140) uniform BloomExtractConfig {
    float Threshold;
    float HighlightClamp;
    float SoftKnee;
    float MaxDistance;
    float NearPlane;
    float FarPlane;
    float SourceStrengthScale;
    float DistanceFadeRange;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    if (shine_is_sky(depth)) {
        fragColor = vec4(0.0);
        return;
    }

    vec4 source = texture(SourceSampler, texCoord);
    float encodedStrength = clamp(source.a, 0.0, 1.0);
    if (encodedStrength <= 1.0e-5) {
        fragColor = vec4(0.0);
        return;
    }

    vec3 rawSourceColor = source.rgb;
    float rawBrightness = max(max(rawSourceColor.r, rawSourceColor.g), rawSourceColor.b);
    if (rawBrightness <= 1.0e-6) {
        fragColor = vec4(0.0);
        return;
    }

    float clampedRawBrightness = min(rawBrightness, HighlightClamp);
    float highlightScale = clampedRawBrightness / rawBrightness;
    float bloomMask = smoothstep(Threshold, Threshold + max(SoftKnee, 1.0e-4), clampedRawBrightness);
    float sceneDistance = shine_linearize_depth(depth, NearPlane, FarPlane);
    float distanceMask = 1.0 - smoothstep(MaxDistance, MaxDistance + DistanceFadeRange, sceneDistance);
    vec3 maskedRawBloom = rawSourceColor * highlightScale * bloomMask;
    float sourceStrength = encodedStrength * SourceStrengthScale;
    fragColor = vec4(maskedRawBloom * sourceStrength * distanceMask, 1.0);
}
