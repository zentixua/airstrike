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
// моделей (свет в ней уже есть); z: непрозрачность частицы без текстуры
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

// Пелена перед точкой p (настоящей, от глаза): объём пелены уже на кадре (PallPass) — до мира за пикселем; квадрат
// ближе мира должен быть за той её частью, что между ним и глазом. Шесть шагов по полю без шума (среднее по шуму — оно).
float pall(vec3 p) {
    float len = length(p);
    if (PallAmbient.w < 0.5 || len < 1e-3) return 1.0;
    vec3 dir = p / len;
    vec2 span = pallBox(dir, PallMin, PallMax);
    float t0 = span.x, t1 = min(span.y, len);
    if (t1 <= t0) return 1.0;
    float tau = 0.0, ds = (t1 - t0) / 6.0, dense;
    vec2 column, height;
    for (int i = 0; i < 6; i++) {
        vec3 q = dir * (t0 + (float(i) + 0.5) * ds);
        if (PallSlot0[1].w > 0.5) {
            vec4 f = pallField(Sampler5, PallSlot0, q, column, height, dense);
            tau += (f.x + f.y) * ds;
        }
        if (PallSlot1[1].w > 0.5) {
            vec4 f = pallField(Sampler5, PallSlot1, q, column, height, dense);
            tau += (f.x + f.y) * ds;
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
