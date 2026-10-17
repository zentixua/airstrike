#version 150

#moj_import <incoming:pall.glsl>

// Слой эффектов (client/fx/layer): дым, пламя, искры, вспышки и всё дальнее одним проходом после мира.
in vec3 Position;
in vec2 UV0;
// свет, уже умноженный на непрозрачность; альфа — сколько закрыто того, что за частицей
in vec4 Color;
// x: мягкость края у рельефа, блоков × 16 (меньше нуля — свет в глазу от источника: минус номер его пробы,
// SourceProbes); y: во сколько раз точка перенесена ближе (дальше дальней плоскости), × 32767
in ivec2 UV1;
// свет мира (карта освещения)
in ivec2 UV2;
// x: туман Minecraft 0..1; y: 1 — частица (ближайшим пикселем, как из атласа Minecraft), −1 — плитка атласа дальних
// моделей (свет в ней уже есть), ½ — ореол far/glow (крупный — по формуле, fx.fsh); z: непрозрачность частицы без текстуры
in vec3 Normal;

uniform sampler2D Sampler2;
// видимая доля источников кадра (SourceProbes): проба i — пиксель (i % ширина, i / ширина)
uniform sampler2D Sampler4;
// 1 — пробы есть
uniform float FxProbes;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// огненные шары (FxLights), по столбцу на шар: центр от камеры и радиус (0 — шара нет); освещённость у его поверхности
// против света вокруг глаза, с цветом, и его огонь на экране (w)
uniform mat4 FxBalls;
uniform mat4 FxBallLight;
// пелена ядерного удара (pall.glsl, PallPass): таблица поля, слоты подрывов, рамка, её средний свет (w — 1, если есть)
uniform sampler2D Sampler5;
uniform mat4 PallSlot0;
uniform mat4 PallSlot1;
uniform vec3 PallMin;
uniform vec3 PallMax;
uniform vec4 PallAmbient;
// огненный шторм: карты огня слотов и слоты огня (pall.glsl) — свет пожаров снизу на дыме и пыли слоя
uniform sampler2D Sampler7;
uniform sampler2D Sampler8;
uniform mat4 PallFire0;
uniform mat4 PallFire1;
// рельеф и вода под пеленой слотов, их числа и вода слотов (pall.glsl): из чего пелена перед квадратом
uniform sampler2D Sampler9;
uniform sampler2D Sampler10;
uniform vec4 PallGround0;
uniform vec4 PallGround1;
uniform vec4 PallWater0;
uniform vec4 PallWater1;

out vec2 texCoord0;
out vec4 vertexColor;
out float viewDistance;
out float softness;
out float fog;
out float nearest;
out float opacity;
// сколько пропускает пелена между глазом и квадратом (её свет — в fx.fsh, по пелене на кадре)
out float pallFront;

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// дальше стольких радиусов шар не освещает (от половины — плавно к нулю): теней нет, и ночью свет шара за сотни блоков
// ложился бы и на дым за холмом
const float BALL_REACH = 16.0;

// свет точки p (от камеры) огненными шарами на экране (gain — свет неба на экране): у поверхности шара — его освещённость,
// дальше — как (r/d)²; с колена — не ярче огня самого шара на экране: освещённое не бывает ярче источника
vec3 balls(vec3 p, float gain) {
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        float r = FxBalls[i].w;
        if (r <= 0.0) continue;
        vec3 v = FxBalls[i].xyz - p;
        float d2 = max(dot(v, v), r * r), reach = BALL_REACH * r * BALL_REACH * r;
        vec3 add = gain * FxBallLight[i].rgb * (r * r / d2) * (1.0 - smoothstep(0.25 * reach, reach, d2));
        sum += add / (1.0 + dot(add, LUMA) / max(FxBallLight[i].w, 1e-4));
    }
    return sum;
}

// свет горящего города снизу на точке p (от камеры): пламя со всей площади шириной ~2h под ней, как зарево на пелене
// (fx_pall), вполовину: клуб частиц освещён снизу только с одной стороны; у земли — сильнее, выше дым между ними его гасит
vec3 firelight(sampler2D map, mat4 S, mat4 F, vec3 p) {
    if (F[0].x <= 0.0) return vec3(0.0);
    float h = p.y - S[0].z;
    float flame = textureLod(map, fireUv(S, F, p.xz), fireGlowLod(h, F[0].y)).r;
    return F[3].rgb * (0.5 * F[1].w * flame * exp(-max(h, 0.0) / F[0].z));
}

// Пелена перед точкой p (настоящей, от глаза): объём пелены уже на кадре (PallPass) — до мира за пикселем; квадрат
// ближе мира должен быть за той её частью, что между ним и глазом. Шесть шагов по полю без шума (среднее по шуму — оно).
float pall(vec3 p) {
    float len = length(p);
    if (PallAmbient.w < 0.5 || len < 1e-3) return 1.0;
    vec3 dir = p / len;
    vec2 span = pallBox(dir, PallMin, PallMax);
    float t0 = span.x, t1 = min(span.y, len);
    if (t1 <= t0) return 1.0;
    float tau = 0.0, ds = (t1 - t0) / 6.0;
    for (int i = 0; i < 6; i++) {
        vec3 q = dir * (t0 + (float(i) + 0.5) * ds);
        // по полной толще всего вещества — как луч объёма (водяная пыль тоже гасит то, что за ней)
        if (PallSlot0[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler9, PallSlot0, PallGround0, PallWater0, q);
            tau += (f.sigma.x + f.sigma.y + f.sigma.z) * ds;
        }
        if (PallSlot1[1].w > 0.5) {
            PallAt f = pallField(Sampler5, Sampler10, PallSlot1, PallGround1, PallWater1, q);
            tau += (f.sigma.x + f.sigma.y + f.sigma.z) * ds;
        }
    }
    return exp(-tau);
}

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    texCoord0 = UV0;
    float unfold = max(float(UV1.y) / 32767.0, 1e-4);
    vec4 light = Normal.y < -0.5 ? vec4(1.0) : texelFetch(Sampler2, UV2 / 16, 0);
    if (Normal.y > -0.5 && any(lessThan(UV2, ivec2(240)))) {
        // свет шаров — к карте освещения: в долях света неба на экране (карта при открытом небе без блоков) и вместе не
        // ярче света дня — экран больше не покажет; светящееся своим светом (FULL_BRIGHT) его не берёт
        vec3 add = balls(Position / unfold, dot(texelFetch(Sampler2, ivec2(0, 15), 0).rgb, LUMA));
        if (PallAmbient.w > 0.5) add += firelight(Sampler7, PallSlot0, PallFire0, Position / unfold) + firelight(Sampler8, PallSlot1, PallFire1, Position / unfold);
        light.rgb += add / max(1.0, dot(add, LUMA));
    }
    // блик и вуаль у глаза за ними ничто не режет: их видно, сколько видно в кадре самого источника
    float seen = 1.0;
    if (UV1.x < 0 && FxProbes > 0.5) {
        int i = -UV1.x - 1, w = textureSize(Sampler4, 0).x;
        seen = texelFetch(Sampler4, ivec2(i % w, i / w), 0).r;
    }
    vertexColor = vec4(Color.rgb * light.rgb, Color.a) * seen;
    // настоящее расстояние по оси взгляда: перенесённую ближе точку — обратно
    viewDistance = -view.z / unfold;
    softness = float(max(UV1.x, 0)) / 16.0;
    fog = Normal.x;
    nearest = Normal.y;
    opacity = Normal.z;
    pallFront = pall(Position / unfold);
}
