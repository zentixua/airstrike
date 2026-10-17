#version 150

// Какая доля источника видна в кадре (client/fx/layer/SourceProbes): расстояние до мира за пикселем (SceneDepth,
// с LOD Distant Horizons) в середине источника на экране и по верхней половине круга вокруг неё — против расстояния
// до его передней половины. Вне кадра — 1: там видимость решают лучи вызывающего. Глубина мира не видит объёма пелены
// ядерного удара, стены пыли и грибов (огненный шар за пылью, за чужой шапкой или ножкой): их пропускание до источника —
// здесь же, в кадре и вне его (pall_front.glsl, как квадратам слоя; блику и вуали слой их больше не режет).
#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>
#moj_import <incoming:pall_front.glsl>

uniform sampler2D Sampler0;

// вид от глаза и проекция кадра
uniform mat4 SourceView;
uniform mat4 SourceProj;
// угол пикселя, рад
uniform float Pixel;

in vec3 center;
in float radius;

out vec4 fragColor;

// Пропускание грибов от глаза до источника с серединой center радиуса radius, без шума (как mushTransmit). Гриб, чья
// середина шапки — сам источник (огненный шар и шапка светят из неё), себя не закрывает: его шапка — только до передней
// границы источника с размахом клубов края, а ножка и юбка между глазом и шапкой — до середины
float probeMush(sampler2D data, vec3 center, float radius) {
    float len = length(center), through = 1.0;
    vec3 dir = center / len;
    for (int k = 0; k < MUSHROOMS; k++) {
        if (!mushOn(data, k)) continue;
        Mush m = mushLoad(data, k);
        vec3 c = mushLocal(m, center) - vec3(0.0, m.capY, 0.0);
        float capEnd = len;
        if (dot(c, c) < 0.25 * m.capR * m.capR) capEnd = len - radius - MUSH_CAP_REACH * mushCapUnit(m);
        vec2 span = mushSpan(m, dir);
        span.y = min(span.y, len);
        if (span.y <= span.x) continue;
        float ds = (span.y - span.x) / 8.0, tau = 0.0;
        for (int i = 0; i < 8; i++) {
            float t = span.x + (float(i) + 0.5) * ds;
            vec3 s = mushMean(m, mushLocal(m, dir * t));
            tau += (step(t, capEnd) * s.x + s.y + s.z) * ds;
        }
        through *= 1.0 - mushAir(m, 0.5 * (span.x + span.y)) * (1.0 - exp(-tau));
    }
    return through;
}

// круг проб — доля радиуса (как лучи по блокам, FarBlasts.RING); середина, бока и верх: низ диска у земли закрыт землёй
// под самим источником
const float RING = 0.7;
const int TAPS = 6;
const vec2 TAP[TAPS] = vec2[TAPS](vec2(0.0, 0.0), vec2(1.0, 0.0), vec2(0.7071, 0.7071), vec2(0.0, 1.0), vec2(-0.7071, 0.7071),
        vec2(-1.0, 0.0));
// передняя половина и мягкость в радиусах — как у тела в слое (FarSprites.BALL_FRONT, BODY_SOFT), но не меньше
// полублока: у факела снаряда (радиус 0,2–0,6) это доли блока, а глубина вдали и LOD DH точнее не бывают — блик мигал бы
const float FRONT = 0.5, SOFT = 0.25, MIN_BLOCKS = 0.5;

void main() {
    vec4 view = SourceView * vec4(center, 1.0);
    // расстояние по оси взгляда, как в SceneDepth
    float d = -view.z, seen = 1.0;
    if (d > 0.0) {
        vec4 clip = SourceProj * view;
        vec2 size = vec2(textureSize(Sampler0, 0));
        vec2 at = (clip.xy / clip.w * 0.5 + 0.5) * size;
        float ring = RING * radius / (length(view.xyz) * Pixel);
        float front = d - max(FRONT * radius, MIN_BLOCKS), soft = max(SOFT * radius, MIN_BLOCKS), sum = 0.0;
        int n = 0;
        for (int k = 0; k < TAPS; k++) {
            vec2 p = at + TAP[k] * ring;
            if (any(lessThan(p, vec2(0.0))) || any(greaterThanEqual(p, size))) continue;
            sum += clamp((texelFetch(Sampler0, ivec2(p), 0).r - front) / soft, 0.0, 1.0);
            // не n++: Veil (его несёт Sable) пересобирает шейдеры через glsl-processor 0.2.3, а тот теряет «;» после n++
            n += 1;
        }
        if (n > 0) seen = sum / float(n);
    }
    // пелена — до передней границы источника, грибы — до середины без своей шапки (пелены и грибов на кадре нет —
    // PallAmbient.w = 0)
    float len = length(center);
    if (PallAmbient.w > 0.5 && len > radius) seen *= pallThrough(center / len, len - radius) * probeMush(Sampler11, center, radius);
    fragColor = vec4(seen, 0.0, 0.0, 1.0);
}
