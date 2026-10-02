#version 150

// Слой эффектов (client/fx/layer): дым, пламя, искры, вспышки и всё дальнее одним проходом после мира.
in vec3 Position;
in vec2 UV0;
// свет, уже умноженный на непрозрачность; альфа — сколько закрыто того, что за частицей
in vec4 Color;
// x: мягкость края у рельефа, блоков × 16; y: во сколько раз точка перенесена ближе (дальше дальней плоскости), × 32767
in ivec2 UV1;
// свет мира (карта освещения)
in ivec2 UV2;
// x: туман Minecraft 0..1; y: 1 — частица (ближайшим пикселем, как из атласа Minecraft), −1 — плитка атласа дальних
// моделей (свет в ней уже есть); z: непрозрачность частицы без текстуры
in vec3 Normal;

uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// огненные шары (FxLights), по столбцу на шар: центр от камеры и радиус (0 — шара нет); освещённость у его поверхности
// против света вокруг глаза, с цветом, и его огонь на экране (w)
uniform mat4 FxBalls;
uniform mat4 FxBallLight;

out vec2 texCoord0;
out vec4 vertexColor;
out float viewDistance;
out float softness;
out float fog;
out float nearest;
out float opacity;

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
    vertexColor = vec4(Color.rgb * light.rgb, Color.a);
    // настоящее расстояние по оси взгляда: перенесённую ближе точку — обратно
    viewDistance = -view.z / unfold;
    softness = float(UV1.x) / 16.0;
    fog = Normal.x;
    nearest = Normal.y;
    opacity = Normal.z;
}
