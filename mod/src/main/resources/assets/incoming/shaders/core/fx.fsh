#version 150

#moj_import <incoming:pall.glsl>

// Слой эффектов (client/fx/layer). Смешивание ONE, ONE_MINUS_SRC_ALPHA: цвет — свет, уже умноженный на
// непрозрачность, альфа — сколько закрыто того, что за частицей (у искр и ореолов 0 — свет складывается).
// Текстура листа — тоже с умноженной альфой. Бледное (альфа меньше 0,1) не отбрасывается, как у ванильных шейдеров:
// дальний дым и зарево как раз бледные.
uniform sampler2D Sampler0;
// расстояние до мира за пикселем (SceneDepth)
uniform sampler2D Sampler1;
// атлас дальних моделей (client/render/FarModels), с умноженной альфой и уменьшенными копиями
uniform sampler2D Sampler3;
// пелена на кадре (PallPass, мельче экрана): свет её до мира за пикселем (rgb) и сколько она пропускает (a)
uniform sampler2D Sampler6;
// средний свет пелены (rgb); w — 1, если пелена на кадре есть
uniform vec4 PallAmbient;
// место ореола far/glow на листе (u0, v0, u1, v1; FxAtlas.glow)
uniform vec4 FxGlow;

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
in float pallFront;

out vec4 fragColor;

// Частица вблизи — как из атласа Minecraft (NEAREST_MIPMAP_LINEAR): ближайший пиксель картинки и её копии вдвое
// меньше, между ними — смесь; сглаженная, она выходила бы мыльной. Мельче копии вдвое (клуб в 32 пикселя и меньше) —
// гладко, как всё дальнее: ступеньки там только рябят. Уровень копии — как у OpenGL, по производным.
vec4 particle(vec2 uv, vec2 dx, vec2 dy, out float lod) {
    vec2 size = vec2(textureSize(Sampler0, 0));
    vec2 px = dx * size, py = dy * size;
    lod = max(0.5 * log2(max(max(dot(px, px), dot(py, py)), 1e-12)), 0.0);
    if (lod >= 1.0) return textureGrad(Sampler0, uv, dx, dy);
    vec2 size1 = size * 0.5;
    vec4 a = textureLod(Sampler0, (floor(uv * size) + 0.5) / size, 0.0);
    vec4 b = textureLod(Sampler0, (floor(uv * size1) + 0.5) / size1, 1.0);
    return mix(a, b, lod);
}

// Ореол far/glow (tools/gen_particles.py, far_glow) на расстоянии r от середины в долях полуразмера: exp(−(r/0,42)²),
// к краю — ноль без ступеньки; пик картинки — у четырёх средних пикселей (r = √2/63), он — 1
float glowAlpha(float r) {
    return exp(-(r / 0.42) * (r / 0.42)) * smoothstep(0.0, 0.25, 1.0 - r) / 0.997147;
}

void main() {
    // производные — до ветвления (вне его они определены у всех пикселей)
    vec2 dx = dFdx(texCoord0), dy = dFdy(texCoord0);
    float lod = 0.0;
    vec4 tex;
    if (nearest > 0.75) {
        tex = particle(texCoord0, dx, dy, lod);
    } else if (nearest < -0.5) {
        tex = textureGrad(Sampler3, texCoord0, dx, dy);
    } else {
        tex = textureGrad(Sampler0, texCoord0, dx, dy);
        if (nearest > 0.25) {
            // ореол (FxQuads.glowShape) крупнее четверти картинки на экране — по формуле: из картинки 64×64 он вышел бы
            // ступенями квадратами по её пикселю. Середины крайних пикселей картинки — r = 1 (63 шага на 64 пикселя)
            vec2 size = vec2(textureSize(Sampler0, 0)), px = dx * size, py = dy * size;
            if (max(dot(px, px), dot(py, py)) < 16.0) {
                vec2 local = ((texCoord0 - FxGlow.xy) / (FxGlow.zw - FxGlow.xy) * 2.0 - 1.0) * (64.0 / 63.0);
                tex = vec4(glowAlpha(length(local)));
            }
        }
    }
    // частица вблизи бледнее 0,1 не рисуется, как у ванильного шейдера частиц: иначе бледные ореолы клубов складываются
    // в мутную дымку вокруг облака. Вдали (клуб мельче 16 пикселей) — без среза: дальний дым и так бледный
    if (nearest > 0.75 && tex.a * opacity < 0.1 * clamp(2.0 - lod, 0.0, 1.0)) discard;
    vec4 color = vec4(vertexColor.rgb * tex.rgb, vertexColor.a * tex.a);
    if (PallAmbient.w > 0.5) {
        // пелена перед квадратом: его свет ослаблен, а закрытое им — уже её свет до него (пелена на кадре — до мира).
        // Квадрат ближе мира: перед ним пелены не больше, чем на кадре до мира, — и её свет до него — доля света пелены
        // на кадре по доле ослабления. Средний свет годится только там, где пелены на кадре почти нет: иначе клуб за
        // густой пеленой выходил бы пятном другого цвета (вдали — светлыми точками на тёмной пелене)
        vec4 frame = texture(Sampler6, gl_FragCoord.xy / ScreenSize);
        // негодный пиксель пелены (NaN, бесконечность) — пелены перед квадратом нет: NaN дал бы чёрный квадрат
        if (!pallValid(frame)) frame = vec4(0.0, 0.0, 0.0, 1.0);
        float front = max(pallFront, frame.a);
        vec3 before = mix(PallAmbient.rgb, frame.rgb / max(1.0 - frame.a, 1e-3), smoothstep(0.02, 0.15, 1.0 - frame.a));
        color.rgb = color.rgb * front + color.a * before * (1.0 - front);
    }
    if (FxScene > 0.5 && softness > 0.0) {
        // клуб тает, входя в землю и стены, и пропадает за рельефом (и за LOD Distant Horizons)
        // негодное расстояние (NaN, за глазом) — мира за пикселем нет, как у неба
        float scene = pallScene(texture(Sampler1, gl_FragCoord.xy / ScreenSize).r);
        color *= clamp((scene - viewDistance) / softness, 0.0, 1.0);
    }
    // у самой камеры квадрат не режет экран краем ближней плоскости
    color *= clamp((viewDistance - 0.3) / 1.2, 0.0, 1.0);
    // туман Minecraft гасит частицу, а не красит её в свой цвет: за ней и так мир в тумане, а непрозрачное пятно цвета
    // тумана у края прорисовки закрывало бы ленты шлейфов и дальнюю картинку взрывов, которые сменяют частицы там
    color *= 1.0 - fog * FxFogColor.a;
    fragColor = color * ColorModulator;
}
