#version 150

// Слой эффектов (client/fx/layer). Смешивание ONE, ONE_MINUS_SRC_ALPHA: цвет — свет, уже умноженный на
// непрозрачность, альфа — сколько закрыто того, что за частицей (у искр и ореолов 0 — свет складывается).
// Текстура листа — тоже с умноженной альфой. Бледное (альфа меньше 0,1) не отбрасывается, как у ванильных шейдеров:
// дальний дым и зарево как раз бледные.
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (SceneDepth)
uniform sampler2D Sampler1;

uniform vec4 ColorModulator;
uniform vec2 ScreenSize;
uniform vec4 FxFogColor;
// 1 — расстояние до мира есть
uniform float FxScene;

in vec2 texCoord0;
in vec4 vertexColor;
in float viewDistance;
in float softness;
in float fog;
in float nearest;
in float opacity;

out vec4 fragColor;

// Частицы — как из атласа Minecraft (NEAREST_MIPMAP_LINEAR): в каждой уменьшенной копии ближайший пиксель, между
// копиями — смесь (сглаженные внутри копии, они выходили мыльными). Уровень — как у OpenGL, по производным.
// Копий у листа 4 (FxAtlas.MIPS).
vec4 texels(vec2 uv, vec2 dx, vec2 dy) {
    vec2 size = vec2(textureSize(Sampler0, 0));
    dx *= size;
    dy *= size;
    float lod = clamp(0.5 * log2(max(max(dot(dx, dx), dot(dy, dy)), 1e-12)), 0.0, 4.0);
    float l0 = floor(lod);
    vec2 s0 = size / exp2(l0), s1 = s0 * 0.5;
    vec4 a = textureLod(Sampler0, (floor(uv * s0) + 0.5) / s0, l0);
    vec4 b = textureLod(Sampler0, (floor(uv * s1) + 0.5) / s1, l0 + 1.0);
    return mix(a, b, lod - l0);
}

void main() {
    // производные — до ветвления (вне его они определены у всех пикселей)
    vec2 dx = dFdx(texCoord0), dy = dFdy(texCoord0);
    vec4 tex = nearest > 0.5 ? texels(texCoord0, dx, dy) : textureGrad(Sampler0, texCoord0, dx, dy);
    // частица бледнее 0,1 — не рисуется, как у ванильного шейдера частиц (иначе мутный ореол вокруг облака)
    if (nearest > 0.5 && tex.a * opacity < 0.1) discard;
    vec4 color = vec4(vertexColor.rgb * tex.rgb, vertexColor.a * tex.a);
    if (FxScene > 0.5 && softness > 0.0) {
        // клуб тает, входя в землю и стены, и пропадает за рельефом (и за LOD Distant Horizons)
        float scene = texture(Sampler1, gl_FragCoord.xy / ScreenSize).r;
        color *= clamp((scene - viewDistance) / softness, 0.0, 1.0);
    }
    // у самой камеры квадрат не режет экран краем ближней плоскости
    color *= clamp((viewDistance - 0.3) / 1.2, 0.0, 1.0);
    color.rgb = mix(color.rgb, FxFogColor.rgb * color.a, fog * FxFogColor.a);
    fragColor = color * ColorModulator;
}
