#version 150

#moj_import <incoming:pall.glsl>
#moj_import <incoming:mushroom.glsl>

// Карта тени грибов от солнца (client/fx/layer/PallPass, до лучей пелены; mushroom.glsl: карта тени): пиксель — тексель
// плитки гриба, прямая вдоль солнца через его место в плоскости поперёк солнца. Толща — поле гриба без шума (mushMean,
// среднее по клубам: тень на земле — от массы облака, её край размыт размахом клубов), шагами по отрезку прямой в рамке
// гриба от солнца вниз; пустое — прыжком до вещества (mushGap). Где прямая проходит высоты слоёв, вещество над ними
// записывается (толща, её середина на прямой, ширина облака поперёк и альбедо); ниже последнего шага — всё вещество
// прямой. Свет к слою — прямой пучок сквозь эту толщу и рассеянный ею вперёд: два потока в столбе вдоль солнца (как свет
// на самом грибе: альбедо и асимметрия — у каждой части свои, у столба воды — капель), на землю — та доля, с которой
// облако такой ширины на таком расстоянии видно с земли (mushSeen; ширина части — по площади её тени, mushAcross).

// таблица грибов (MushroomField)
uniform sampler2D Sampler0;
// направление на солнце (xyz) и сила солнца днём (w)
uniform vec4 PallSun;

out vec4 fragColor;

// Свет солнца к слою под веществом гриба (MushroomField.shadowThrough): толща tau, середина вещества на прямой — в d от
// слоя, полуширина облака поперёк width, альбедо w и асимметрия g (после усечения пика); mu — косинус пучка на землю
// у вывода на кадр
float through(float tau, float d, float width, float w, float g, float mu) {
    if (tau < 1e-4) return 1.0;
    float scattered = max(pallDiffuse(w, g, tau, tau, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, -0.6666667), 0.0);
    return exp(-tau) + scattered * mushSeen(width, d, mu);
}

void main() {
    ivec2 at = ivec2(gl_FragCoord.xy);
    int k = at.y / MUSH_SHADOW;
    if (k >= MUSHROOMS || !mushOn(Sampler0, k)) {
        fragColor = vec4(1.0);
        return;
    }
    Mush m = mushLoad(Sampler0, k);
    vec4 rect = texelFetch(Sampler0, ivec2(MUSH_SHADOW_RECT, k), 0);
    float top = texelFetch(Sampler0, ivec2(MUSH_SHADOW_TOP, k), 0).x;
    vec3 sun = mushShadowSun(PallSun.xyz), a, b;
    mushShadowAxes(sun, a, b);
    // полуширина шапки, юбки и столба воды поперёк солнца — по площади их тени (ножки — на высоте шага); шапка — от низа
    // кольца до верха купола
    float capH = max(m.capY + m.ringB, m.domeY + m.domeH) - (m.capY - m.ringB);
    float capW = mushAcross(m.capR, capH, sun.y), skirtW = mushAcross(m.skirtR + m.skirtW, m.skirtH, sun.y);
    float colW = mushAcross(max(m.colMid + m.colHalf, m.crownR), m.colTop + m.crownH, sun.y);
    vec2 uv = (vec2(float(at.x), float(at.y - k * MUSH_SHADOW)) + 0.5) / float(MUSH_SHADOW);
    vec2 w = rect.xy + uv * rect.zw;
    // прямая: точка плоскости поперёк солнца (от глаза) и шаг вдоль солнца; высота над землёй под эпицентром — h0 + sun.y·s
    vec3 o = m.base + a * w.x + b * w.y;
    vec2 span = mushLine(m, o, sun);
    float h0 = o.y - m.base.y;
    // места высот слоёв на прямой: земля под эпицентром, середина между ней и верхом мира, верх
    vec3 layer = (vec3(0.0, 0.5 * top, top) - h0) / sun.y;
    // вещество над слоями: толща и её доли с местом на прямой, шириной облака, альбедо и асимметрией (поделить на толщу)
    vec3 tauL = vec3(0.0), atL = vec3(0.0), wideL = vec3(0.0), albL = vec3(0.0), asymL = vec3(0.0);
    float tau = 0.0, atS = 0.0, wide = 0.0, alb = 0.0, asym = 0.0, s = span.y;
    if (span.y > span.x) {
        float ds = (span.y - span.x) / float(MUSH_SHADOW_STEPS);
        for (int i = 0; i <= MUSH_SHADOW_STEPS; i++) {
            if (s <= span.x || tau >= MUSH_SHADOW_DARK) break;
            float gap = mushGap(m, mushLocal(m, o + sun * s));
            // вниз от s на шаг (или прыжок по пустому — без вещества), выборка — в середине шага
            float from = max(s - max(gap, ds), span.x);
            float sigma = 0.0, width = 0.0, albedo = 0.0, g = 0.0;
            if (gap <= ds) {
                vec3 q = mushLocal(m, o + sun * (0.5 * (s + from)));
                vec4 matter = mushMean(m, q);
                sigma = dot(matter, vec4(1.0));
                if (sigma > 0.0) {
                    width = (matter.x * capW + matter.y * mushAcross(mushStemAt(m, q.y), m.stemTop, sun.y) + matter.z * skirtW + matter.w * colW) / sigma;
                    albedo = (matter.x * m.capMat.g + matter.y * m.stemMat.g + matter.z * m.skirtMat.g + matter.w * m.colMat.g) / sigma;
                    g = (matter.x * m.capMat.a + matter.y * m.stemMat.a + matter.z * m.skirtMat.a + matter.w * m.colMat.a) / sigma;
                }
            }
            // слои, которые прямая проходит на этом шаге: вещество над ними — до них
            vec3 crossed = step(vec3(from), layer) * (vec3(1.0) - step(vec3(s), layer));
            vec3 part = sigma * max(vec3(s) - layer, vec3(0.0));
            tauL = mix(tauL, vec3(tau) + part, crossed);
            atL = mix(atL, vec3(atS) + part * 0.5 * (vec3(s) + layer), crossed);
            wideL = mix(wideL, vec3(wide) + part * width, crossed);
            albL = mix(albL, vec3(alb) + part * albedo, crossed);
            asymL = mix(asymL, vec3(asym) + part * g, crossed);
            float dt = sigma * (s - from);
            tau += dt;
            atS += dt * 0.5 * (s + from);
            wide += dt * width;
            alb += dt * albedo;
            asym += dt * g;
            s = from;
        }
    }
    // слои ниже последнего шага — под всем веществом прямой
    vec3 below = step(layer, vec3(s));
    tauL = mix(tauL, vec3(tau), below);
    atL = mix(atL, vec3(atS), below);
    wideL = mix(wideL, vec3(wide), below);
    albL = mix(albL, vec3(alb), below);
    asymL = mix(asymL, vec3(asym), below);
    vec3 n = max(tauL, vec3(1e-6));
    float mu = max(PallSun.y, 0.12);
    fragColor = vec4(through(tauL.x, atL.x / n.x - layer.x, wideL.x / n.x, albL.x / n.x, asymL.x / n.x, mu),
            through(tauL.y, atL.y / n.y - layer.y, wideL.y / n.y, albL.y / n.y, asymL.y / n.y, mu),
            through(tauL.z, atL.z / n.z - layer.z, wideL.z / n.z, albL.z / n.z, asymL.z / n.z, mu), 1.0);
}
