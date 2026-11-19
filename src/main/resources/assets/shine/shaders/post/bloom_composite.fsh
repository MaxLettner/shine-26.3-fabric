#version 330
#extension GL_ARB_separate_shader_objects : require

#include <shine:bloom_depth.glsl>

uniform sampler2D MainSampler;
uniform sampler2D HalfSampler;
uniform sampler2D QuarterSampler;
uniform sampler2D EighthSampler;
uniform sampler2D SixteenthSampler;
uniform sampler2D ThirtysecondSampler;
uniform sampler2D SixtyfourthSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 MainSize;
    vec2 HalfSize;
    vec2 QuarterSize;
    vec2 EighthSize;
    vec2 SixteenthSize;
    vec2 ThirtysecondSize;
    vec2 SixtyfourthSize;
    vec2 DepthSize;
};

layout(std140) uniform BloomCompositeConfig {
    float Strength;
};

layout(std140) uniform BloomCompositeWeights {
    float Weight0;
    float Weight1;
    float Weight2;
    float Weight3;
    float Weight4;
    float Weight5;
    float WeightPad0;
    float WeightPad1;
};

layout(std140) uniform BloomCompositeDistanceConfig {
    float MaxDistance;
    float NearPlane;
    float FarPlane;
    float DistanceFadeRange;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 centered_sample(sampler2D levelSampler, vec2 uv, vec2 levelSize) {
    vec2 halfTexelX = vec2(0.5 / max(levelSize.x, 1.0), 0.0);
    return (texture(levelSampler, uv - halfTexelX).rgb + texture(levelSampler, uv + halfTexelX).rgb) * 0.5;
}

void main() {
    vec2 uv = texCoord;
    vec4 sceneColor = texture(MainSampler, uv);
    float depth = texture(DepthSampler, uv).r;
    if (shine_is_sky(depth)) {
        fragColor = sceneColor;
        return;
    }

    float sceneDistance = shine_linearize_depth(depth, NearPlane, FarPlane);
    float distanceMask = 1.0 - smoothstep(MaxDistance, MaxDistance + DistanceFadeRange, sceneDistance);
    vec3 bloomColor = centered_sample(HalfSampler, uv, HalfSize) * Weight0;
    bloomColor += centered_sample(QuarterSampler, uv, QuarterSize) * Weight1;
    bloomColor += centered_sample(EighthSampler, uv, EighthSize) * Weight2;
    bloomColor += centered_sample(SixteenthSampler, uv, SixteenthSize) * Weight3;
    bloomColor += centered_sample(ThirtysecondSampler, uv, ThirtysecondSize) * Weight4;
    bloomColor += centered_sample(SixtyfourthSampler, uv, SixtyfourthSize) * Weight5;
    bloomColor *= Strength * distanceMask;
    fragColor = vec4(sceneColor.rgb + bloomColor, sceneColor.a);
}
