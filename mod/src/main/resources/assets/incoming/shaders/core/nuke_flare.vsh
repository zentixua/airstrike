#version 150

// Круглое свечение ядерного удара (client/nuclear/NukeRenderer, NukeFlash): квадрат, UV0 — от 0 до 1 по сторонам.
in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
out vec4 vertexColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vertexColor = Color;
}
