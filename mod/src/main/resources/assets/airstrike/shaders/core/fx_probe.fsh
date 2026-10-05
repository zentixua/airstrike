#version 150

// Какая доля источника видна в кадре (client/fx/layer/SourceProbes): расстояние до мира за пикселем (SceneDepth,
// с LOD Distant Horizons) в середине источника на экране и по верхней половине круга вокруг неё — против расстояния
// до его передней половины. Вне кадра — 1: там видимость решают лучи вызывающего.
uniform sampler2D Sampler0;

// вид от глаза и проекция кадра
uniform mat4 SourceView;
uniform mat4 SourceProj;
// угол пикселя, рад
uniform float Pixel;

in vec3 center;
in float radius;

out vec4 fragColor;

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
            n++;
        }
        if (n > 0) seen = sum / float(n);
    }
    fragColor = vec4(seen, 0.0, 0.0, 1.0);
}
