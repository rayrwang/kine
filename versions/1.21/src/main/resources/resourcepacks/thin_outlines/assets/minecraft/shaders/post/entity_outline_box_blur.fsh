#version 330

// kine override of the vanilla entity-outline box blur.
// Vanilla outputs (blurred / (radius + 0.5)).rgb, which averages the premultiplied edge color
// against the transparent (0,0,0) texels around a thin silhouette — so the outline fringe fades
// toward BLACK while its alpha stays high, and thin entities (arrows) read as black at distance.
// Here we renormalize the blurred color back to full brightness on its dominant channel, so the
// outline keeps its hue (e.g. red) at every coverage instead of darkening. Alpha is unchanged, so
// the soft edge still comes from the blur as vanilla intends.

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform BlurConfig {
    vec2 BlurDir;
    float Radius;
};

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 oneTexel = 1.0 / InSize;
    vec2 sampleStep = oneTexel * BlurDir;

    vec4 blurred = vec4(0.0);
    float radius = 2.0;
    for (float a = -radius + 0.5; a <= radius; a += 2.0) {
        blurred += texture(InSampler, texCoord + sampleStep * a);
    }
    blurred += texture(InSampler, texCoord + sampleStep * radius) / 2.0;

    vec3 rgb = (blurred / (radius + 0.5)).rgb;
    float m = max(max(rgb.r, rgb.g), rgb.b);
    rgb = m > 0.0001 ? rgb / m : vec3(0.0);   // full-brightness hue, no fade-to-black at the edges
    fragColor = vec4(rgb, blurred.a);
}
