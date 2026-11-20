#version 330
#extension GL_ARB_separate_shader_objects : require

// Bloom pyramid downsample. Same weights as the original 20-group formulation, with the duplicate
// four_k() evaluations merged: 13 groups / 52 bilinear fetches instead of 80.

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec4 four_k(vec3 texel, vec2 uv) {
    return (
        texture(InSampler, uv + texel.xx) +
        texture(InSampler, uv + texel.xy) +
        texture(InSampler, uv + texel.yx) +
        texture(InSampler, uv + texel.yy)
    ) * 0.25;
}

void main() {
    vec2 uv = texCoord;
    vec3 texelIn = vec3(1.0, -1.0, 0.0) / InSize.xyx;
    vec3 texelOut = vec3(1.0, -1.0, 0.0) / OutSize.xyx;

    // Weights (x 1/32): center 4, edges 2, corners 1 -> 16/32 total.
    vec4 color = four_k(texelIn, uv) * (4.0 / 32.0);
    color += (four_k(texelIn, uv + texelOut.zy)
        + four_k(texelIn, uv + texelOut.yz)
        + four_k(texelIn, uv + texelOut.xz)
        + four_k(texelIn, uv + texelOut.zx)) * (2.0 / 32.0);
    color += (four_k(texelIn, uv + texelOut.yy)
        + four_k(texelIn, uv + texelOut.xy)
        + four_k(texelIn, uv + texelOut.yx)
        + four_k(texelIn, uv + texelOut.xx)) * (1.0 / 32.0);

    // Inner ring at one input texel: 4 x 1/8.
    color += (four_k(texelIn, uv + texelIn.xx)
        + four_k(texelIn, uv + texelIn.xy)
        + four_k(texelIn, uv + texelIn.yx)
        + four_k(texelIn, uv + texelIn.yy)) * 0.125;

    fragColor = vec4(color.rgb, 1.0);
}
