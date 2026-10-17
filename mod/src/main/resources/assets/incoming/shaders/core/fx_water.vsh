#version 150

// Наклейка на воду (client/fx/layer/WaterDecal): квадрат во весь кадр.
in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
