#version 150

// Расстояние до мира за пикселем (client/fx/layer/SceneDepth): квадрат во весь кадр.
in vec3 Position;

void main() {
    gl_Position = vec4(Position.xy, 0.0, 1.0);
}
