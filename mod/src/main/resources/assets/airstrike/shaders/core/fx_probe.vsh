#version 150

// Пробы видимости источников (client/fx/layer/SourceProbes): квадрат ровно на пиксель своей пробы.
// середина источника от глаза
in vec3 Position;
// x: радиус источника, блоков; y: номер пробы (с 0)
in vec2 UV0;
// угол квадрата: ±1, ±1
in vec3 Normal;

// пикселей текстуры проб: в строке, строк
uniform vec2 ProbeSize;

out vec3 center;
out float radius;

void main() {
    int i = int(UV0.y + 0.5), w = int(ProbeSize.x);
    vec2 cell = vec2(float(i % w), float(i / w)) + 0.5 + 0.5 * Normal.xy;
    gl_Position = vec4(cell / ProbeSize * 2.0 - 1.0, 0.0, 1.0);
    center = Position;
    radius = UV0.x;
}
