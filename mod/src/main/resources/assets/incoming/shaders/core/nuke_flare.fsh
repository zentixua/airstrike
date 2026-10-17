#version 150

// Круглое свечение по формуле, а не из картинки: ореол шара бывает шире экрана, и картинка 64×64 вышла бы в нём
// квадратами по её пикселю (ближайшим пикселем — ступенями, гладко — изломом выборки на каждом). Яркое ядро и мягкий
// ореол (r — от середины в долях полуразмера), к краю — ноль без ступеньки; цвет и сила — у вершин.
in vec2 texCoord0;
in vec4 vertexColor;

uniform vec4 ColorModulator;

out vec4 fragColor;

float flare(float r) {
    float a = 0.6 * exp(-(r / 0.15) * (r / 0.15)) + 0.4 * exp(-(r / 0.5) * (r / 0.5));
    return a * smoothstep(0.0, 0.3, 1.0 - r);
}

void main() {
    float a = flare(length(texCoord0 * 2.0 - 1.0));
    if (a <= 0.0) {
        discard;
    }
    fragColor = vec4(vertexColor.rgb, vertexColor.a * a) * ColorModulator;
}
