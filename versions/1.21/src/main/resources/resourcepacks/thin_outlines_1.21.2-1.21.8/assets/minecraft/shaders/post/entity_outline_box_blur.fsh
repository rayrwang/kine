#version 150

// kine override of the vanilla entity-outline box blur (the thin_outlines folder's copy says why).
// The 1.21.2 to 1.21.8 copy (pack formats 42 to 64; compat.Packs picks this folder there): the game hands the sample
// step in from the vertex shader, as its own copy of this file has.

uniform sampler2D InSampler;

in vec2 texCoord;
in vec2 sampleStep;

out vec4 fragColor;

void main() {
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
