#version 150

// Расстояние до мира за пикселем по оси взгляда (client/fx/layer/SceneDepth): ближнее из глубины Minecraft
// (Sampler0) и глубины Distant Horizons (Sampler1), неба — 1e9.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform mat4 InvProj;
uniform mat4 DhInvProj;
// x: 1 — DH рисовал в этом кадре; y: 1 — глубина DH в NDC от −1 до 1 (иначе от 0 до 1); z: глубина DH без LOD
uniform vec3 DhDepth;

out vec4 fragColor;

float along(mat4 inverseProjection, vec2 ndc, float z) {
    vec4 view = inverseProjection * vec4(ndc, z, 1.0);
    return -view.z / view.w;
}

void main() {
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
    vec2 ndc = uv * 2.0 - 1.0;
    float depth = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r;
    float dist = depth >= 1.0 ? 1e9 : along(InvProj, ndc, depth * 2.0 - 1.0);
    if (DhDepth.x > 0.5) {
        float lod = texture(Sampler1, uv).r;
        if (abs(lod - DhDepth.z) > 1e-7) {
            dist = min(dist, along(DhInvProj, ndc, DhDepth.y > 0.5 ? lod * 2.0 - 1.0 : lod));
        }
    }
    fragColor = vec4(dist, 0.0, 0.0, 1.0);
}
